package net.voidclear.v2.target;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 实体上的持久化标记。
 *
 * <p>现在只用一个键：{@code keep}，带上的实体清扫时跳过。这个键是公开契约，
 * 别的插件可以自己往实体上写（用 {@link #key()} 取键），也可以用 {@code /vc keep} 打上。</p>
 */
public final class Tags {

    private static volatile NamespacedKey keep;

    private Tags() {
    }

    /** 插件启用时登记一次，之后判定不再依赖 Bukkit 的插件查找。 */
    public static void bind(Plugin plugin) {
        keep = new NamespacedKey(plugin, "keep");
    }

    public static boolean has(Entity entity) {
        NamespacedKey key = keep;
        if (key == null || entity == null) {
            return false;
        }
        // 读不到标记时一律按「已标记」：宁可少扫，不能把带标记的东西扫掉
        try {
            var data = entity.getPersistentDataContainer();
            return data.has(key, PersistentDataType.BYTE);
        } catch (RuntimeException broken) {
            return true;
        }
    }

    public static void mark(Entity entity) {
        NamespacedKey key = keep;
        if (key != null && entity != null) {
            entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** 去掉标记。 */
    public static void unmark(Entity entity) {
        NamespacedKey key = keep;
        if (key != null && entity != null) {
            entity.getPersistentDataContainer().remove(key);
        }
    }

    /** 公开键（别的插件写标记时用）；插件启用前是 null。 */
    public static NamespacedKey key() {
        return keep;
    }
}
