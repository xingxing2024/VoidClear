package net.voidclear;

import net.voidclear.v2.config.Sections;
import net.voidclear.v2.config.Settings;
import net.voidclear.v2.listener.CareListener;
import net.voidclear.v2.listener.TrashListener;
import net.voidclear.v2.listener.SweepRecovery;
import net.voidclear.v2.listener.PlayerTransfers;
import net.voidclear.v2.rule.Decide;
import net.voidclear.v2.runtime.Countdown;
import net.voidclear.v2.runtime.ChatNotice;
import net.voidclear.v2.runtime.Scheduler;
import net.voidclear.v2.runtime.Sweeper;
import net.voidclear.v2.runtime.Timing;
import net.voidclear.v2.store.HistoryLog;
import net.voidclear.v2.store.PlayerAuditLog;
import net.voidclear.v2.store.TrashStore;
import net.voidclear.v2.store.ServerOwnership;
import net.voidclear.v2.target.Tags;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * VoidClear 主类：把 v2 的各块接起来并管生命周期。
 *
 * <p>启动顺序：读配置 → 登记标记键 → 建清扫器 / 倒计时 → 注册监听 → 起拍。
     * 关服时先停拍并等已执行清扫交付，再停调度、写完历史记录并保存倒计时。</p>
 */
public final class VoidClearPlugin extends JavaPlugin {

    private Settings settings;
    private Scheduler scheduler;
    private HistoryLog history;
    private PlayerAuditLog audit;
    private Decide decide;
    private Sweeper sweeper;
    private net.voidclear.v2.runtime.SweepPreview preview;
    private net.voidclear.v2.listener.AdminMenu adminMenu;
    private TrashStore trash;
    private net.voidclear.v2.store.DatabaseTasks databaseTasks;
    private TrashListener trashListener;
    private SweepRecovery sweepRecovery;
    private PlayerTransfers playerTransfers;
    private ServerOwnership serverOwnership;

    private Countdown sweepTimer;
    private Countdown trashTimer;
    private Runnable unregisterPlaceholders;
    private Scheduler.Beat heartbeat;

    private final org.bukkit.configuration.file.YamlConfiguration data =
            new org.bukkit.configuration.file.YamlConfiguration();
    private java.io.File timerFile;

    @Override
    public void onEnable() {
        try { enable(); }
        catch (RuntimeException | LinkageError failed) {
            getLogger().log(Level.SEVERE, "VoidClear 启动核对失败，正在停止服务器；禁止在交易保护缺失时继续开放，请保留世界、玩家存档与数据库", failed);
            getServer().shutdown();
            throw failed;
        }
    }

    private void enable() {
        settings = new Settings(this);
        settings.load();
        scheduler = Scheduler.create(this);
        Tags.bind(this);
        decide = new Decide(settings.policy());
        timerFile = new java.io.File(getDataFolder(), "timer.yml");
        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            getLogger().warning("无法创建数据目录：" + getDataFolder());
        }
        loadTimers();

        if (!getServer().getOnlinePlayers().isEmpty())
            throw new IllegalStateException("会话归属必须在玩家登录前启动；请完整重启服务器，禁止带在线玩家热启用");
        try { serverOwnership = new ServerOwnership(getDataFolder().toPath().resolve("server-owner.id")); }
        catch (java.io.IOException failed) { throw new IllegalStateException("本服会话归属文件不可用", failed); }

        history = new HistoryLog(this);
        audit = new PlayerAuditLog(getDataFolder().toPath());
        String databaseType = settings.raw("database.type", "sqlite").trim().toLowerCase(java.util.Locale.ROOT);
        if (databaseType.equals("mysql")) {
            trash = TrashStore.mysql(
                    databaseValue("VOIDCLEAR_DB_URL", "database.mysql.jdbc-url", ""),
                    databaseValue("VOIDCLEAR_DB_USER", "database.mysql.username", ""),
                    databaseValue("VOIDCLEAR_DB_PASSWORD", "database.mysql.password", ""),
                    settings.raw("database.mysql.table-prefix", "voidclear_"), getLogger());
        } else if (databaseType.equals("sqlite")) {
            String filename = settings.raw("database.file", "bin.db");
            java.nio.file.Path databaseFile = getDataFolder().toPath().resolve(filename);
            trash = TrashStore.sqlite(databaseFile, getLogger());
            trash.migrateLegacy(getDataFolder().toPath().resolve("trash.yml"));
        } else {
            throw new IllegalStateException("database.type 只能是 sqlite 或 mysql");
        }
        trash.bindSessionOwner(serverOwnership.id());
        databaseTasks = new net.voidclear.v2.store.DatabaseTasks();
        sweepRecovery = new SweepRecovery(this);
        playerTransfers = new PlayerTransfers(this);
        sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler, decide, trash, sweepRecovery);
        trashListener = new TrashListener(this);
        preview = new net.voidclear.v2.runtime.SweepPreview(settings.pace(),settings.policy(),scheduler,decide);
        adminMenu = new net.voidclear.v2.listener.AdminMenu(this);

        sweepTimer = new Countdown(this, this::beginSweep);
        trashTimer = new Countdown(this, this::clearTrashAutomatically);

        applyLook();

        PluginCommand command = getCommand("voidclear");
        if (command != null) {
            CommandHandler handler = new CommandHandler(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("plugin.yml 里没有注册 voidclear 指令。");
        }

        getServer().getPluginManager().registerEvents(new CareListener(this), this);
        getServer().getPluginManager().registerEvents(playerTransfers, this);
        getServer().getPluginManager().registerEvents(trashListener, this);
        getServer().getPluginManager().registerEvents(adminMenu, this);
        getServer().getPluginManager().registerEvents(sweepRecovery, this);
        for (var world : getServer().getWorlds()) for (var chunk : world.getLoadedChunks()) {
            scheduler.onChunk(world, chunk.getX(), chunk.getZ(), () -> {
                if (chunk.isLoaded()) for (var entity : chunk.getEntities())
                    if (entity instanceof org.bukkit.entity.Item item) {
                        playerTransfers.loadedDrop(item);
                        sweepRecovery.reconcile(item);
                    }
            });
        }

        heartbeat = scheduler.every(20L, this::beat);
        resumeTimers();
        persistTimers();

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            var placeholders = new Placeholders(this);
            unregisterPlaceholders = placeholders::unregister;
            if (!placeholders.register()) getLogger().warning("PlaceholderAPI 变量注册失败，请检查是否有重复的 voidclear 扩展。");
        }
        getLogger().info("已启动。清扫间隔 " + settings.pace().period() + " 秒，掉落物进入垃圾箱。");
    }

    /** 关服 / 热停用：等已开跑的清扫交付完，再停调度、存倒计时。 */
    @Override
    public void onDisable() {
        if (unregisterPlaceholders != null) { cleanup("PAPI", unregisterPlaceholders); unregisterPlaceholders = null; }
        if (heartbeat != null) {
            cleanup("心跳", heartbeat::cancel);
            heartbeat = null;
        }
        if (adminMenu != null) cleanup("管理面板", adminMenu::shutdown);
        if (preview != null) cleanup("预览屏障", () -> preview.stopAndAwait(0L));
        if (trashListener != null) cleanup("菜单", trashListener::shutdown);
        if (playerTransfers != null) cleanup("玩家会话", playerTransfers::shutdown);
        if (sweeper != null) {
            cleanup("清扫屏障", () -> sweeper.stopAndAwait(0L));
        }
        boolean interrupted = Thread.interrupted();
        if (scheduler != null) {
            cleanup("调度器", scheduler::shutdown);
        }
        if (databaseTasks != null) cleanup("数据库任务", databaseTasks::close);
        if (history != null) cleanup("历史与审计", history::close);
        if (trash != null) cleanup("垃圾箱保存", trash::close);
        if (serverOwnership != null) {
            if (playerTransfers != null && playerTransfers.hasReservations())
                getLogger().warning("仍有未释放会话，保留本服文件锁直到进程退出；禁止热重载接管");
            else cleanup("会话文件锁", () -> {
                try { serverOwnership.close(); }
                catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
            });
        }
        // 先存倒计时：停止计时器会把它们标成未运行，顺序不能反
        cleanup("倒计时保存", this::persistTimers);
        if (sweepTimer != null) {
            cleanup("清扫计时器", sweepTimer::stop);
        }
        if (trashTimer != null) cleanup("垃圾箱计时器", trashTimer::stop);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        getLogger().info("已停用。");
    }

    private void cleanup(String name, Runnable task) {
        cleanup(getLogger(), name, task);
    }

    static void cleanup(java.util.logging.Logger logger, String name, Runnable task) {
        try { task.run(); }
        catch (RuntimeException failed) { logger.log(Level.SEVERE, "停用清理失败：" + name + "；继续其他清理", failed); }
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    /** 每秒一拍：跑清扫倒计时。 */
    private void beat() {
        try {
            if (!scheduler.running()) return;
            sweepTimer.tick(message("sweep.countdown.tick", "&7还有 &f%TIME% &7秒开始清扫。"));
            trashTimer.tick(message("trash.countdown.tick", "&7垃圾箱将在 &f%TIME% &7秒后清空。"));
            ensureSweepCountdown();
            trashListener.refreshOpenMenus();
            adminMenu.refresh();
        } catch (RuntimeException failed) {
            getLogger().log(java.util.logging.Level.WARNING, "垃圾箱心跳刷新失败，下次心跳继续", failed);
        }
    }

    /** 倒计时开始 / 触发后记一笔，重启后接着走（关机时间也算进去）。 */
    private void persistTimers() {
        if (sweepTimer == null || timerFile == null) {
            return;
        }
        data.set("timer.sweep-deadline", sweepTimer.running() ? sweepTimer.deadline() : 0L);
        data.set("timer.trash-deadline", trashTimer != null && trashTimer.running() ? trashTimer.deadline() : 0L);
        saveTimers();
    }

    private void loadTimers() {
        if (timerFile.isFile()) {
            try {
                data.load(timerFile);
            } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException broken) {
                getLogger().warning("timer.yml 读取失败，倒计时按配置从头算：" + broken.getMessage());
            }
        }
    }

    private void saveTimers() {
        try {
            data.save(timerFile);
        } catch (java.io.IOException broken) {
            getLogger().warning("timer.yml 写入失败：" + broken.getMessage());
        }
    }

    private void resumeTimers() {
        long now = System.currentTimeMillis();
        long sweepAt = data.getLong("timer.sweep-deadline", 0L);
        long trashAt = data.getLong("timer.trash-deadline", 0L);
        if (sweepAt > now) {
            sweepTimer.beginAt(sweepAt, (sweepAt - now) / 1000L + 1);
            getLogger().info("接续上次的清扫倒计时，还剩 " + sweepTimer.secondsLeft() + " 秒。");
        } else {
            startSweepCountdown(settings.pace().daily());
        }
        if (settings.pace().trashClearEnabled()) {
            if (settings.pace().trashClearDaily()) startTrashCountdown();
            else if (trashAt > 0) trashTimer.beginAt(trashAt, settings.pace().trashClearPeriod());
            else startTrashCountdown();
        }
    }

    private void startTrashCountdown() {
        long seconds = Timing.secondsUntil(settings.pace().trashClearPeriod(), settings.pace().trashClearDaily(),
                settings.pace().trashClearTimes(), System.currentTimeMillis(), java.time.ZoneId.systemDefault());
        trashTimer.begin(seconds);
    }

    private void clearTrashAutomatically() {
        if (trash.mysql()) {
            clearTrashAsync().whenComplete((count, failed) -> {
                if (failed != null) getLogger().log(Level.SEVERE, "自动清空未确认，保留数据库证据", failed);
                else broadcast(message("trash.cleared", "&7垃圾箱已清空，删除 &f%COUNT% &7件物品。")
                        .replace("%COUNT%", String.valueOf(count)));
            });
            startTrashCountdown();
            persistTimers();
            return;
        }
        try {
            long count = clearTrash();
            broadcast(message("trash.cleared", "&7垃圾箱已清空，删除 &f%COUNT% &7件物品。")
                    .replace("%COUNT%", String.valueOf(count)));
        } catch (IllegalStateException failed) {
            getLogger().log(java.util.logging.Level.SEVERE, "垃圾箱自动清空未确认，请检查数据库；下次按配置周期执行", failed);
        }
        startTrashCountdown();
        persistTimers();
    }

    public long clearTrash() {
        synchronized (trash) {
            trashListener.invalidateOpenMenus();
            return trash.clearAll();
        }
    }

    public java.util.concurrent.CompletableFuture<Long> clearTrashAsync() {
        trashListener.invalidateOpenMenus();
        return databaseTasks.submit(trash::clearAll);
    }

    public PlayerTransfers playerTransfers() { return playerTransfers; }
    public SweepRecovery sweepRecovery() { return sweepRecovery; }
    public net.voidclear.v2.store.DatabaseTasks databaseTasks() { return databaseTasks; }
    public ServerOwnership serverOwnership() { return serverOwnership; }
    public PlayerAuditLog audit() { return audit; }

    public void recordAudit(java.util.UUID player, String name, java.util.UUID operation, List<PlayerAuditLog.Movement> changes) {
        if (changes.isEmpty()) return;
        try {
            var now = java.time.Instant.now();
            var entries = changes.stream().map(change -> new PlayerAuditLog.Entry(now, player, name,
                    serverOwnership.id(), operation, change)).toList();
            history.execute(() -> {
                try { audit.append(entries); }
                catch (RuntimeException failed) { getLogger().log(Level.SEVERE, "已确认交易的审计写入失败；不得据此补发物品", failed); }
            });
        } catch (RuntimeException failed) { getLogger().log(Level.SEVERE, "已确认交易的审计排队失败；不得据此补发物品", failed); }
    }

    /* ------------------------------------------------------------------ */
    /* 清扫                                                                */
    /* ------------------------------------------------------------------ */

    /** 开始下一轮倒计时。 */
    public void startSweepCountdown(boolean daily) {
        long seconds = daily
                ? secondsUntil(settings.pace().period(), settings.pace().dailyTimes())
                : settings.pace().period();
        sweepTimer.begin(Math.max(1, seconds));
        persistTimers();
    }

    private void beginSweep() {
        runSweep(message("sweep.countdown.start", "&a清扫开始。"), () -> {
            String done = sweepSummary();
            broadcast(done);
            getLogger().info(done);
        });
    }

    /**
     * 扫一轮，完了再按模式排下一轮。
     *
     * <p>倒计时排在 {@code now} 上而不是 sweeper 的回调里：sweeper 正在跑时会直接丢弃这次请求、
     * 回调永远不来，排在回调里就等于这一轮的后续倒计时丢了。</p>
     *
     * <p>{@code startLine} 只在真的开扫时播一次：自动到点和手动指令都走这里，别在调用方再播一遍。</p>
     *
     * @return false 表示上一轮还没结束，这次请求没执行
     */
    public boolean runSweep(String startLine, Runnable finished) {
        if (sweeper.active()) {
            return false;
        }
        broadcast(startLine);
        startSweepCountdown(settings.pace().daily());
        if (!sweeper.start(() -> {
            String historyLine = "SWEEP removed=" + sweeper.lastTotal() + " collected_groups="
                    + sweeper.lastCollected() + " collected_items=" + sweeper.lastCollectedItems() + " duration_ms=" + sweeper.lastDurationMillis() + " failed_tasks=" + sweeper.lastFailures();
            history.execute(() -> {
                try { history.add(historyLine); }
                catch (RuntimeException failed) { getLogger().log(Level.WARNING, "写入清扫记录失败", failed); }
            });
            sweepTimer.finish();
            finished.run();
        })) {
            return false;
        }
        return true;
    }

    public String sweepSummary() {
        return net.voidclear.v2.runtime.SweepReport.result(settings, sweeper.lastTotal(), sweeper.lastCollected(), sweeper.lastCollectedItems(),
                sweeper.lastFailures(), sweeper.lastDurationMillis());
    }

    /** 倒计时到点（或开服接续）时可能还没到，这里保证之后倒计时不会断。 */
    private void ensureSweepCountdown() {
        if (!sweepTimer.running() && !sweeper.active()) {
            startSweepCountdown(settings.pace().daily());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 配置                                                                */
    /* ------------------------------------------------------------------ */

    /** 改清扫相关配置后重载；立即生效，不用重启。 */
    public void reload() { updateConfiguration(settings::load); }

    private void updateConfiguration(Runnable install) {
        int sweepPeriod = settings.pace().period();
        boolean sweepDaily = settings.pace().daily();
        List<String> sweepTimes = settings.pace().dailyTimes();
        boolean clearEnabled = settings.pace().trashClearEnabled();
        int clearPeriod = settings.pace().trashClearPeriod();
        boolean clearDaily = settings.pace().trashClearDaily();
        List<String> clearTimes = settings.pace().trashClearTimes();
        Runnable reloadMenu = trashListener.prepareReload();
        install.run();
        reloadMenu.run();
        applyLook();
        if (!settings.pace().trashClearEnabled()) trashTimer.stop();
        else if (!clearEnabled || clearPeriod != settings.pace().trashClearPeriod()
                || clearDaily != settings.pace().trashClearDaily()
                || !clearTimes.equals(settings.pace().trashClearTimes()) || !trashTimer.running()) {
            startTrashCountdown();
        }
        if (sweepPeriod != settings.pace().period() || sweepDaily != settings.pace().daily()
                || !sweepTimes.equals(settings.pace().dailyTimes())
                || !sweeper.active() && !sweepTimer.running()) {
            startSweepCountdown(settings.pace().daily());
        }
        persistTimers();
    }

    public void editSetting(net.voidclear.v2.config.GuiSetting setting) {
        updateConfiguration(() -> settings.edit(setting));
    }

    public net.voidclear.v2.runtime.SweepPreview preview() { return preview; }
    public net.voidclear.v2.listener.AdminMenu adminMenu() { return adminMenu; }

    private String databaseValue(String environment, String path, String fallback) {
        String value = System.getenv(environment);
        return value == null || value.isBlank() ? settings.raw(path, fallback) : value;
    }


    private void applyLook() {
        trashTimer.announce(settings.pace().trashClearMarks(), List.of(), 0);
        sweepTimer.look(settings.pace().sweepBar(), settings.pace().sweepBarColor(), settings.pace().sweepBarTitle());
        sweepTimer.showBefore(settings.pace().sweepBarShowBefore());
        sweepTimer.actionBar(settings.pace().sweepActionBar(), settings.pace().sweepActionBarTitle());
        sweepTimer.announce(settings.pace().marks(), settings.pace().soundMarks(), settings.pace().soundTail());
        sweepTimer.sound(settings.pace().soundKey(), settings.pace().finishSoundKey(),
                settings.pace().volume(), settings.pace().pitch());
    }

    private long secondsUntil(int period, List<String> times) {
        return Timing.secondsUntil(period, true, times,
                System.currentTimeMillis(), java.time.ZoneId.systemDefault());
    }

    /* ------------------------------------------------------------------ */
    /* 给指令与监听用                                                      */
    /* ------------------------------------------------------------------ */

    public String message(String key, String fallback) {
        return settings.message(key, fallback);
    }

    public String hint(String key, String fallback) {
        return settings.hint(key, fallback);
    }

    /** 给指令与监听用：全局播一句。 */
    public void broadcast(String line) {
        var notice = ChatNotice.of(line);
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.getScheduler().run(this, scheduled -> {
                    if (player.isOnline()) player.sendMessage(notice);
                }, null);
            } catch (RuntimeException gone) {
                // 玩家下线或插件停用时不再发送。
            }
        }
    }

    public Settings settings() {
        return settings;
    }

    public Sweeper sweeper() {
        return sweeper;
    }

    public Decide decide() {
        return decide;
    }

    public Countdown sweepTimer() {
        return sweepTimer;
    }

    public Countdown trashTimer() { return trashTimer; }

    public HistoryLog history() {
        return history;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public TrashStore trash() {
        return trash;
    }

    public TrashListener trashListener() {
        return trashListener;
    }

}
