package net.voidclear.v2.rule;

import net.kyori.adventure.text.Component;
import net.voidclear.v2.config.Settings;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.List;

/** The command preview and actual entity sweep must agree, including after a reload. */
public final class DecideCheck {
    public static void main(String[] args) {
        Settings settings = Settings.blank();
        Decide decide = new Decide(settings.policy());
        Entity cow = entity(EntityType.COW, false, Animals.class);
        Entity zombie = entity(EntityType.ZOMBIE, false, Enemy.class);
        Entity stand = entity(EntityType.ARMOR_STAND, false);
        World world = cow.getWorld();
        YamlConfiguration config = new YamlConfiguration();
        config.set("rule.keep", List.of());
        config.set("rule.animal-keep", List.of());
        config.set("rule.monster-sweep", List.of());
        config.set("rule.sweep", List.of("minecraft:*"));
        settings.install(config);
        verify(decide, cow, true, false, true);
        verify(decide, zombie, true, true, false);
        verify(decide, stand, true, false, false);

        config.set("rule.animal-whitelist", List.of("animal"));
        config.set("rule.monster-keep", List.of("monsters"));
        verify(decide, cow, false, false, true);
        verify(decide, zombie, false, true, false);
        config.set("rule.animal-whitelist", List.of());
        config.set("rule.monster-keep", List.of());
        config.set("rule.whitelist", List.of(" MINECRAFT:ZOMBIE "));
        verify(decide, zombie, false, true, false);
        config.set("rule.whitelist", List.of());

        config.set("rule.world.world.keep", List.of("minecraft:armor_stand"));
        config.set("rule.world.world.sweep", List.of());
        verify(decide, stand, false, false, false);
        verify(decide, zombie, false, true, false);
        config.set("rule.monster-sweep", List.of("monster"));
        verify(decide, zombie, true, true, false);
        config.set("rule.world.world.monsters", false);
        verify(decide, zombie, false, true, false);
        config.set("rule.world.world.monsters", true);
        config.set("rule.world.world.creatures", false);
        verify(decide, zombie, false, true, false);

        config.set("rule.world", null);
        config.set("rule.named-kept", true);
        assert !decide.sweeps(entity(EntityType.ZOMBIE, true, Enemy.class)) : "Named protection lost";
        config.set("rule.named-kept", false);
        assert decide.sweeps(entity(EntityType.ZOMBIE, true, Enemy.class)) : "Named mobs ignored the configured rules";
        assert !decide.sweeps(entity(EntityType.PLAYER, false, Player.class));
        assert !decide.sweeps(entity(EntityType.WITHER, false, Boss.class));
        assert !Decide.isMonster(entity(EntityType.COW, false, Animals.class, Enemy.class));

        assert !decide.verdict(world.getName(), "custom:zombie", true, false).remove();
        config.set("rule.vanilla-only", false);
        assert decide.verdict(world.getName(), "custom:zombie", true, false).remove();
        config.set("rule.vanilla-only", true);
        config.set("rule.sweep", List.of("*"));
        assert decide.verdict(world.getName(), "custom:zombie", true, false).remove() : "Wildcard semantics changed";
        System.out.println("DecideCheck: ALL OK");
    }

    private static void verify(Decide decide, Entity entity, boolean expected, boolean monster, boolean animal) {
        assert decide.sweeps(entity) == expected : "Wrong actual verdict: " + entity.getType();
        assert decide.verdict(entity.getWorld().getName(), entity.getType().getKey().toString(), monster, animal).remove() == expected
                : "Command preview disagrees: " + entity.getType();
    }

    private static Entity entity(EntityType type, boolean named, Class<?>... categories) {
        World world = (World) Proxy.newProxyInstance(DecideCheck.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "world";
                    throw new AssertionError(method.getName());
                });
        Class<?>[] interfaces = new Class<?>[categories.length + 1];
        interfaces[0] = Entity.class;
        System.arraycopy(categories, 0, interfaces, 1, categories.length);
        return (Entity) Proxy.newProxyInstance(DecideCheck.class.getClassLoader(), interfaces, (proxy, method, args) -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getType" -> type;
            case "customName" -> named ? Component.text("Protected?") : null;
            default -> throw new AssertionError(method.getName());
        });
    }
}
