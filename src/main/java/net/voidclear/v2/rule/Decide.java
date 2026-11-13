package net.voidclear.v2.rule;

import net.voidclear.v2.config.Sections;
import net.voidclear.v2.target.Tags;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「这个实体或这件掉落物该不该扫」：唯一判定处。
 *
 * <p>玩家、Boss、带保护标记的实体始终保留，命名保护按配置；通用与分类保留规则优先，
 * 世界 keep / sweep 分别覆盖全局同名规则。实际清扫与命令查询共用 {@link #verdict}。</p>
 *
 * <p>判定不缓存：{@link Sections.Policy} 每次都从当前配置读，所以 {@code /vc reload} 之后立即生效。</p>
 */
public final class Decide {

    private final Sections.Policy policy;

    public Decide(Sections.Policy policy) {
        this.policy = policy;
    }

    /* ------------------------------------------------------------------ */
    /* 生物                                                                */
    /* ------------------------------------------------------------------ */

    public boolean sweeps(Entity entity) {
        if (entity instanceof Player || entity instanceof Boss || Tags.has(entity)) {
            return false;
        }
        if (policy.keepNamed() && named(entity)) {
            return false;
        }
        return verdict(entity.getWorld().getName(), entity.getType().getKey().toString(),
                isMonster(entity), entity instanceof Animals).remove();
    }

    /** 这个世界的判定，给 {@code /vc check} 与控制台看。 */
    public Outcome verdict(String world, String id, boolean monster, boolean animal) {
        if ("minecraft:player".equalsIgnoreCase(id)) {
            return Outcome.keep("player");
        }
        if ("minecraft:ender_dragon".equalsIgnoreCase(id) || "minecraft:wither".equalsIgnoreCase(id)) {
            return Outcome.keep("boss");
        }
        String needle = id.toLowerCase(Locale.ROOT);
        if ("minecraft:item".equalsIgnoreCase(id)) {
            ConfigurationSection drops = policy.dropsOf(world);
            if (!policy.drops(world) || drops == null || !drops.getBoolean("enable", true)) {
                return Outcome.keep("world:drops=false / drop:enable=false");
            }
            return Outcome.keep("仅凭实体 ID 无法判定掉落物；未命中物品豁免且无 keep 标记时删除");
        }
        if (!policy.creatures(world)) return Outcome.keep("world:creatures=false");
        if ("minecraft:experience_orb".equalsIgnoreCase(id)) {
            return policy.experience(world) ? Outcome.remove("world:experience-orbs=true")
                    : Outcome.keep("world:experience-orbs=false");
        }
        if (animal && !policy.animals(world)) return Outcome.keep("world:animals=false");
        if (monster && !policy.monsters(world)) return Outcome.keep("world:monsters=false");
        for (String entry : policy.whitelist()) {
            if (entry != null && matches(entry, needle, monster, animal)) {
                return Outcome.keep("whitelist:" + entry);
            }
        }
        List<String> categoryWhitelist = animal ? policy.animalWhitelist()
                : monster ? policy.monsterWhitelist() : List.of();
        for (String entry : categoryWhitelist) {
            if (entry != null && matches(entry, needle, monster, animal)) {
                return Outcome.keep((animal ? "animal-whitelist:" : "monster-whitelist:") + entry);
            }
        }
        ConfigurationSection section = policy.world(world);
        List<String> keep = section != null && section.isSet("keep") ? section.getStringList("keep") : policy.keep();
        List<String> sweep = section != null && section.isSet("sweep") ? section.getStringList("sweep") : policy.sweep();
        List<String> categoryKeep = animal ? policy.animalKeep() : monster ? policy.monsterKeep() : List.of();
        List<String> categorySweep = animal ? policy.animalSweep() : monster ? policy.monsterSweep() : List.of();
        for (String entry : keep) {
            if (entry != null && matches(entry, needle, monster, animal)) {
                return Outcome.keep("keep:" + entry);
            }
        }
        for (String entry : categoryKeep) {
            if (entry != null && matches(entry, needle, monster, animal)) {
                return Outcome.keep("category-keep:" + entry);
            }
        }
        for (String entry : sweep) {
            if (entry != null && matches(entry, needle, monster, animal)) {
                return Outcome.remove("sweep:" + entry);
            }
        }
        for (String entry : categorySweep) {
            if (entry != null && matches(entry, needle, monster, animal)) {
                return Outcome.remove("category-sweep:" + entry);
            }
        }
        return Outcome.keep("no-rule");
    }

    public static boolean isMonster(Entity entity) {
        return entity instanceof Enemy && !(entity instanceof Animals);
    }

    private boolean matches(String entry, String id, boolean monster, boolean animal) {
        String needle = entry.trim().toLowerCase(Locale.ROOT);
        if (needle.equals("*")) {
            return true;
        }
        if (needle.endsWith(":*")) {
            return id.startsWith(needle.substring(0, needle.length() - 1)) && vanilla(id);
        }
        if (needle.equals(id)) {
            return vanilla(id);
        }
        if (needle.equals("animals") || needle.equals("animal")) {
            return animal && vanilla(id);
        }
        if (needle.equals("monster") || needle.equals("monsters")) {
            return monster && vanilla(id);
        }
        return false;
    }

    /** {@code vanilla-only} 打开时，模组实体不参与按 id 的名单匹配。 */
    private boolean vanilla(String id) {
        return !policy.vanillaOnly() || id.startsWith("minecraft:");
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static boolean named(Entity entity) {
        return entity.customName() != null && !plain(entity.customName()).isBlank();
    }

    /* ------------------------------------------------------------------ */
    /* 掉落物                                                              */
    /* ------------------------------------------------------------------ */

    public boolean sweeps(Item drop) {
        ConfigurationSection section = policy.dropsOf(drop.getWorld().getName());
        if (section == null || !section.getBoolean("enable", true)) {
            return false;
        }
        return dropProtectionReason(drop)==null;
    }

    public String dropProtectionReason(Item drop) {
        ConfigurationSection section=policy.dropsOf(drop.getWorld().getName());
        if(section==null||!section.getBoolean("enable",true)) return "掉落回收关闭";
        var protection=section.getConfigurationSection("protection");
        if(protection==null && policy.drops()!=null) protection=policy.drops().getConfigurationSection("protection");
        ItemStack stack=drop.getItemStack();
        String reason=ImportantDrops.reason(protection,stack);
        if(reason!=null)return reason;
        for(String key:section.getKeys(false)) {
            if(List.of("enable","protect-seconds","protection").contains(key))continue;
            if(exempt(section.getConfigurationSection(key),stack))return "物品豁免";
        }
        return null;
    }

    /** 写了的字段都要命中才算豁免。 */
    private boolean exempt(ConfigurationSection rule, ItemStack stack) {
        if (rule == null || stack == null) {
            return false;
        }
        String id = trim(rule.getString("id"));
        String name = trim(rule.getString("name"));
        String lore = trim(rule.getString("lore"));
        if (id == null && name == null && lore == null) {
            return false;
        }
        if (id != null && !id.equalsIgnoreCase(stack.getType().getKey().toString())) {
            return false;
        }
        if (name == null && lore == null) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (name != null) {
            String label = meta.hasDisplayName() && meta.displayName() != null ? plain(meta.displayName()) : "";
            if (!loose(name, label)) {
                return false;
            }
        }
        if (lore != null) {
            if (meta.lore() == null) {
                return false;
            }
            boolean any = false;
            for (var line : meta.lore()) {
                if (loose(lore, plain(line))) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** {@code "*关键字"} 子串匹配，其余完全相等。 */
    static boolean loose(String pattern, String text) {
        String value = pattern.trim();
        if (value.startsWith("*")) {
            return text.toLowerCase(Locale.ROOT).contains(value.substring(1).toLowerCase(Locale.ROOT));
        }
        return text.equals(value);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 掉落物判定用的空值检查。 */
    public static boolean empty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }
}
