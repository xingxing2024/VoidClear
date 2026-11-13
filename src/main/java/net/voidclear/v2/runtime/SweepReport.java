package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Settings;
import java.util.Locale;

/** The same result for automatic broadcasts, manual commands and status. */
public final class SweepReport {
    private SweepReport() { }

    public static String result(Settings messages, int targets, int groups, long items, int failures, long millis) {
        String key, fallback;
        if (failures > 0) {
            key = "sweep.incomplete";
            fallback = "&e本轮清扫未完整执行 &8· &7已处理 &f%COUNT% &7个目标，确认回收 &f%ITEMS% &7件物品 &8· &7失败任务 &f%FAILED% &8· &7%SECONDS%秒；请查看服务端日志";
        } else if (targets == 0) {
            key = "sweep.empty";
            fallback = "&7本轮未处理目标 &8· &7已加载区块内，无符合规则且可处理的目标 &8· &7%SECONDS%秒";
        } else {
            key = "sweep.done";
            fallback = "&7本轮清扫结束 &8· &7已处理 &f%COUNT% &7个目标，确认回收 &f%ITEMS% &7件物品 &8· &7%SECONDS%秒";
        }
        String text = messages.message(key, fallback)
                .replace("%COUNT%", String.valueOf(targets))
                .replace("%COLLECTED%", String.valueOf(groups))
                .replace("%ITEMS%", String.valueOf(items))
                .replace("%FAILED%", String.valueOf(failures))
                .replace("%SECONDS%", String.format(Locale.ROOT, "%.2f", millis / 1000.0));
        if (items > 0) text += messages.hint("sweep.claim", " &8· &7本轮已入箱物品可用 &f/vc trash &7查看");
        return text;
    }
}
