package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Values;

import java.time.ZoneId;
import java.util.List;

/**
 * 倒计时的时间算法（纯函数，不碰世界对象）。
 *
 * <p>按毫秒算，不用服务器时区；每日模式取「下一个 HH:mm」，关机期间错过的时刻直接跳过。</p>
 */
public final class Timing {

    private Timing() {
    }

    /**
     * 下一次触发距离现在还有多少秒。
     *
     * @param periodSec 间隔模式的周期
     * @param daily     true = 每天在 times 列出的时刻
     * @param times     {@code "04:00"} 之类
     * @param now       现在（毫秒）
     * @param zone      时区
     */
    public static long secondsUntil(int periodSec, boolean daily, List<String> times, long now, ZoneId zone) {
        if (!daily) {
            return Math.max(1, periodSec);
        }
        long next = Values.nextTime(times, now, zone);
        if (next <= now) {
            return Math.max(1, periodSec);
        }
        // Rounding down may fire before the daily instant and schedule that same instant again.
        return Math.max(1, (next - now + 999L) / 1000L);
    }
}
