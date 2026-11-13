package net.voidclear.v2.rule;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.Container;

/** Ground-drop protection only; it never changes or transfers item contents. */
final class ImportantDrops {
    static String reason(ConfigurationSection rules, ItemStack stack) {
        if (rules == null || stack == null || stack.isEmpty()) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        if (rules.getBoolean("enchanted", false) && (meta.hasEnchants()
                || meta instanceof EnchantmentStorageMeta book && book.hasStoredEnchants()))
            return "附魔物品保护";
        if (rules.getBoolean("nonempty-containers", true)) {
            if (meta instanceof BundleMeta bundle && bundle.hasItems()) return "非空容器保护";
            if (meta instanceof BlockStateMeta block && block.hasBlockState()
                    && block.getBlockState() instanceof Container container) {
                for (var item : container.getSnapshotInventory().getContents())
                    if (!Decide.empty(item)) return "非空容器保护";
            }
        }
        var patterns = rules.getStringList("pdc-keys");
        if (!patterns.isEmpty()) {
            for (var key : meta.getPersistentDataContainer().getKeys()) {
                for (var pattern : patterns) {
                    String p = pattern.trim();
                    if (p.equals(key.toString()) || p.equals(key.getNamespace() + ":*"))
                        return "自定义标记保护";
                }
            }
        }
        return null;
    }
}
