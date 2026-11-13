package net.voidclear.v2.config;

import org.bukkit.configuration.InvalidConfigurationException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 配置加载不得改写已有文件；两份解析成功后才能写入缺失文件。 */
public final class MigrationCheck {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory(Path.of("target"), "config-check-");
        String config = "# custom\nbar:\n  sweep:\n    color: RED\nrule:\n  keep: []\n";
        String messages = "prefix: '&b虚空清理'\n";
        Files.writeString(folder.resolve(Settings.CONFIG), config);
        Files.writeString(folder.resolve(Settings.MESSAGES), messages);
        var loaded = load(folder);
        assert loaded.config().getString("bar.sweep.color").equals("RED");
        assert loaded.config().getStringList("rule.keep").isEmpty();
        assert Files.readString(folder.resolve(Settings.CONFIG)).equals(config);
        assert Files.readString(folder.resolve(Settings.MESSAGES)).equals(messages);
        load(folder);
        assert Files.readString(folder.resolve(Settings.CONFIG)).equals(config);

        Path broken = Files.createTempDirectory(Path.of("target"), "config-broken-");
        Files.writeString(broken.resolve(Settings.MESSAGES), "help: [\n");
        try { load(broken); throw new AssertionError("Invalid YAML accepted"); }
        catch (InvalidConfigurationException expected) { }
        assert !Files.exists(broken.resolve(Settings.CONFIG));
        assert Files.readString(broken.resolve(Settings.MESSAGES)).equals("help: [\n");

        Path fresh = Files.createTempDirectory(Path.of("target"), "config-fresh-");
        var installed = load(fresh);
        assert Files.exists(fresh.resolve(Settings.CONFIG));
        assert Files.exists(fresh.resolve(Settings.MESSAGES));
        assert installed.messages().getString("prefix").contains("虚空清理");
        assert installed.config().getBoolean("actionbar.sweep.enable");
        for (String schedule : new String[]{"mode: invalid", "mode: daily\n    times: []",
                "mode: daily\n    times: ['25:99']"}) {
            Path invalid = Files.createTempDirectory(Path.of("target"), "schedule-invalid-");
            String original = "trash:\n  clear:\n    " + schedule + "\n";
            Files.writeString(invalid.resolve(Settings.CONFIG), original);
            try { load(invalid); throw new AssertionError("Invalid schedule accepted: " + schedule); }
            catch (InvalidConfigurationException expected) { }
            assert Files.readString(invalid.resolve(Settings.CONFIG)).equals(original);
            assert !Files.exists(invalid.resolve(Settings.MESSAGES));
        }
        Files.writeString(folder.resolve(Settings.CONFIG), "trash:\n  clear:\n    mode: daily\n    times: ['08:00', '20:00']\n");
        assert load(folder).config().getStringList("trash.clear.times").equals(java.util.List.of("08:00", "20:00"));
        for (String schedule : new String[]{"mode: invalid", "mode: daily\n  times: []",
                "mode: daily\n  times: ['25:99']"}) {
            String original = "timer:\n  " + schedule + "\n";
            Files.writeString(folder.resolve(Settings.CONFIG), original);
            try { load(folder); throw new AssertionError("Invalid sweep schedule accepted"); }
            catch (InvalidConfigurationException expected) { }
            assert Files.readString(folder.resolve(Settings.CONFIG)).equals(original);
        }
        Files.writeString(folder.resolve(Settings.CONFIG), "timer:\n  mode: daily\n  times: ['08:00', '20:00']\n");
        assert load(folder).config().getStringList("timer.times").size() == 2;
        presentationUpgrade();
        System.out.println("MigrationCheck: ALL OK");
    }

    private static void presentationUpgrade() throws Exception {
        Path folder = Files.createTempDirectory(Path.of("target"), "presentation-upgrade-");
        var old = new org.bukkit.configuration.file.YamlConfiguration();
        old.set("prefix", "CUSTOM PREFIX");
        old.set("sweep.done", ConfigMigration.OLD_SWEEP);
        old.set("help.header", "&b虚空清理 &8· &7指令");
        old.set("help.lines", ConfigMigration.OLD_HELP);
        old.set("unrelated", "preserve me");
        String before = old.saveToString();
        Files.writeString(folder.resolve(Settings.CONFIG), "# keep config\n");
        Files.writeString(folder.resolve(Settings.MESSAGES), before);
        Files.writeString(folder.resolve("messages.yml.pre-voidclear.bak"), "earlier backup");
        var upgraded = load(folder);
        assert !upgraded.messages().contains("help.lines", true);
        assert upgraded.messages().getString("help.header").contains("使用指南");
        assert !upgraded.messages().getString("sweep.done").contains("/vc trash");
        assert upgraded.messages().getString("prefix").equals("CUSTOM PREFIX");
        assert upgraded.messages().getString("unrelated").equals("preserve me");
        assert Files.readString(folder.resolve("messages.yml.pre-voidclear.bak")).equals("earlier backup");
        assert Files.readString(folder.resolve("messages.yml.pre-voidclear.bak.1")).equals(before);
        assert Files.readString(folder.resolve(Settings.CONFIG)).equals("# keep config\n");
        String after = Files.readString(folder.resolve(Settings.MESSAGES));
        load(folder);
        assert Files.readString(folder.resolve(Settings.MESSAGES)).equals(after) : "Upgrade was not idempotent";
        assert !Files.exists(folder.resolve("messages.yml.pre-voidclear.bak.2"));

        for (Object help : java.util.List.of(java.util.List.of(), java.util.List.of("MY HELP"), "MY SINGLE LINE")) {
            Path custom = Files.createTempDirectory(Path.of("target"), "presentation-custom-");
            var messages = new org.bukkit.configuration.file.YamlConfiguration();
            messages.set("sweep.done", "MY SWEEP %COUNT%"); messages.set("help.header", "MY TITLE"); messages.set("help.lines", help);
            String original = messages.saveToString();
            Files.writeString(custom.resolve(Settings.MESSAGES), original);
            var loaded = load(custom);
            assert loaded.messages().contains("help.lines", true);
            assert Files.readString(custom.resolve(Settings.MESSAGES)).equals(original);
            assert !Files.exists(custom.resolve("messages.yml.pre-voidclear.bak"));
        }
        Path units = Files.createTempDirectory(Path.of("target"), "sweep-unit-upgrade-");
        var grouped = new org.bukkit.configuration.file.YamlConfiguration();
        grouped.set("sweep.done", "&7本轮清扫结束 &8· &7已处理 &f%COUNT% &7个目标，确认回收 &f%COLLECTED% &7组掉落物 &8· &7%SECONDS%秒");
        grouped.set("sweep.incomplete", "&e本轮清扫未完整执行 &8· &7已处理 &f%COUNT% &7个目标，确认回收 &f%COLLECTED% &7组 &8· &7失败任务 &f%FAILED% &8· &7%SECONDS%秒；请查看服务端日志");
        String groupedBefore = grouped.saveToString();
        Files.writeString(units.resolve(Settings.MESSAGES), groupedBefore);
        var quantities = load(units);
        assert quantities.messages().getString("sweep.done").contains("%ITEMS%");
        assert quantities.messages().getString("sweep.incomplete").contains("%ITEMS%");
        assert Files.readString(units.resolve("messages.yml.pre-voidclear.bak")).equals(groupedBefore);
        String quantityMessages = Files.readString(units.resolve(Settings.MESSAGES));
        load(units);
        assert Files.readString(units.resolve(Settings.MESSAGES)).equals(quantityMessages);

        Path invalid = Files.createTempDirectory(Path.of("target"), "presentation-invalid-");
        Files.writeString(invalid.resolve(Settings.CONFIG), "timer:\n  mode: invalid\n");
        Files.writeString(invalid.resolve(Settings.MESSAGES), before);
        try { load(invalid); throw new AssertionError("Upgrade accepted invalid configuration"); }
        catch (InvalidConfigurationException expected) { }
        assert Files.readString(invalid.resolve(Settings.MESSAGES)).equals(before);
        assert !Files.exists(invalid.resolve("messages.yml.pre-voidclear.bak"));
    }

    private static ConfigMigration.Snapshot load(Path folder) throws Exception {
        return ConfigMigration.load(folder, name -> {
            try { return Files.newInputStream(Path.of("src/main/resources").resolve(name)); }
            catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        });
    }
}
