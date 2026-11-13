package net.voidclear.v2.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * {@code config.yml} 的类型化视图。
 *
 * <p>子类各管一块：清扫节奏、判定规则、照料。键名集中在这里，
 * 换键名只改一个文件；{@code /vc reload} 重新 {@code load()} 即可。</p>
 */
public final class Sections {

    private Sections() {
    }

    /** 抽象基类：持有 {@link Settings}，只做读+兜底。 */
    public abstract static class Part {
        protected final Settings settings;

        protected Part(Settings settings) {
            this.settings = settings;
        }
    }

    /* ================================================================== */
    /* 节奏与清扫                                                          */
    /* ================================================================== */

    public static final class Pace extends Part {

        public Pace(Settings settings) {
            super(settings);
        }

        /** 两轮清扫之间的间隔（秒）。 */
        public int period() {
            return Math.max(1, settings.rawInt("timer.period", 3600));
        }

        public boolean daily() {
            return "daily".equalsIgnoreCase(settings.raw("timer.mode", "interval"));
        }

        public List<String> dailyTimes() {
            return settings.rawList("timer.times", List.of("04:00", "16:00"));
        }

        public boolean trashClearEnabled() {
            return settings.rawBool("trash.clear.enable", false);
        }

        public int trashClearPeriod() {
            return Math.max(1, settings.rawInt("trash.clear.period", 7200));
        }

        public boolean trashClearDaily() {
            return "daily".equalsIgnoreCase(settings.raw("trash.clear.mode", "interval"));
        }

        public List<String> trashClearTimes() {
            return settings.rawList("trash.clear.times", List.of("04:00", "16:00"));
        }

        public List<Integer> trashClearMarks() {
            return settings.rawInts("trash.clear.marks", List.of(30, 10, 5));
        }

        /** 一批派发多少个区块。 */
        public int batch() {
            return Math.max(1, settings.rawInt("sweep.batch", 600));
        }

        /** 区块里待清的东西少于这个数就整块跳过；0 = 关闭。 */
        public int floor() {
            return Math.max(0, settings.rawInt("sweep.floor", 0));
        }

        /** 倒计时播报秒数，升序。 */
        public List<Integer> marks() {
            return settings.rawInts("sweep.marks", List.of(60, 30, 15, 5));
        }

        /** 最后几秒每秒都响；0 = 关闭。 */
        public int soundTail() {
            return Math.max(0, settings.rawInt("sound.tail", 5));
        }

        public List<Integer> soundMarks() {
            return settings.rawInts("sound.marks", List.of());
        }

        public String soundKey() {
            return settings.raw("sound.key", "block.note_block.pling");
        }

        public String finishSoundKey() {
            return settings.raw("sound.finish-key", "block.note_block.bell");
        }

        public float volume() {
            return (float) settings.rawDouble("sound.volume", 1.0);
        }

        public float pitch() {
            return (float) settings.rawDouble("sound.pitch", 1.0);
        }

        /* --- 两条独立的 Boss 条 --- */

        public boolean sweepBar() {
            return settings.rawBool("bar.sweep.enable", true);
        }

        public String sweepBarColor() {
            return settings.raw("bar.sweep.color", "BLUE");
        }

        public String sweepBarTitle() {
            return settings.hint("bar.sweep.title", "&b清扫倒计时 &8· &f%TIME% &7秒");
        }

        public int sweepBarShowBefore() { return Math.max(0, settings.rawInt("bar.sweep.show-before", 60)); }

        public boolean sweepActionBar() {
            return settings.rawBool("actionbar.sweep.enable", true);
        }

        public String sweepActionBarTitle() {
            return settings.hint("actionbar.sweep.title", "&b清扫倒计时 &8· &f%TIME% &7秒");
        }

    }

    /* ================================================================== */
    /* 判定规则                                                            */
    /* ================================================================== */

    public static final class Policy extends Part {

        public Policy(Settings settings) {
            super(settings);
        }

        public boolean vanillaOnly() {
            return settings.rawBool("rule.vanilla-only", true);
        }

        public boolean keepNamed() {
            return settings.rawBool("rule.named-kept", false);
        }

        public List<String> keep() {
            return settings.rawListSet("rule.keep", List.of("animals"));
        }

        public List<String> whitelist() {
            return settings.rawListSet("rule.whitelist", List.of());
        }

        public List<String> animalWhitelist() {
            return settings.rawListSet("rule.animal-whitelist", List.of());
        }

        public List<String> monsterWhitelist() {
            return settings.rawListSet("rule.monster-whitelist", List.of());
        }

        public List<String> animalKeep() {
            return settings.rawListSet("rule.animal-keep", List.of("animals"));
        }

        public List<String> monsterKeep() {
            return settings.rawListSet("rule.monster-keep", List.of());
        }

        public List<String> animalSweep() {
            return settings.rawListSet("rule.animal-sweep", List.of());
        }

        public List<String> monsterSweep() {
            return settings.rawListSet("rule.monster-sweep", List.of("monster"));
        }

        public List<String> sweep() {
            return settings.rawListSet("rule.sweep", List.of("monster"));
        }

        /** 某个世界的规则小节；没有则返回 null（走全局名单）。 */
        public ConfigurationSection world(String name) {
            return worldSection(name);
        }

        public ConfigurationSection drops() {
            return settings.rawSection("drop");
        }

        public ConfigurationSection worldDrops(String name) {
            ConfigurationSection section = worldSection(name);
            return section == null ? null : section.getConfigurationSection("drop");
        }

        /** {@code rule.world} 下按世界名取；名字里的点要当字符看，不能当路径分隔。 */
        private ConfigurationSection worldSection(String name) {
            ConfigurationSection root = settings.rawSection("rule.world");
            if (root == null) {
                return null;
            }
            // get() 照样按 '.' 切路径：先直接查原始键值（getValues(false) 不拆点），再退回 getConfigurationSection
            Object raw = root.getValues(false).get(name);
            if (raw == null) {
                raw = root.get(name);
            }
            return raw instanceof ConfigurationSection section ? section : null;
        }

        public boolean creaturesByDefault() {
            return settings.rawBool("world.creatures", true);
        }

        public boolean dropsByDefault() {
            return settings.rawBool("world.drops", true);
        }

        /** 这个世界扫不扫生物 / 收不收掉落物：世界例外优先，其次全局默认。 */
        public boolean creatures(String world) {
            ConfigurationSection section = world(world);
            return section == null ? creaturesByDefault() : section.getBoolean("creatures", creaturesByDefault());
        }

        public boolean animals(String world) {
            ConfigurationSection section = world(world);
            return creatures(world) && (section == null ? settings.rawBool("world.animals", true)
                    : section.getBoolean("animals", settings.rawBool("world.animals", true)));
        }

        public boolean monsters(String world) {
            ConfigurationSection section = world(world);
            return creatures(world) && (section == null ? settings.rawBool("world.monsters", true)
                    : section.getBoolean("monsters", settings.rawBool("world.monsters", true)));
        }

        public boolean experience(String world) {
            ConfigurationSection section = world(world);
            return creatures(world) && (section == null ? settings.rawBool("world.experience-orbs", true)
                    : section.getBoolean("experience-orbs", settings.rawBool("world.experience-orbs", true)));
        }

        public boolean drops(String world) {
            ConfigurationSection section = world(world);
            return section == null ? dropsByDefault() : section.getBoolean("drops", dropsByDefault());
        }

        /** 世界小节的掉落物豁免；没写就退回全局 drop。 */
        public ConfigurationSection dropsOf(String world) {
            ConfigurationSection section = worldDrops(world);
            return section == null ? drops() : section;
        }

    }

    /* ================================================================== */
    /* 照料                                                                */
    /* ================================================================== */

    public static final class Care extends Part {

        public Care(Settings settings) {
            super(settings);
        }

        public boolean playerTrample() {
            return settings.rawBool("trample.players", true);
        }

        public boolean mobTrample() {
            return settings.rawBool("trample.mobs", true);
        }

        /** 只在这几个世界生效；列表为空 = 全服。 */
        public List<String> trampleWorlds() {
            return settings.rawList("trample.worlds", List.of());
        }

        public List<String> blastBlocked() {
            return settings.rawList("blast.blocked", List.of("minecraft:creeper"));
        }
    }
}
