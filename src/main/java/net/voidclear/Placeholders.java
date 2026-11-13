package net.voidclear;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class Placeholders extends PlaceholderExpansion {
    private final VoidClearPlugin plugin;

    public Placeholders(VoidClearPlugin plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "voidclear"; }
    @Override public String getAuthor() { return "VoidClear"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return resolve(plugin, params);
    }

    static String resolve(VoidClearPlugin plugin, String params) {
        if (params == null) return null;
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "sweep_seconds" -> String.valueOf(plugin.sweepTimer().secondsLeft());
            case "sweep_time" -> formatTime(plugin.sweepTimer().secondsLeft());
            case "sweep_at" -> formatDeadline(plugin.sweepTimer().running() ? plugin.sweepTimer().deadline() : 0);
            case "sweep_mode" -> plugin.settings().pace().daily() ? "daily" : "interval";
            case "sweep_active" -> String.valueOf(plugin.sweeper().active());
            case "sweep_last_removed" -> String.valueOf(plugin.sweeper().lastTotal());
            case "sweep_last_collected" -> String.valueOf(plugin.sweeper().lastCollected());
            case "sweep_last_duration" -> String.format(java.util.Locale.ROOT, "%.2f", plugin.sweeper().lastDurationMillis() / 1000.0);
            case "trash_count" -> String.valueOf(plugin.trash().itemCount());
            case "trash_stacks" -> String.valueOf(plugin.trash().size());
            case "trash_capacity" -> String.valueOf(net.voidclear.v2.store.TrashStore.CAPACITY);
            case "trash_free_slots" -> String.valueOf(net.voidclear.v2.store.TrashStore.CAPACITY - plugin.trash().size());
            case "trash_clear_enabled" -> String.valueOf(plugin.settings().pace().trashClearEnabled());
            case "trash_clear_mode" -> plugin.settings().pace().trashClearDaily() ? "daily" : "interval";
            case "trash_clear_seconds" -> String.valueOf(plugin.settings().pace().trashClearEnabled() ? plugin.trashTimer().secondsLeft() : 0);
            case "trash_clear_time" -> plugin.settings().pace().trashClearEnabled()
                    ? formatTime(plugin.trashTimer().secondsLeft()) : "未开启";
            case "trash_clear_at" -> formatDeadline(plugin.settings().pace().trashClearEnabled() && plugin.trashTimer().running()
                    ? plugin.trashTimer().deadline() : 0);
            default -> null;
        };
    }

    static String formatDeadline(long deadline) {
        return deadline <= 0 ? "未开启" : java.time.Instant.ofEpochMilli(deadline)
                .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public static String formatTime(int seconds) {
        int value = Math.max(0, seconds);
        return value / 60 + "分" + String.format(java.util.Locale.ROOT, "%02d", value % 60) + "秒";
    }
}
