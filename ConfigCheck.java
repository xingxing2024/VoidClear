import net.voidclear.v2.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/**
 * 配置读取自检：显式空列表、缺键先吃 jar 默认、jar 也没有才吃代码兜底、单字符串 help.header。
 *
 * <p>用法：{@code java -ea -cp "<cp>;<classes>" ConfigCheck}</p>
 */
public final class ConfigCheck {

    private static int fails = 0;

    public static void main(String[] args) {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("rule.keep", List.of("animals"));
        defaults.set("rule.whitelist", List.of());
        defaults.set("sweep.marks", List.of(60, 30, 15, 5));
        defaults.set("sound.marks", List.of());
        defaults.set("trample.worlds", List.of());

        // 1. 显式空列表：用户写了 [] 就是 []，不能被兜底顶掉
        Settings empty = blank();
        empty.install(withDefaults(defaults, c -> c.set("rule.keep", List.of())));
        check("显式空名单保持空", empty.policy().keep().isEmpty(), empty.policy().keep());
        Settings noMarks = blank();
        noMarks.install(withDefaults(defaults, c -> c.set("sweep.marks", List.of())));
        check("显式空 marks 保持空（不倒计时播报）", noMarks.pace().marks().isEmpty(), noMarks.pace().marks());

        // 2. 缺键：先吃 jar 默认
        Settings missing = blank();
        missing.install(withDefaults(defaults, c -> c.set("rule.sweep", List.of("monster"))));
        check("缺键吃 jar 默认名单", missing.policy().keep().equals(List.of("animals")), missing.policy().keep());
        check("缺键时白名单默认为空", missing.policy().whitelist().isEmpty(), missing.policy().whitelist());
        check("缺键吃 jar 默认整数表", missing.pace().marks().equals(List.of(5, 15, 30, 60)), missing.pace().marks());
        check("jar 默认是空表就保持空", missing.pace().soundMarks().isEmpty(), missing.pace().soundMarks());

        // 3. jar 里也没有：才用代码兜底
        Settings fallback = blank();
        check("floor 缺键默认关闭门槛", fallback.pace().floor() == 0, fallback.pace().floor());
        fallback.install(withDefaults(defaults, c -> {
        }));
        check("jar 也没有用代码兜底", fallback.pace().floor() == 0, fallback.pace().floor());

        Settings bin = blank();
        check("升级不自动开启垃圾箱清空", !bin.pace().trashClearEnabled(), "");
        YamlConfiguration binConfig = new YamlConfiguration();
        binConfig.set("timer.period", 123);
        binConfig.set("sweep.marks", List.of(12));
        binConfig.set("trash.clear.enable", true);
        binConfig.set("trash.clear.period", 7);
        binConfig.set("trash.clear.marks", List.of(3, 1));
        bin.install(binConfig);
        check("垃圾箱清空开关独立", bin.pace().trashClearEnabled(), "");
        check("垃圾箱与清扫周期独立", bin.pace().trashClearPeriod() == 7 && bin.pace().period() == 123, "");
        check("垃圾箱与清扫播报独立", bin.pace().trashClearMarks().equals(List.of(1, 3)) && bin.pace().marks().equals(List.of(12)), "");
        binConfig.set("trash.clear.period", 0);
        check("垃圾箱周期至少一秒", bin.pace().trashClearPeriod() == 1, "");
        binConfig.set("trash.clear.marks", List.of());
        check("垃圾箱显式空播报保持空", bin.pace().trashClearMarks().isEmpty(), "");
        check("垃圾箱默认间隔模式", !bin.pace().trashClearDaily(), "");
        binConfig.set("trash.clear.mode", "daily");
        binConfig.set("trash.clear.times", List.of("08:00", "20:00"));
        check("垃圾箱支持指定时刻模式", bin.pace().trashClearDaily(), "");
        check("垃圾箱时刻与普通清扫独立", bin.pace().trashClearTimes().equals(List.of("08:00", "20:00"))
                && !bin.pace().daily(), "");

        // 4. messages.yml 的 help.header 写成单字符串也要能读
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("help.header", "&8&m---- &r&b虚空清理 &8&m----");
        check("单字符串 help.header 收成一行",
                blankWith(messages).msgLines("help.header", List.of("x")).equals(List.of("&8&m---- &r&b虚空清理 &8&m----")),
                blankWith(messages).msgLines("help.header", List.of("x")));
        messages.set("help.header", List.of());
        check("显式空 help.header 不回落到兜底", blankWith(messages).msgLines("help.header", List.of("x")).isEmpty(), "");

        Settings rules = blank();
        YamlConfiguration config = new YamlConfiguration();
        config.set("drop.enable", true);
        config.set("rule.sweep", List.of("minecraft:*"));
        config.set("rule.keep", List.of());
        rules.install(config);
        net.voidclear.v2.rule.Decide decide = new net.voidclear.v2.rule.Decide(rules.policy());
        check("查询玩家始终保留", !decide.verdict("world", "minecraft:player", false, false).remove(), "");
        check("末影龙始终保留", !decide.verdict("world", "minecraft:ender_dragon", true, false).remove(),
                decide.verdict("world", "minecraft:ender_dragon", true, false).reason());
        check("凋灵始终保留", !decide.verdict("world", "minecraft:wither", true, false).remove(),
                decide.verdict("world", "minecraft:wither", true, false).reason());
        check("查询经验球跟随生物开关", decide.verdict("world", "minecraft:experience_orb", false, false).remove(), "");
        config.set("world.creatures", false);
        check("掉落物查询明确提示需要物品数据", decide.verdict("world", "minecraft:item", false, false).reason().contains("无法判定"), "");
        check("关闭生物保留经验球", !decide.verdict("world", "minecraft:experience_orb", false, false).remove(), "");
        config.set("world.drops", false);
        check("关闭掉落物则查询保留", !decide.verdict("world", "minecraft:item", false, false).remove(), "");
        config.set("world.creatures", true);
        config.set("world.animals", false);
        check("动物总开关开启时仍可单独关闭动物", !decide.verdict("world", "minecraft:cow", false, true).remove(),
                decide.verdict("world", "minecraft:cow", false, true).reason());
        config.set("world.animals", true);
        config.set("world.monsters", false);
        check("怪物可单独关闭", !decide.verdict("world", "minecraft:zombie", true, false).remove(),
                decide.verdict("world", "minecraft:zombie", true, false).reason());
        config.set("world.monsters", true);
        config.set("world.experience-orbs", false);
        check("经验球可单独关闭", !decide.verdict("world", "minecraft:experience_orb", false, false).remove(),
                decide.verdict("world", "minecraft:experience_orb", false, false).reason());
        config.set("world.experience-orbs", true);
        config.set("rule.whitelist", List.of("minecraft:creeper"));
        check("白名单覆盖 sweep 规则", !decide.verdict("world", "minecraft:creeper", true, false).remove(),
                decide.verdict("world", "minecraft:creeper", true, false).reason());
        config.set("rule.whitelist", List.of("monster"));
        check("分类白名单覆盖 sweep 规则", !decide.verdict("world", "minecraft:zombie", true, false).remove(),
                decide.verdict("world", "minecraft:zombie", true, false).reason());
        config.set("rule.whitelist", List.of());
        config.set("rule.keep", List.of());
        config.set("rule.animal-whitelist", List.of("minecraft:cow"));
        config.set("rule.monster-whitelist", List.of("minecraft:creeper"));
        config.set("rule.sweep", List.of("minecraft:*"));
        config.set("rule.keep", List.of());
        config.set("rule.animal-keep", List.of());
        rules.install(config);
        decide = new net.voidclear.v2.rule.Decide(rules.policy());
        var categoryDecide = decide;
        check("动物白名单保留动物", !categoryDecide.verdict("world", "minecraft:cow", false, true).remove(),
                categoryDecide.verdict("world", "minecraft:cow", false, true).reason());
        check("动物白名单不保留怪物", categoryDecide.verdict("world", "minecraft:zombie", true, false).remove(),
                categoryDecide.verdict("world", "minecraft:zombie", true, false).reason());
        check("怪物白名单保留怪物", !categoryDecide.verdict("world", "minecraft:creeper", true, false).remove(),
                categoryDecide.verdict("world", "minecraft:creeper", true, false).reason());
        check("怪物白名单不保留动物", categoryDecide.verdict("world", "minecraft:pig", false, true).remove(),
                categoryDecide.verdict("world", "minecraft:pig", false, true).reason());
        config.set("rule.monster-whitelist", List.of("*"));
        config.set("rule.sweep", List.of("monster"));
        rules.install(config);
        var wildcardDecide = new net.voidclear.v2.rule.Decide(rules.policy());
        check("* 通配保留所有怪物", !wildcardDecide.verdict("world", "minecraft:zombie", true, false).remove(),
                wildcardDecide.verdict("world", "minecraft:zombie", true, false).reason());
        check("* 通配保留所有动物", !wildcardDecide.verdict("world", "minecraft:cow", false, true).remove(),
                wildcardDecide.verdict("world", "minecraft:cow", false, true).reason());

        System.out.println(fails == 0 ? "ConfigCheck: ALL OK" : "ConfigCheck: " + fails + " FAILED");
        if (fails != 0) {
            System.exit(1);
        }
    }

    private static Settings blank() {
        return Settings.blank();
    }

    private static Settings blankWith(YamlConfiguration messages) {
        Settings settings = Settings.blank();
        settings.installMessages(messages);
        return settings;
    }

    private static YamlConfiguration withDefaults(YamlConfiguration defaults,
                                                  java.util.function.Consumer<YamlConfiguration> user) {
        YamlConfiguration config = new YamlConfiguration();
        user.accept(config);
        config.setDefaults(defaults);
        return config;
    }

    private static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + name + (ok ? "" : " | " + detail));
        if (!ok) {
            fails++;
        }
    }
}
