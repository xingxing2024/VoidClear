package net.voidclear;

import net.voidclear.v2.rule.Outcome;
import net.voidclear.v2.runtime.Scheduler;
import net.voidclear.v2.store.DiagnosticReport;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /vc} 的全部子指令。文案优先取 messages.yml，读不到用这里的备用句。
 */
public final class CommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("admin", "sweep", "preview", "trash", "check", "keep", "unkeep", "status", "diagnose", "audit", "history", "trend", "export", "receipt", "reload", "help");

    /**
     * 已经带颜色的文本：转成组件再发，颜色码才不会原样显示。
     * 玩家实际看到的样子与控制台一致，测试时就是靠这个核对文案。
     */
    private static void raw(CommandSender sender, String line) {
        sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(line.replace('\u00a7', '&')));
    }

    private void reply(CommandSender sender, String line) {
        if (sender instanceof Player player) {
            plugin.scheduler().player(player, () -> {
                if (player.isOnline()) raw(player, line);
            });
        } else {
            raw(sender, line);
        }
    }

    /**
     * 读一行的配置项。YAML 里写成 {@code header: '- ... -'} 时给到的是单元素列表，
     * 外面包的方括号顺手去掉，写成一行字符串也照收。
     */
    private static String single(List<String> lines) {
        List<String> trimmed = net.voidclear.v2.config.Settings.trim(lines, '[', ']');
        return trimmed.isEmpty() ? "" : trimmed.get(0);
    }

    private final VoidClearPlugin plugin;

    public CommandHandler(VoidClearPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("trash")) {
            if (args.length == 3 && args[1].equalsIgnoreCase("clear") && args[2].equalsIgnoreCase("confirm")) {
                if (!admin(sender)) { raw(sender, plugin.message("general.no-permission", "&c没有权限")); return true; }
                Scheduler.global(plugin, () -> {
                    try {
                        if (plugin.trash().mysql()) {
                            plugin.clearTrashAsync().whenComplete((count, failed) -> {
                                if (failed != null) reply(sender, "&c清空未确认，请保留数据库并联系管理员。");
                                else reply(sender, "&7垃圾箱已清空，删除 &f" + count + " &7件物品。");
                            });
                        } else reply(sender, "&7垃圾箱已清空，删除 &f" + plugin.clearTrash() + " &7件物品。");
                    }
                    catch (IllegalStateException failed) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "垃圾箱清空未确认", failed);
                        reply(sender, "&c数据库操作未确认，请检查连接及日志；未报告清空成功。");
                    }
                });
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("clear") && admin(sender)) {
                raw(sender, "&c此操作永久删除垃圾箱物品。确认执行：/vc trash clear confirm");
                return true;
            }
            try {
                var filter=net.voidclear.v2.listener.TrashFilter.parse(args);
                if(sender instanceof Player player) plugin.trashListener().open(player,filter);
                else raw(sender,plugin.message("general.players-only","&c该指令只能在游戏内执行"));
            } catch(IllegalArgumentException invalid) {
                raw(sender,"&7用法：/vc trash [search <名称或材质> | category <分类> [关键词]]");
            }
            return true;
        }
        if (sub.equals("help")) {
            try { help(sender, helpPage(args, admin(sender))); }
            catch (IllegalArgumentException invalid) {
                raw(sender, plugin.message("help.usage", admin(sender) ? "&7用法：/vc help [1-3]" : "&7用法：/vc help"));
            }
            return true;
        }
        if (!admin(sender)) {
            raw(sender, plugin.message("general.no-permission", "&c你没有权限这么做。"));
            return true;
        }
        // 控制台没有玩家实体：只有这些子指令不依赖玩家
        if (!(sender instanceof Player) && List.of("keep", "unkeep").contains(sub)) {
            raw(sender, plugin.message("general.players-only", "&c这条指令只能在游戏里执行。"));
            return true;
        }
        switch (sub) {
            case "admin" -> {
                if(args.length!=1)raw(sender,"&7用法：/vc admin");
                else if(sender instanceof Player player)plugin.adminMenu().open(player);
                else raw(sender,plugin.message("general.players-only","&c只能在游戏内打开管理面板。"));
            }
            case "sweep" -> sweep(sender, args);
            case "preview" -> preview(sender,args);
            case "check" -> check(sender, args);
            case "keep" -> keep(sender, args, true);
            case "unkeep" -> keep(sender, args, false);
            case "status" -> status(sender);
            case "diagnose" -> diagnose(sender, args);
            case "audit" -> audit(sender, args);
            case "history" -> history(sender, args);
            case "trend" -> stats(sender, args, false);
            case "export" -> stats(sender, args, true);
            case "receipt" -> receipt(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender, 1);
        }
        return true;
    }

    /* ------------------------------------------------------------------ */

    /**
     * 手动一轮清扫：会按配置重新排一次倒计时（等于把倒计时重置），扫完不额外续期。
     * 有倒计时 / 清扫器这些全局状态，所以整体排到全局调度器再动。
     */
    private void sweep(CommandSender sender, String[] args) {
        if(args.length>2 || args.length==2 && !List.of("pause","resume","progress").contains(args[1].toLowerCase(Locale.ROOT))) {
            raw(sender,"&7用法：/vc sweep [pause|resume|progress]");return;
        }
        Scheduler.global(plugin, () -> {
            if(args.length==2) {
                var sweeper=plugin.sweeper();
                switch(args[1].toLowerCase(Locale.ROOT)) {
                    case "pause" -> reply(sender,sweeper.pause()?"&e已暂停后续清扫批次，已提交任务会正常完成。":"&7当前没有可暂停的清扫。");
                    case "resume" -> reply(sender,sweeper.resume()?"&a清扫已继续。":"&7当前没有已暂停的清扫。");
                    case "progress" -> sweepProgress(sender);
                }
                return;
            }
            String start = plugin.message("sweep.triggered", "&7清扫已开始。");
            Runnable done = () -> reply(sender, plugin.sweepSummary());
            if (!plugin.runSweep(start, done)) {
                reply(sender, plugin.message("sweep.busy", "&7上一轮清扫尚未结束，请稍等"));
            }
        });
    }

    private void preview(CommandSender sender,String[] args) {
        if(args.length>2) {raw(sender,"&7用法：/vc preview [世界]");return;}
        Scheduler.global(plugin,()-> {
            var run=plugin.preview();
            if(run.active()) {reply(sender,"&7已有清扫预览正在执行，请稍候。");return;}
            List<World> worlds;
            if(args.length==2) {
                World world=Bukkit.getWorld(args[1]);
                if(world==null) {reply(sender,"&c找不到世界："+args[1]);return;}
                worlds=List.of(world);
            } else worlds=Bukkit.getWorlds();
            reply(sender,plugin.message("preview.started","&7正在预览已加载区块，不会移除实体或写入库存…"));
            run.start(worlds,()-> {
                reply(sender,plugin.message("preview.summary","&b清扫预览 &7（当前估计，正式执行时重新判定）"));
                run.results().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(row->{
                    var count=row.getValue();
                    reply(sender,"&f"+row.getKey()+" &8· &7生物 &f"+count.creatures()+" &7掉落实体 &f"+count.drops()+" &7经验球 &f"+count.experience());
                    String skipped=count.skipped().entrySet().stream().filter(e->e.getValue()>0).sorted(java.util.Map.Entry.comparingByKey())
                            .map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("，"));
                    if(!skipped.isEmpty()) reply(sender,"&8跳过："+skipped);
                });
                if(run.results().isEmpty()) reply(sender,"&7没有可统计的已加载区块。");
                if(run.lastFailures()>0) reply(sender,"&e预览未完整执行，失败任务："+run.lastFailures()+"，请检查日志。");
                reply(sender,"&8预览不核验垃圾箱容量或待恢复来源，不承诺实际入箱件数。");
            });
        });
    }

    /** {@code /vc keep [名字|id|all]} 与 {@code /vc unkeep}：给实体打上/去掉不清扫的标记。 */
    private void keep(CommandSender sender, String[] args, boolean on) {
        if (!(sender instanceof Player player)) {
            raw(sender, plugin.message("general.players-only", "&c这条指令只能在游戏里执行。"));
            return;
        }
        if (args.length < 2) {
            org.bukkit.entity.Entity target = player.getTargetEntity(16);
            if (target != null && !(target instanceof Player)) {
                tag(target, on, hit -> done(sender, on, hit ? 1 : 0));
            } else {
                done(sender, on, 0);
            }
            return;
        }
        // 16 格内的实体可能分属不同区域：不能在这一拍直接写 PDC，改写到各自实体的调度器上
        List<org.bukkit.entity.Entity> found = new java.util.ArrayList<>();
        if (args[1].equalsIgnoreCase("all")) {
            for (org.bukkit.entity.Entity nearby : player.getNearbyEntities(16, 16, 16)) {
                if (!(nearby instanceof Player)) {
                    found.add(nearby);
                }
            }
        } else {
            // 按名字或实体 id 找 16 格内全部匹配的实体
            String what = args[1].toLowerCase(Locale.ROOT);
            for (org.bukkit.entity.Entity nearby : player.getNearbyEntities(16, 16, 16)) {
                if (nearby instanceof Player) {
                    continue;
                }
                String id = nearby.getType().getKey().toString();
                if (nearby.getName().equalsIgnoreCase(args[1]) || id.equalsIgnoreCase(what) || id.endsWith(":" + what)) {
                    found.add(nearby);
                }
            }
        }
        if (found.isEmpty()) {
            done(sender, on, 0);
            return;
        }
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(found.size());
        java.util.concurrent.atomic.AtomicInteger marked = new java.util.concurrent.atomic.AtomicInteger();
        for (org.bukkit.entity.Entity entity : found) {
            tag(entity, on, hit -> {
                if (hit) {
                    marked.incrementAndGet();
                }
                if (left.decrementAndGet() == 0) {
                    done(sender, on, marked.get());
                }
            });
        }
    }

    /**
     * 在实体自己的区域线程上打/去标记；回调也在那里，计数完再统一回一句。
     * 已失效或排不上的实体按「没标上」算，不会让整条指令永远等下去。
     * {@code result} 由这里保证只调一次：它自己抛异常就往上抛，不 catch 后再补一次。
     */
    private void tag(org.bukkit.entity.Entity entity, boolean on, java.util.function.Consumer<Boolean> result) {
        Runnable mark = () -> {
            boolean marked = false;
            try {
                if (entity.isValid()) {
                    if (on) {
                        net.voidclear.v2.target.Tags.mark(entity);
                    } else {
                        net.voidclear.v2.target.Tags.unmark(entity);
                    }
                    marked = true;
                }
            } catch (RuntimeException failed) {
                plugin.getLogger().warning("实体标记失败：" + failed.getMessage());
            }
            result.accept(marked);
        };
        boolean queued;
        try {
            // 返回 null = 实体已被移除：任务不会跑，失败回调也不会来，只能在这里补一次
            queued = entity.getScheduler().run(plugin, scheduled -> mark.run(), () -> result.accept(false)) != null;
        } catch (RuntimeException gone) {
            queued = false;
        }
        if (!queued) {
            result.accept(false);
        }
    }

    private void done(CommandSender sender, boolean on, int marked) {
        reply(sender, plugin.hint(on ? "keep.marked" : "keep.unmarked",
                        on ? "&a已标记 %COUNT% 个实体不被清扫。" : "&7已取消 %COUNT% 个实体的标记。")
                .replace("%COUNT%", String.valueOf(marked)));
    }

    /** {@code /vc check <实体id>}：看会保留还是被扫、依据是哪条。 */
    private void check(CommandSender sender, String[] args) {
        if (args.length < 2) {
            raw(sender, plugin.hint("check.usage", "&7用法：/vc check <实体id>"));
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        EntityType type;
        try {
            type = EntityType.valueOf(name.toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
        } catch (IllegalArgumentException unknown) {
            raw(sender, plugin.hint("check.unknown", "&c没有这个实体：%ID%").replace("%ID%", name));
            return;
        }
        String id = type.getKey().toString();
        // 命令可能在自己的区域线程上跑：玩家世界名要在这里（玩家线程）取，
        // 全局调度器上碰 player.getWorld() 在 Folia 会跨区域
        String asked = args.length > 2 ? args[2]
                : sender instanceof Player player ? player.getWorld().getName() : firstWorld();
        Scheduler.global(plugin, () -> {
            String world = asked;
            boolean monster = type.getEntityClass() != null
                    && org.bukkit.entity.Enemy.class.isAssignableFrom(type.getEntityClass())
                    && !org.bukkit.entity.Animals.class.isAssignableFrom(type.getEntityClass());
            boolean animal = type.getEntityClass() != null
                    && org.bukkit.entity.Animals.class.isAssignableFrom(type.getEntityClass());
            Outcome outcome = plugin.decide().verdict(world, id, monster, animal);
            // %RESULT% 是嵌在 result 里的一句，不能带前缀，否则整行会混进第一句
            String result = outcome.remove()
                    ? plugin.hint("check.removed", "&c会被扫掉")
                    : plugin.hint("check.kept", "&a保留");
            if (type == EntityType.ITEM && outcome.reason().contains("无法判定")) {
                result = "\u00a7e需查看具体物品";
            }
            reply(sender, plugin.hint("check.result", "&7%ID%：%RESULT% &8（%REASON%）")
                    .replace("%ID%", id)
                    .replace("%RESULT%", result)
                    .replace("%REASON%", outcome.reason()));
        });
    }

    private String firstWorld() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? "world" : worlds.get(0).getName();
    }

    private void status(CommandSender sender) {
        Scheduler.global(plugin, () -> report(sender));
    }

    record HistoryQuery(int count, String type, String player) {}

    static HistoryQuery auditQuery(String[] args) {
        HistoryQuery query = historyQuery(args);
        String type = query.type() == null ? null : query.type().toUpperCase(Locale.ROOT);
        if (type != null && !List.of("DEPOSIT", "WITHDRAW").contains(type))
            throw new IllegalArgumentException("Unknown audit action");
        if (query.player() != null && (query.player().isBlank() || query.player().length() > 128
                || query.player().codePoints().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Invalid audit player");
        return new HistoryQuery(query.count(), type, query.player());
    }

    static boolean diagnoseExport(String[] args) {
        if (args.length == 1) return false;
        if (args.length == 2 && args[1].equalsIgnoreCase("export")) return true;
        throw new IllegalArgumentException("Unexpected diagnostic arguments");
    }

    record ReceiptQuery(java.util.UUID player, String confirmation) { }
    static ReceiptQuery receiptQuery(String[] args) {
        if (args.length != 2 && (args.length != 4 || !args[2].equalsIgnoreCase("confirm")))
            throw new IllegalArgumentException("Receipt usage");
        var player = java.util.UUID.fromString(args[1]);
        if (!player.toString().equalsIgnoreCase(args[1])) throw new IllegalArgumentException("Full UUID required");
        if (args.length == 4 && !java.util.UUID.fromString(args[3]).toString().equalsIgnoreCase(args[3]))
            throw new IllegalArgumentException("Invalid confirmation");
        return new ReceiptQuery(player, args.length == 4 ? args[3].toLowerCase(Locale.ROOT) : null);
    }

    private void receipt(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            raw(sender, plugin.message("receipt.console-only", "&c收据清理仅限服务器控制台；游戏内和命令方块不能执行。"));
            return;
        }
        ReceiptQuery query;
        try { query = receiptQuery(args); }
        catch (IllegalArgumentException invalid) {
            raw(sender, plugin.message("receipt.usage", "&7用法：/vc receipt <玩家UUID> [confirm <预览确认码>]"));
            return;
        }
        reply(sender, "&7正在后台核对离线存档与数据库；不会补发物品或删除交易凭据。");
        plugin.databaseTasks().submit(() -> plugin.playerTransfers().withOfflineReceipt(query.player(), () -> {
            var cleaner = plugin.playerTransfers().receiptCleaner();
            if (query.confirmation() == null) {
                var preview = cleaner.preview(query.player(), plugin.trash());
                reply(sender, "&e仅适用于确认旧数据库已丢失的孤立收据；请先核对数据库配置及全服备份。不是恢复旧交易。");
                reply(sender, "&7玩家 &f" + preview.player() + " &7· 旧收据 &f" + preview.receipt());
                reply(sender, "&e确认将以当前存档背包为准，仅移除失效收据；历史交易守恒无法证明。确认码5分钟有效，新预览替换旧预览。");
                reply(sender, "&f/vc receipt " + preview.player() + " confirm " + preview.token());
            } else {
                var backup = cleaner.confirm(query.player(), query.confirmation(), plugin.trash());
                plugin.getLogger().warning("管理员按当前存档基线清理孤立收据：player=" + query.player() + " backup=" + backup);
                reply(sender, "&7失效收据已清理；背包、其他PDC及数据库记录未改动。备份：&f" + backup);
            }
            return true;
        })).whenComplete((ignored, failed) -> {
            if (failed != null) {
                Throwable cause = failed;
                while (cause.getCause() != null) cause = cause.getCause();
                reply(sender, "&c清理未确认：" + DiagnosticReport.safe(String.valueOf(cause.getMessage())) + "。请保留日志与备份核查，不要补发物品。");
                plugin.getLogger().log(java.util.logging.Level.WARNING, "孤立收据清理未确认：" + query.player(), failed);
            }
        });
    }

    private void audit(CommandSender sender, String[] args) {
        HistoryQuery query;
        try { query = auditQuery(args); }
        catch (IllegalArgumentException invalid) {
            raw(sender, plugin.message("audit.usage", "&7用法：/vc audit [1-20] [type <DEPOSIT|WITHDRAW>|player <名字或UUID>]"));
            return;
        }
        try {
            plugin.history().execute(() -> {
                try {
                    var entries = plugin.audit().matching(query.count(), query.type(), query.player());
                    reply(sender, plugin.message("audit.header", "&b本服存取审计 &8· &f%COUNT% 条")
                            .replace("%COUNT%", String.valueOf(entries.size())));
                    if (entries.isEmpty()) reply(sender, plugin.message("audit.empty", "&7保留范围内无匹配记录；不能据此证明没有存取。"));
                    for (var entry : entries) {
                        reply(sender, "&8" + entry.time() + " &f" + DiagnosticReport.safe(entry.name()) + " &7"
                                + (entry.movement().action().equals("DEPOSIT") ? "存入 " : "取出 ")
                                + entry.movement().material() + " ×" + entry.movement().amount());
                        reply(sender, "&8玩家=" + entry.player() + " 服务器=" + entry.server() + " 操作=" + entry.operation());
                    }
                } catch (RuntimeException failed) { auditFailed(sender, failed); }
            });
        } catch (RuntimeException failed) { auditFailed(sender, failed); }
    }

    private void auditFailed(CommandSender sender, RuntimeException failed) {
        reply(sender, plugin.message("audit.failed", "&c读取存取审计失败，请检查服务端日志。"));
        plugin.getLogger().log(java.util.logging.Level.WARNING, "读取存取审计失败", failed);
    }

    private void diagnose(CommandSender sender, String[] args) {
        boolean export;
        try { export = diagnoseExport(args); }
        catch (IllegalArgumentException invalid) {
            raw(sender, plugin.message("diagnose.usage", "&7用法：/vc diagnose [export]"));
            return;
        }
        var local = new DiagnosticReport.Local(java.time.Instant.now(), plugin.serverOwnership().id(), plugin.trash().mysql(),
                plugin.databaseTasks().queued(), plugin.databaseTasks().running(), plugin.playerTransfers().activeSessions(),
                plugin.playerTransfers().pendingCount(), plugin.playerTransfers().isolatedCount());
        reply(sender, plugin.message("diagnose.started", "&7正在后台读取诊断；不会修改数据库、解除隔离或补发物品。"));
        reply(sender, "&7后台任务排队 " + local.queued() + " / 执行 " + local.running() + " · 待确认玩家 " + local.pending());
        try {
            plugin.history().execute(() -> {
                net.voidclear.v2.store.TrashStore.Diagnostics database = null;
                try { database = plugin.trash().diagnostics(); }
                catch (RuntimeException failed) { plugin.getLogger().log(java.util.logging.Level.WARNING, "只读数据库诊断失败", failed); }
                var lines = DiagnosticReport.lines(local, database);
                lines.forEach(line -> reply(sender, "&7" + line));
                if (export) {
                    try {
                        var file = DiagnosticReport.export(plugin.getDataFolder().toPath(), lines);
                        reply(sender, plugin.message("diagnose.exported", "&7诊断已导出：&f%FILE%")
                                .replace("%FILE%", file.getFileName().toString()));
                    } catch (RuntimeException failed) { diagnoseFailed(sender, failed); }
                }
            });
        } catch (RuntimeException failed) { diagnoseFailed(sender, failed); }
    }

    private void diagnoseFailed(CommandSender sender, RuntimeException failed) {
        reply(sender, plugin.message("diagnose.failed", "&c诊断任务或导出失败，请检查服务端日志。"));
        plugin.getLogger().log(java.util.logging.Level.WARNING, "诊断任务或导出失败", failed);
    }

    static HistoryQuery historyQuery(String[] args) {
        int count = 10;
        String type = null;
        String player = null;
        if (args.length > 4) throw new IllegalArgumentException("Too many arguments");
        if (args.length >= 2) {
            int index = 1;
            try {
                count = Integer.parseInt(args[index]);
                index++;
            } catch (NumberFormatException invalid) {
                // 允许省略条数，直接使用 /vc history type SWEEP。
            }
            if (count < 1 || count > 20) {
                throw new IllegalArgumentException("Count must be 1-20");
            }
            if (index < args.length) {
                if (args.length != index + 2) {
                    throw new IllegalArgumentException("Filter requires a value");
                }
                if (args[index].equalsIgnoreCase("type")) type = args[index + 1];
                else if (args[index].equalsIgnoreCase("player")) player = args[index + 1];
                else {
                    throw new IllegalArgumentException("Unknown filter");
                }
            }
        }
        return new HistoryQuery(count, type, player);
    }

    private void history(CommandSender sender, String[] args) {
        HistoryQuery query;
        try {
            query = historyQuery(args);
        } catch (IllegalArgumentException invalid) {
            raw(sender, plugin.message("history.usage", "&7用法：/vc history [条数] [type <操作>|player <玩家>]"));
            return;
        }
        plugin.history().execute(() -> {
            try {
                List<String> entries = plugin.history().matching(query.count(), query.type(), query.player());
                reply(sender, plugin.message("history.header", "&b最近操作 &8· &f%COUNT% 条")
                        .replace("%COUNT%", String.valueOf(entries.size())));
                if (entries.isEmpty()) reply(sender, plugin.message("history.empty", "&7暂无记录"));
                else entries.forEach(line -> reply(sender, "&8" + line));
            } catch (RuntimeException failed) {
                reply(sender, plugin.message("history.failed", "&c读取操作记录失败"));
                plugin.getLogger().log(java.util.logging.Level.WARNING, "读取操作记录失败", failed);
            }
        });
    }

    private void stats(CommandSender sender, String[] args, boolean export) {
        int days = 30;
        if (args.length > 2) {
            raw(sender, "&7用法：/vc " + (export ? "export" : "trend") + " [1-365]");
            return;
        }
        if (args.length == 2) {
            try { days = Integer.parseInt(args[1]); }
            catch (NumberFormatException invalid) { raw(sender, "&7天数必须是 1-365"); return; }
        }
        if (days < 1 || days > 365) { raw(sender, "&7天数必须是 1-365"); return; }
        int requestedDays = days;
        plugin.history().execute(() -> {
            try {
                if (export) {
                    reply(sender, "&7统计已导出：&f" + plugin.history().exportDailyCsv(requestedDays).getFileName());
                } else {
                    var rows = plugin.history().dailySweeps(requestedDays);
                    if (rows.isEmpty()) { reply(sender, "&7所选时间内没有清扫记录"); return; }
                    rows.forEach(row -> reply(sender, "&8" + row.date() + " &7处理目标 &f" + row.removed()
                            + " &7· 回收掉落物 &f" + row.groups() + " &7组 · 耗时 &f"
                            + String.format(Locale.ROOT, "%.2f", row.durationMillis() / 1000.0) + " 秒"));
                }
            } catch (RuntimeException failed) {
                reply(sender, "&c统计操作失败");
                plugin.getLogger().log(java.util.logging.Level.WARNING, "统计操作失败", failed);
            }
        });
    }

    private void sweepProgress(CommandSender sender) {
        var p=plugin.sweeper().progress();
        reply(sender,"&7清扫：&f"+(p.active()?(p.paused()?"已暂停":"进行中"):"空闲")+" &8· &7区块 &f"+p.completedChunks()+"/"+p.totalChunks()
                +" &8· &7批量 &f"+p.batch()+" &8· &7待完成任务 &f"+p.pendingTasks());
    }

    private void report(CommandSender sender) {
        reply(sender, plugin.hint("status.header", "&b虚空清理 &8· &7状态"));
        if(plugin.sweeper().active())sweepProgress(sender);
        reply(sender, line("status.sweep", "下次清扫", plugin.sweepTimer().leftText()));
        reply(sender, plugin.sweeper().hasResult() ? plugin.sweepSummary()
                : plugin.hint("status.no-result", "&7本次启动尚无清扫结果"));
        reply(sender, "&7垃圾箱 &f" + plugin.trash().itemCount() + " &7件 &8· &f/vc trash");
        reply(sender, "&7下次清空垃圾箱 &f" + (plugin.settings().pace().trashClearEnabled() ? plugin.trashTimer().leftText() : "已关闭"));
    }

    private String line(String key, String name, String value) {
        return plugin.hint(key, "&7%NAME%：&f%VALUE%").replace("%NAME%", name).replace("%VALUE%", value);
    }

    private void reload(CommandSender sender) {
        Scheduler.global(plugin, () -> doReload(sender));
    }

    private void doReload(CommandSender sender) {
        try {
            plugin.reload();
        } catch (RuntimeException broken) {
            reply(sender, "&c重载失败：" + broken.getMessage());
            return;
        }
        reply(sender, plugin.message("general.reloaded", "&f配置已重载"));
    }

    static int helpPage(String[] args, boolean admin) {
        if (args.length > 2) throw new IllegalArgumentException("Help usage");
        int page = args.length < 2 ? 1 : Integer.parseInt(args[1]);
        if (page < 1 || page > (admin ? 3 : 1)) throw new IllegalArgumentException("Invalid help page");
        return page;
    }

    private void help(CommandSender sender, int page) {
        var messages = plugin.settings();
        // Explicit old custom help (including an empty list) remains the administrator's choice.
        if (admin(sender) && messages.hasMessage("help.lines")) {
            raw(sender, single(messages.msgLines("help.header", List.of("&b虚空清理 &8· &7指令"))));
            for (String line : net.voidclear.v2.config.Settings.trim(messages.msgLines("help.lines", List.of()), '[', ']'))
                raw(sender, line);
        } else {
            CommandHelp.lines(messages, admin(sender), page).forEach(sender::sendMessage);
        }
    }

    /* ------------------------------------------------------------------ */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = admin(sender);
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : SUBS) {
                if ((admin || sub.equals("trash") || sub.equals("help")) && sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help"))
            return (admin ? List.of("1", "2", "3") : List.of("1")).stream()
                    .filter(page -> page.startsWith(args[1])).toList();
        if(args[0].equalsIgnoreCase("trash")) {
            List<String> values=List.of();
            if(args.length==2) values=admin?List.of("search","category","clear"):List.of("search","category");
            else if(args.length==3 && args[1].equalsIgnoreCase("category")) values=net.voidclear.v2.listener.TrashFilter.categories();
            else if(args.length==3 && args[1].equalsIgnoreCase("clear") && admin) values=List.of("confirm");
            String prefix=args[args.length-1].toLowerCase(Locale.ROOT);
            return values.stream().filter(value->value.startsWith(prefix)).toList();
        }
        if (!admin) { return List.of(); }
        if(args[0].equalsIgnoreCase("sweep") && args.length==2)
            return List.of("pause","resume","progress").stream().filter(v->v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args[0].equalsIgnoreCase("preview") && args.length==2)
            return Bukkit.getWorlds().stream().map(World::getName).filter(name->name.startsWith(args[1])).toList();
        if (args[0].equalsIgnoreCase("receipt") && args.length == 3)
            return List.of("confirm").stream().filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if (args[0].equalsIgnoreCase("diagnose") && args.length == 2)
            return List.of("export").stream().filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args[0].equalsIgnoreCase("audit")) {
            List<String> values = List.of();
            if (args.length == 2) values = List.of("1", "5", "10", "20", "type", "player");
            else if (args.length == 3 && args[1].matches("[0-9]+")) values = List.of("type", "player");
            else if ((args.length == 3 || args.length == 4) && args[args.length - 2].equalsIgnoreCase("type"))
                values = List.of("DEPOSIT", "WITHDRAW");
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trash")) return List.of("clear");
        if (args.length == 3 && args[0].equalsIgnoreCase("trash") && args[1].equalsIgnoreCase("clear")) return List.of("confirm");
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            String prefix = args[1];
            return List.of("1", "5", "10", "20", "type").stream()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trend") || args[0].equalsIgnoreCase("export"))) return List.of("7", "30", "90", "365");
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (EntityType type : EntityType.values()) {
                if (type.isSpawnable() && type.getKey() != null) {
                    String id = type.getKey().toString();
                    if (id.startsWith(prefix)) {
                        out.add(id);
                    }
                }
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("check")) {
            List<String> out = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                if (world.getName().startsWith(args[2])) {
                    out.add(world.getName());
                }
            }
            return out;
        }
        return List.of();
    }

    static boolean admin(CommandSender sender) {
        return sender.hasPermission("voidclear.admin");
    }
}
