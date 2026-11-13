package net.voidclear.v2.listener;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small reader for the supplied bin.yml layout; unknown button actions are inert. */
final class TrashMenuConfig {
    record Button(String name, Material material, List<String> lore, String action, boolean glow) {}
    private final String title;
    private final List<String> layout;
    private final Map<Character, Button> buttons;
    private final String disabledLore;
    private final List<Integer> itemSlots;

    private TrashMenuConfig(String title, List<String> layout, Map<Character, Button> buttons, String disabledLore) {
        this.title = title;
        this.layout = layout;
        this.buttons = buttons;
        this.disabledLore = disabledLore;
        itemSlots = new ArrayList<>();
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            for (int col = 0; col < 9; col++) {
                char key = line.charAt(col);
                if (key == ' ' || !buttons.containsKey(key)) itemSlots.add(row * 9 + col);
            }
        }
        if (itemSlots.isEmpty()) throw new IllegalStateException("垃圾箱菜单没有物品位");
    }

    static TrashMenuConfig load(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); }
        catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException broken) {
            throw new IllegalStateException("垃圾箱菜单读取失败，保留原菜单", broken);
        }
        List<String> layout = yaml.getStringList("Layout");
        if (layout.size() != 6 || layout.stream().anyMatch(line -> line.length() != 9)) {
            throw new IllegalStateException("menus/bin.yml 的 Layout 必须是 6 行、每行 9 个字符");
        }
        Map<Character, Button> buttons = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("Buttons");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key.length() != 1) continue;
                String path = "Buttons." + key + ".display";
                Material material = Material.matchMaterial(yaml.getString(path + ".material", "CHEST"));
                if (material == null) throw new IllegalStateException("未知菜单材质：" + key);
                buttons.put(key.charAt(0), new Button(
                        yaml.getString(path + ".name", " "), material,
                        yaml.getStringList(path + ".lore").stream().map(TrashMenuConfig::updatedDefaultLore).toList(), yaml.getString("Buttons." + key + ".action", "none"),
                        yaml.getBoolean(path + ".glow", false)));
            }
        }
        String disabledLore = yaml.getString("Disabled-Icon.Lore", "&8（已到尽头）");
        if ("&8&o（已到尽头）".equals(disabledLore)) disabledLore = "&8（已到尽头）";
        return new TrashMenuConfig(yaml.getString("Title", "&8虚空清理 &7· 垃圾箱"), layout, buttons,
                disabledLore);
    }

    int pageSize() { return itemSlots.size(); }
    boolean sameAs(TrashMenuConfig other) {
        return title.equals(other.title) && layout.equals(other.layout)
                && buttons.equals(other.buttons) && disabledLore.equals(other.disabledLore);
    }
    List<Integer> itemSlots() { return itemSlots; }
    char layoutAt(int slot) { return layout.get(slot / 9).charAt(slot % 9); }
    String title() { return title; }
    String title(Map<String, String> values) { return apply(title, values); }
    String disabledLore() { return disabledLore; }
    Button button(char key) { return buttons.get(key); }

    ItemStack icon(Button button, Map<String, String> values, boolean disabled) {
        ItemStack stack = new ItemStack(button.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(apply(button.name(), values)));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        button.lore().forEach(line -> lore.add(text(apply(line, values))));
        if (disabled) lore.add(text(apply(disabledLore, values)));
        meta.lore(lore);
        if (button.glow()) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static String updatedDefaultLore(String line) {
        return switch (line) {
            case "&7垃圾数量：&f%COUNT% &7件" -> "&7库存物品：&f%COUNT% &7件";
            case "&7清空倒计时：&f%CLEAR_TIME%" -> "&7自动清空剩余：&f%CLEAR_TIME%";
            case "&7下次清空：&f%CLEAR_AT%" -> "&7下次自动清空：&f%CLEAR_AT%";
            default -> line;
        };
    }

    private static String apply(String value, Map<String, String> values) {
        for (var entry : values.entrySet()) value = value.replace(entry.getKey(), entry.getValue());
        return value;
    }

    private static net.kyori.adventure.text.Component text(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('\u00a7', '&'))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
