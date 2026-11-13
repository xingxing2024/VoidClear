package net.voidclear.v2.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * config.yml 与 messages.yml 的读取入口。
 *
 * <p>缺键挂内置默认值；首次展示升级仅迁移可识别旧值，修改前保留原文件备份。</p>
 */
public final class Settings {

    public static final String CONFIG = "config.yml";
    public static final String MESSAGES = "messages.yml";

    private final JavaPlugin plugin;

    /** 两份配置全部成功后一次发布，失败时保留上一次快照。 */
    private volatile ConfigMigration.Snapshot snapshot;

    private static volatile Logger fallbackLogger;

    private final Sections.Pace pace;
    private final Sections.Policy policy;
    private final Sections.Care care;

    public Settings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.pace = new Sections.Pace(this);
        this.policy = new Sections.Policy(this);
        this.care = new Sections.Care(this);
    }

    /**
     * 不读文件的配置（自检用）：两份都给空表，各块按代码里的默认值走。
     * 不碰服务端，日志打到控制台。
     */
    public static Settings blank() {
        Settings blank = new Settings(null);
        blank.snapshot = new ConfigMigration.Snapshot(new YamlConfiguration(), new YamlConfiguration());
        return blank;
    }

    /** 自检用：直接装一份内存配置（不落盘）。 */
    public void install(YamlConfiguration config) {
        snapshot = new ConfigMigration.Snapshot(config, snapshot.messages());
    }

    /** 自检用：直接装一份内存 messages.yml（不落盘）。 */
    public void installMessages(YamlConfiguration messages) {
        snapshot = new ConfigMigration.Snapshot(snapshot.config(), messages);
    }

    public Sections.Pace pace() {
        return pace;
    }

    public Sections.Policy policy() {
        return policy;
    }

    public Sections.Care care() {
        return care;
    }

    public void load() {
        if (plugin == null) {
            snapshot = new ConfigMigration.Snapshot(new YamlConfiguration(), new YamlConfiguration());
            return;
        }
        try {
            snapshot = ConfigMigration.load(plugin.getDataFolder().toPath(), plugin::getResource);
        } catch (IOException | InvalidConfigurationException broken) {
            throw new IllegalStateException("配置读取或迁移失败，拒绝发布新配置：" + broken.getMessage(), broken);
        }
    }

    /** Parse fresh files and commit a backup before publishing a GUI edit. */
    public void edit(GuiSetting setting) {
        if(plugin==null)throw new IllegalStateException("配置没有文件目录");
        try { snapshot=ConfigMigration.edit(plugin.getDataFolder().toPath(),plugin::getResource,setting); }
        catch(IOException | InvalidConfigurationException failed) {
            throw new IllegalStateException("设置未保存，原配置已保留："+failed.getMessage(),failed);
        }
    }

    /* ------------------------------------------------------------------ */

    public String raw(String path, String fallback) {
        String value = snapshot.config().getString(path);
        return value == null ? fallback : value;
    }

    /** 旧键兼容时，用户明写的值优先于 JAR 新键默认值。 */
    public String raw(String path, String legacyPath, String fallback) {
        YamlConfiguration config = snapshot.config();
        if (config.contains(path, true)) return config.getString(path, fallback);
        if (config.contains(legacyPath, true)) return config.getString(legacyPath, fallback);
        return config.getString(path, fallback);
    }

    public org.bukkit.configuration.ConfigurationSection rawSection(String path) {
        return snapshot.config().getConfigurationSection(path);
    }

    public int rawInt(String path, int fallback) {
        return snapshot.config().getInt(path, fallback);
    }

    public boolean rawBool(String path, boolean fallback) {
        return snapshot.config().getBoolean(path, fallback);
    }

    public double rawDouble(String path, double fallback) {
        return snapshot.config().getDouble(path, fallback);
    }

    /**
     * 名单类：明写（含空表）优先，其次 jar 默认，最后代码兜底。
     * 空表本身有意义（如 {@code trample.worlds: []} = 全服），不能拿「列表是空的」反推「没写」。
     */
    public List<String> rawListSet(String path, List<String> fallback) {
        YamlConfiguration config = snapshot.config();
        if (!config.isSet(path)) {
            return new ArrayList<>(fallback);
        }
        if (!config.contains(path)) {
            // 用户没写：知道 jar 里有没有这个键，才分得清「jar 的空表」和「jar 也没写」
            if (!defaults().isSet(path)) {
                return new ArrayList<>(fallback);
            }
        }
        return new ArrayList<>(config.getStringList(path));
    }

    /** 同 {@link #rawListSet}：明写的空列表照用。 */
    public List<String> rawList(String path, List<String> fallback) {
        return rawListSet(path, fallback);
    }

    /** 整数表，按同一条「明写 → jar 默认 → 代码兜底」规则取，结果升序。 */
    public List<Integer> rawInts(String path, List<Integer> fallback) {
        YamlConfiguration config = snapshot.config();
        if (!config.isSet(path) || (!config.contains(path) && !defaults().isSet(path))) {
            return sorted(fallback);
        }
        return sorted(config.getIntegerList(path));
    }

    /** 普通字符串列表（不排序），保留方括号（Boss 条标题要用）。 */
    public List<String> rawLines(String path, List<String> fallback) {
        return rawListSet(path, fallback);
    }

    /** jar 内那份同名文件；用户配置没挂默认值时为空表。 */
    private YamlConfiguration defaults() {
        return snapshot.config().getDefaults() instanceof YamlConfiguration defaults ? defaults : new YamlConfiguration();
    }

    private static List<Integer> sorted(List<Integer> list) {
        List<Integer> out = new ArrayList<>(list);
        Collections.sort(out);
        return out;
    }

    public static List<String> trim(List<String> lines, char open, char close) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String value = line == null ? "" : line.trim();
            if (value.length() > 1 && value.charAt(0) == open && value.charAt(value.length() - 1) == close) {
                value = value.substring(1, value.length() - 1).trim();
            }
            out.add(value);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */

    /** 读不到键就用备用句（优先 messages.yml，其次 config.yml）。 */
    public String text(String key, String fallback) {
        ConfigMigration.Snapshot current = snapshot;
        YamlConfiguration messages = current.messages(), config = current.config();
        String value = messages == null ? null : messages.getString(key);
        if (value == null && config != null) {
            value = config.getString(key);
        }
        return value == null ? fallback : value;
    }

    /** 只读 messages.yml（命令表这类，config.yml 里没有对应键，别去 config 里捞别的同名键）。 */
    public boolean hasMessage(String key) {
        YamlConfiguration messages = snapshot.messages();
        return messages != null && messages.contains(key, true);
    }

    public String msg(String key, String fallback) {
        YamlConfiguration messages = snapshot.messages();
        String value = messages == null ? null : messages.getString(key);
        return value == null ? fallback : value;
    }

    /**
     * messages.yml 里的多行文本；写成一行字符串也照收（如 {@code help.header: '...'}）。
     * 明写的空列表同样是「明写」，不回落到兜底。
     */
    public List<String> msgLines(String key, List<String> fallback) {
        YamlConfiguration messages = snapshot.messages();
        if (messages == null || !messages.isSet(key)) {
            return new ArrayList<>(fallback);
        }
        if (messages.isString(key)) {
            return List.of(messages.getString(key));
        }
        return new ArrayList<>(messages.getStringList(key));
    }

    public String prefix() {
        return Values.color(msg("prefix", "&8[&b虚空清理&8] "));
    }

    /** 带前缀的一句播报。 */
    public String message(String key, String fallback) {
        String body = text(key, fallback);
        // 前缀也是 & 风格，必须一起转，否则会原样显示颜色码。
        return Values.color(prefix() + body);
    }

    /** 只着色、不带前缀。 */
    public String hint(String key, String fallback) {
        return Values.color(text(key, fallback));
    }

    public Logger logger() {
        if (plugin != null) {
            return plugin.getLogger();
        }
        if (fallbackLogger == null) {
            fallbackLogger = Logger.getLogger("VoidClear");
        }
        return fallbackLogger;
    }
}
