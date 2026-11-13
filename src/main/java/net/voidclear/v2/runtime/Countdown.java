package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Sounds;
import net.voidclear.v2.config.Values;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一条倒计时：播报、Boss 条、响铃，归零时执行动作。
 *
 * <p>清扫倒计时每秒调一次 {@link #tick(String)}。按毫秒截止时间算，
 * 不用服务器时区；归零那一拍先执行动作，再返回给调用方决定是否续下一条
 * （自动式和「马上执行」的区别就在这里）。</p>
 */
public final class Countdown {

    private static final long SECOND_MILLIS = 1000L;

    private final Runnable action;
    private final org.bukkit.plugin.Plugin plugin;

    private volatile long deadline;
    private long total;
    private volatile boolean running;
    private int announced = -1;

    private BossBar bar;
    private boolean barEnabled;
    private int barShowBefore = 60;
    private String barColor = "RED";
    private String barTitle = "";

    private volatile boolean actionBarEnabled;
    private String actionBarTitle = "";
    private volatile long actionBarVersion;
    private final Map<Player, Long> actionBarViewers = new ConcurrentHashMap<>();

    private List<Integer> marks = List.of();
    private List<Integer> soundMarks = List.of();
    private int soundTail;
    private String tickKey = "block.note_block.pling";
    private String finishKey = "block.note_block.bell";
    private float volume = 1f;
    private float pitch = 1f;

    public Countdown(org.bukkit.plugin.Plugin plugin, Runnable action) {
        this.plugin = plugin;
        this.action = action;
    }

    /* ------------------------------------------------------------------ */

    public void begin(long seconds) {
        beginAt(System.currentTimeMillis() + Math.max(1L, seconds) * SECOND_MILLIS, Math.max(1L, seconds));
    }

    /** 恢复一条跑了一半的倒计时（截止时间戳来自上次关服）。 */
    public void beginAt(long deadlineMillis, long totalSeconds) {
        stop();
        this.total = Math.max(1L, totalSeconds) * SECOND_MILLIS;
        this.deadline = deadlineMillis;
        this.running = true;
    }

    public boolean running() {
        return running;
    }

    public long deadline() {
        return deadline;
    }

    public int secondsLeft() {
        return running ? (int) Math.max(0, Math.ceil((deadline - System.currentTimeMillis()) / (double) SECOND_MILLIS)) : 0;
    }

    public void stop() {
        running = false;
        announced = -1;
        hideBar();
        hideActionBar();
    }

    public String leftText() {
        if (!running) {
            return "未开始";
        }
        int left = secondsLeft();
        if (left >= 3600) {
            return (left / 3600) + " 时 " + (left % 3600) / 60 + " 分";
        }
        if (left >= 60) {
            return (left / 60) + " 分 " + (left % 60) + " 秒";
        }
        return left + " 秒";
    }

    /** 最近一次配置里的播报秒数（{@code /vc status} 展示用）。 */
    public List<Integer> marks() {
        return marks;
    }

    /** 配置重载后刷新外观；正在跑的倒计时不被打断。 */
    public void look(boolean enabled, String color, String title) {
        this.barEnabled = enabled;
        this.barColor = color == null ? "RED" : color;
        this.barTitle = title == null ? "" : title;
        if (!enabled) {
            hideBar();
        } else if (bar != null) {
            bar.setColor(color());
        }
    }

    public void showBefore(int seconds) {
        barShowBefore = Math.max(0, seconds);
        if (!visibleAt(secondsLeft(), barShowBefore)) hideBar();
        if (!visibleAt(secondsLeft(), barShowBefore)) hideActionBar();
        announced = -1;
    }

    /** 与 Boss 条共用显示阈值，但开关独立；仅清扫倒计时接入。 */
    public void actionBar(boolean enabled, String title) {
        hideActionBar();
        actionBarEnabled = enabled;
        actionBarTitle = title == null ? "" : title;
        announced = -1;
    }

    static boolean visibleAt(int left, int before) {
        return left > 0 && (before == 0 || left <= before);
    }

    public void announce(List<Integer> marks, List<Integer> soundMarks, int soundTail) {
        this.marks = marks == null ? List.of() : marks;
        this.soundMarks = soundMarks == null ? List.of() : soundMarks;
        this.soundTail = Math.max(0, soundTail);
    }

    public void sound(String tickKey, String finishKey, float volume, float pitch) {
        this.tickKey = tickKey == null ? "" : tickKey;
        this.finishKey = finishKey == null ? "" : finishKey;
        this.volume = volume;
        this.pitch = pitch;
    }

    /* ------------------------------------------------------------------ */

    /**
     * 每秒调一次。
     *
     * @param tickMessage 带 {@code %TIME%} 的播报句
     * @return {@code true} 表示这一拍归零，动作已执行
     */
    public boolean tick(String tickMessage) {
        if (!running) {
            return false;
        }
        long leftMillis = deadline - System.currentTimeMillis();
        if (leftMillis <= 0) {
            running = false;
            hideBar();
            hideActionBar();
            action.run();
            return true;
        }
        int left = (int) Math.ceil(leftMillis / (double) SECOND_MILLIS);
        if (left == announced) {
            return false;
        }
        announced = left;
        if (marks.contains(left)) {
            broadcast(tickMessage.replace("%TIME%", String.valueOf(left)));
        }
        if (soundMarks.contains(left) || (soundTail > 0 && left <= soundTail)) {
            ring(tickKey);
        }
        if (barEnabled && visibleAt(left, barShowBefore)) {
            paintBar(left);
        } else {
            hideBar();
        }
        if (actionBarEnabled && visibleAt(left, barShowBefore) && !actionBarTitle.isBlank()) {
            paintActionBar(left);
        } else {
            hideActionBar();
        }
        return false;
    }

    /** 全局播一句（已着色的成品句）。 */
    public void broadcast(String text) {
        Component notice = ChatNotice.of(text);
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.getScheduler().run(plugin, scheduled -> {
                    if (player.isOnline()) player.sendMessage(notice);
                }, null);
            } catch (RuntimeException gone) {
                // 玩家下线或插件停用时不再发送。
            }
        }
    }

    /** 收尾音效。 */
    public void finish() {
        ring(finishKey);
    }

    /** 调一个原版音效 id；不认识的 id 当作自定义音效路径播放。 */
    public void ring(String key) {
        Sounds.play(plugin, key, volume, pitch);
    }

    /* ------------------------------------------------------------------ */

    private void paintActionBar(int left) {
        Component text = LegacyComponentSerializer.legacySection().deserialize(
                Values.color(actionBarTitle.replace("%TIME%", String.valueOf(left))));
        long version = actionBarVersion;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        actionBarViewers.keySet().retainAll(online);
        for (Player player : online) {
            try {
                player.getScheduler().run(plugin, scheduled -> {
                    if (player.isOnline() && running && actionBarEnabled && version == actionBarVersion) {
                        player.sendActionBar(text);
                        actionBarViewers.put(player, version);
                    }
                }, null);
            } catch (RuntimeException gone) {
                // 玩家下线或插件停用时丢弃本次提示，不影响其他玩家。
            }
        }
    }

    private void hideActionBar() {
        ++actionBarVersion; // 已排队但尚未发送的旧提示不能在停止后复活。
        for (var entry : actionBarViewers.entrySet()) {
            Player player = entry.getKey();
            long version = entry.getValue();
            try {
                player.getScheduler().run(plugin, scheduled -> {
                    if (actionBarViewers.remove(player, version) && player.isOnline()) {
                        // 只清理本倒计时发过的提示一次，不每秒清空其他插件的 ActionBar。
                        player.sendActionBar(Component.empty());
                    }
                }, null);
            } catch (RuntimeException gone) {
                actionBarViewers.remove(player, version);
            }
        }
    }

    private void paintBar(int left) {
        if (bar == null) {
            bar = Bukkit.createBossBar(Values.color(barTitle), color(), BarStyle.SOLID);
        }
        bar.setColor(color());
        bar.setTitle(Values.color(barTitle.replace("%TIME%", String.valueOf(left))));
        long displayTotal = barShowBefore == 0 ? total : Math.min(total, barShowBefore * SECOND_MILLIS);
        bar.setProgress(Math.max(0, Math.min(1, (deadline - System.currentTimeMillis()) / (double) displayTotal)));
        bar.setVisible(true);
        List<Player> want = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player player : bar.getPlayers()) {
            if (!want.contains(player)) {
                bar.removePlayer(player);
            }
        }
        for (Player player : want) {
            bar.addPlayer(player);
        }
    }

    private void hideBar() {
        if (bar != null) {
            bar.setVisible(false);
            bar.removeAll();
        }
    }

    private BarColor color() {
        try {
            return BarColor.valueOf(barColor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return BarColor.RED;
        }
    }
}
