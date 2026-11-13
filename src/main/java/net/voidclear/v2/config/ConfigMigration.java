package net.voidclear.v2.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 严格加载配置；仅升级可识别的旧默认展示，已有文件先备份。 */
final class ConfigMigration {
    record Snapshot(YamlConfiguration config, YamlConfiguration messages) {}
    static final String OLD_SWEEP = "&7清扫完成 &8· &f%COUNT% &7个目标，回收 &f%COLLECTED% &7组物品，用时 &f%SECONDS% &7秒。使用 &f/vc trash &7领取";
    static final List<String> OLD_HELP = List.of(
            "&f/vc &8· &7显示帮助",
            "&f/vc sweep &8· &7立即清扫",
            "&f/vc trash &8· &7打开公共垃圾箱",
            "&f/vc trash clear confirm &8· &7永久清空垃圾箱（管理员）",
            "&f/vc check <实体> [世界] &8· &7查看判定",
            "&f/vc keep [名字|id|all] &8· &7标记不清扫",
            "&f/vc unkeep [名字|id|all] &8· &7取消标记",
            "&f/vc status &8· &7查看状态",
            "&f/vc diagnose [export] &8· &7只读诊断与报告导出（管理员）",
            "&f/vc audit [条数] [type|player 条件] &8· &7查询本服存取审计（管理员）",
            "&f/vc history [条数] [type <操作>|player <玩家>] &8· &7查看操作记录（管理员）",
            "&f/vc trend [天数] &8· &7查看每日清扫趋势（管理员）",
            "&f/vc export [天数] &8· &7导出清扫统计 CSV（管理员）",
            "&f/vc reload &8· &7重载配置");

    static Snapshot load(Path folder, Function<String, InputStream> resource) throws IOException,
            InvalidConfigurationException {
        String[] names = {Settings.CONFIG, Settings.MESSAGES};
        List<YamlConfiguration> users = new ArrayList<>();
        List<YamlConfiguration> defaults = new ArrayList<>();
        Map<Path, byte[]> writes = new LinkedHashMap<>();
        // 全部严格解析后才允许任何迁移写入，坏 YAML 不得被默认值覆盖。
        for (String name : names) {
            Path path = folder.resolve(name);
            YamlConfiguration builtin = new YamlConfiguration();
            try (InputStream in = resource.apply(name)) {
                if (in == null) throw new IOException("缺少内置配置 " + name);
                builtin.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            YamlConfiguration user = new YamlConfiguration();
            user.options().parseComments(true);
            user.loadFromString(Files.exists(path) ? Files.readString(path) : builtin.saveToString());
            users.add(user);
            defaults.add(builtin);
        }
        for (int i = 0; i < names.length; i++) {
            YamlConfiguration user = users.get(i);
            Path path = folder.resolve(names[i]);
            boolean upgraded = i == 1 && upgradeMessages(user, defaults.get(i));
            String after = user.saveToString();
            if (!Files.exists(path) || upgraded) writes.put(path, after.getBytes(StandardCharsets.UTF_8));
            user.setDefaults(defaults.get(i));
        }
        validateSchedule(users.get(0), "timer");
        validateSchedule(users.get(0), "trash.clear");
        commit(writes);
        return new Snapshot(users.get(0), users.get(1));
    }

    static Snapshot edit(Path folder, Function<String,InputStream> resource, GuiSetting setting)
            throws IOException, InvalidConfigurationException {
        var configs = new ArrayList<YamlConfiguration>();
        for (String name : List.of(Settings.CONFIG,Settings.MESSAGES)) {
            var defaults = new YamlConfiguration();
            try (var in = resource.apply(name)) {
                if(in==null)throw new IOException("缺少内置配置 "+name);
                defaults.loadFromString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
            var user = new YamlConfiguration();user.options().parseComments(true);
            Path file=folder.resolve(name);
            user.loadFromString(Files.exists(file)?Files.readString(file):defaults.saveToString());
            user.setDefaults(defaults);configs.add(user);
        }
        setting.apply(configs.getFirst());
        validateSchedule(configs.getFirst(),"timer");validateSchedule(configs.getFirst(),"trash.clear");
        commit(Map.of(folder.resolve(Settings.CONFIG),configs.getFirst().saveToString().getBytes(StandardCharsets.UTF_8)));
        return new Snapshot(configs.getFirst(),configs.getLast());
    }

    private static boolean upgradeMessages(YamlConfiguration user, YamlConfiguration defaults) {
        boolean changed = false;
        for (var entry : java.util.Map.of("sweep.done", "&7本轮清扫结束 &8· &7已处理 &f%COUNT% &7个目标，确认回收 &f%COLLECTED% &7组掉落物 &8· &7%SECONDS%秒",
                "sweep.incomplete", "&e本轮清扫未完整执行 &8· &7已处理 &f%COUNT% &7个目标，确认回收 &f%COLLECTED% &7组 &8· &7失败任务 &f%FAILED% &8· &7%SECONDS%秒；请查看服务端日志").entrySet()) {
            if (entry.getValue().equals(user.getString(entry.getKey()))) {
                user.set(entry.getKey(), defaults.getString(entry.getKey()));
                changed = true;
            }
        }
        if (OLD_SWEEP.equals(user.getString("sweep.done"))) {
            user.set("sweep.done", defaults.getString("sweep.done"));
            changed = true;
        }
        if (user.isList("help.lines") && OLD_HELP.equals(user.getStringList("help.lines"))) {
            user.set("help.lines", null);
            changed = true;
        }
        if ("&b虚空清理 &8· &7指令".equals(user.getString("help.header"))) {
            user.set("help.header", defaults.getString("help.header"));
            changed = true;
        }
        return changed;
    }

    private static void validateSchedule(YamlConfiguration config, String path) throws InvalidConfigurationException {
        String mode = config.getString(path + ".mode", "interval");
        if (!mode.equalsIgnoreCase("interval") && !mode.equalsIgnoreCase("daily")) {
            throw new InvalidConfigurationException(path + ".mode 必须是 interval 或 daily");
        }
        if (mode.equalsIgnoreCase("daily")) {
            List<String> times = config.getStringList(path + ".times");
            if (times.isEmpty() || times.stream().anyMatch(time -> Values.parseTime(time) == null)) {
                throw new InvalidConfigurationException(path + ".times 必须是非空的有效 HH:mm 时刻列表");
            }
        }
    }

    private static void commit(Map<Path, byte[]> writes) throws IOException {
        Map<Path, Path> staged = new LinkedHashMap<>();
        Map<Path, Path> backups = new LinkedHashMap<>();
        List<Path> applied = new ArrayList<>();
        try {
            for (var entry : writes.entrySet()) {
                Path path = entry.getKey();
                Files.createDirectories(path.getParent());
                if (Files.exists(path)) {
                    Path backup = path.resolveSibling(path.getFileName() + ".pre-voidclear.bak");
                    for (int n = 1; Files.exists(backup); n++) backup = path.resolveSibling(path.getFileName() + ".pre-voidclear.bak." + n);
                    Files.copy(path, backup); // 不使用 REPLACE_EXISTING，已有备份永不覆盖。
                    backups.put(path, backup);
                }
                Path temp = Files.createTempFile(path.getParent(), ".voidclear-", ".tmp");
                staged.put(path, temp);
                Files.write(temp, entry.getValue());
            }
            for (var entry : staged.entrySet()) {
                moveReplacing(entry.getValue(), entry.getKey());
                applied.add(entry.getKey());
            }
        } catch (IOException failure) {
            for (Path path : applied.reversed()) {
                try {
                    Path backup = backups.get(path);
                    if (backup == null) Files.deleteIfExists(path);
                    else {
                        Path temp = staged.get(path);
                        Files.copy(backup, temp, StandardCopyOption.REPLACE_EXISTING);
                        moveReplacing(temp, path);
                    }
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        } finally {
            for (Path temp : staged.values()) Files.deleteIfExists(temp);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
