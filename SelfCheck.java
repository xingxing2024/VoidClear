import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/**
 * 不碰服务端的逻辑自检：配置解析、时间解析、颜色码。
 *
 * <p>用法（在仓库根目录，classpath 用 Maven 的 compile classpath）：</p>
 * <pre>
 * javac -encoding UTF-8 -d out -cp "&lt;cp&gt;" SelfCheck.java
 * java -ea -cp "&lt;cp&gt;;out" SelfCheck
 * </pre>
 */
public class SelfCheck {

    private static int fails = 0;

    public static void main(String[] args) throws Exception {
        YamlConfiguration cfg = load("config.yml");
        YamlConfiguration msg = load("messages.yml");
        YamlConfiguration descriptor = load("plugin.yml");
        check("展示名为虚空清理", msg.getString("prefix", "").contains("虚空清理"));
        check("仅注册当前命令别名", descriptor.getStringList("commands.voidclear.aliases").equals(List.of("vc")));
        check("仅注册当前管理权限", descriptor.getConfigurationSection("permissions").getKeys(false).equals(java.util.Set.of("voidclear"))
                && descriptor.isConfigurationSection("permissions.voidclear.admin"));

        report("config.yml 可解析", cfg.getKeys(true).size() > 10, String.valueOf(cfg.getKeys(true).size()));
        report("messages.yml 颜色码还在", msg.getString("general.no-permission", "").startsWith("&c"),
                msg.getString("general.no-permission"));
        report("旧版回收箱配置已移除", !cfg.contains("stash"), "");

        check("4:00", valid("4:00"));
        check("04:00", valid("04:00"));
        check("25:99 应判非法", !valid("25:99"));
        check("nextTime 返回未来某一刻", nextSafe("04:00") > System.currentTimeMillis());
        check("坏时刻返回 -1", nextSafe("xx:yy") == -1);
        check("hitsMinute 命中", hits("04:00", at(4, 0)));
        check("hitsMinute 不命中", !hits("04:00", at(5, 0)));

        check("&a 转成 §a", "\u00a7a".equals(color("&a")));
        check("已是 §a 时原样", "\u00a7a红".equals(color("\u00a7a红")));
        check("null 安全", "".equals(color(null)));

        report("messages.yml 无旧版回收箱文案", !msg.contains("stash"), "");

        check("倒计时：间隔模式取周期", net.voidclear.v2.runtime.Timing.secondsUntil(3600, false, List.of(), 0L,
                java.time.ZoneId.systemDefault()) == 3600);
        check("倒计时：每日模式落在未来", net.voidclear.v2.runtime.Timing.secondsUntil(60, true, List.of("04:00"),
                System.currentTimeMillis(), java.time.ZoneId.systemDefault()) > 0);
        var utc = java.time.ZoneId.of("UTC");
        long now = java.time.Instant.parse("2026-11-06T10:00:00Z").toEpochMilli();
        check("每日模式选择最近时刻且不使用间隔", net.voidclear.v2.runtime.Timing.secondsUntil(7, true,
                List.of("16:00", "11:30"), now, utc) == 5400);
        check("每日模式跨日", net.voidclear.v2.runtime.Timing.secondsUntil(7, true,
                List.of("04:00"), now, utc) == 64800);
        check("每日模式已触发时刻不重复", net.voidclear.v2.runtime.Timing.secondsUntil(7, true,
                List.of("10:00"), now, utc) == 86400);
        boolean neverEarly = true, alwaysTomorrow = true;
        long target = now + 60_000;
        for (int millis = 0; millis < 1000; millis++) {
            long current = now + millis;
            long scheduled = current + net.voidclear.v2.runtime.Timing.secondsUntil(7, true,
                    List.of("10:01"), current, utc) * 1000;
            neverEarly &= scheduled >= target && scheduled < target + 1000;
            alwaysTomorrow &= net.voidclear.v2.runtime.Timing.secondsUntil(7, true,
                    List.of("10:01"), scheduled, utc) > 23 * 3600;
        }
        check("每日模式每个毫秒偏移都不得提前触发", neverEarly);
        check("每日模式触发后每个毫秒偏移都续到次日", alwaysTomorrow);

        System.out.println(fails == 0 ? "SelfCheck: ALL OK" : "SelfCheck: " + fails + " FAILED");
        if (fails != 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        report(name, ok, "");
    }

    private static void report(String name, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + name + (detail.isEmpty() ? "" : " | " + detail));
        if (!ok) {
            fails++;
        }
    }

    private static YamlConfiguration load(String name) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (var in = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("src", "main", "resources", name))) {
            c.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return c;
    }

    private static boolean valid(String raw) {
        return net.voidclear.v2.config.Values.parseTime(raw) != null;
    }

    private static long nextSafe(String raw) {
        return net.voidclear.v2.config.Values.nextTime(List.of(raw),
                System.currentTimeMillis(), java.time.ZoneId.systemDefault());
    }

    private static boolean hits(String raw, long millis) {
        return net.voidclear.v2.config.Values.hitsMinute(List.of(raw), millis);
    }

    private static String color(String raw) {
        return net.voidclear.v2.config.Values.color(raw);
    }

    /** 今天（本地时区）的某个时刻。 */
    private static long at(int hour, int minute) {
        return java.time.LocalDate.now().atTime(hour, minute)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
