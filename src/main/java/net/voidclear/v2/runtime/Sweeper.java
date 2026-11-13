package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Sections;
import net.voidclear.v2.rule.Decide;
import net.voidclear.v2.target.Tags;
import net.voidclear.v2.store.TrashStore;
import net.voidclear.v2.listener.SweepRecovery;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 一轮清扫：遍历已加载区块，活着的东西直接移除，掉落物交给垃圾箱。
 *
 * <p>每个区块派成独立的任务（Folia 按区域并行，Paper 依次回主线程）。
 * 一批派完、整批都回调之后才回全局调度器派下一批 —— 不递归、不一次把队列塞满；
 * 无论哪个线程完成最后一块，{@link #complete()} 只认一次。</p>
 *
 * <p>线程：{@link #start} 在调度器线程上调；区块任务可能并行，计数与批次用 {@link #tally} 串行。</p>
 *
 * <p>停用屏障（{@link #stop}）：报告停用后不再动实体、不再派下一批、不再叫完成回调；
 * 停用前已经开跑的区块照常跑完，停用方等它们结束。</p>
 */
public class Sweeper {

    private final Sections.Pace pace;
    private final Sections.Policy policy;
    private final Scheduler scheduler;
    private final Decide decide;
    private final TrashStore trash;
    private final SweepRecovery recovery;

    private final Object tally = new Object();
    private int removed;
    private int collected;
    private long collectedItems;
    private long lastCollectedItems;
    private int lastTotal;
    private int failures;
    private int lastFailures;
    private boolean hasResult;

    private volatile boolean active;
    private volatile Runnable done;
    private int lastCollected;
    private long startedAt;
    private long lastDurationMillis;

    /** 本轮还没派的区块。 */
    private final Deque<Chunk> queue = new ArrayDeque<>();
    /** 本轮已经派到调度器里、还没回调的区块数：归零才考虑派下一批或收尾。 */
    private int issued;
    /** 屏障后「正在执行」的区块数：还在交付掉落物，停用方要等它归零（见 {@link #stop}）。 */
    private int inFlight;
    /** 停用是永久的：此后 {@link #start} 一律拒绝。 */
    private volatile boolean stopped;

    public Sweeper(Sections.Pace pace, Sections.Policy policy, Scheduler scheduler, Decide decide) {
        this(pace, policy, scheduler, decide, null);
    }

    public Sweeper(Sections.Pace pace, Sections.Policy policy, Scheduler scheduler, Decide decide, TrashStore trash) {
        this(pace, policy, scheduler, decide, trash, null);
    }

    public Sweeper(Sections.Pace pace, Sections.Policy policy, Scheduler scheduler, Decide decide, TrashStore trash, SweepRecovery recovery) {
        this.pace = pace;
        this.policy = policy;
        this.scheduler = scheduler;
        this.decide = decide;
        this.trash = trash;
        this.recovery = recovery;
    }

    public boolean active() {
        return active;
    }

    public int removed() {
        synchronized (tally) {
            return removed;
        }
    }

    public int collected() {
        synchronized (tally) {
            return collected;
        }
    }

    /**
     * 开始一轮；上一轮没跑完就忽略本次请求。
     *
     * @return false 表示没开始（上一轮还在跑）
     */
    public boolean start(Runnable finished) {
        return start(Bukkit.getServer().getWorlds(), finished);
    }

    /**
     * 开始一轮；上一轮没跑完、或者已经 {@link #stop} 过了，都忽略本次请求。
     *
     * <p>停用是永久状态：停用之后再开一轮，等于在插件正在卸的时候又往区块里塞任务，
     * 那些任务交付的东西落不进库。</p>
     *
     * @return false 表示没开始（上一轮还在跑，或已经停用）
     */
    public boolean start(List<World> worlds, Runnable finished) {
        synchronized (tally) {
            if (active || stopped) {
                return false;
            }
            active = true;
            // 上一轮（若因停用被撤下）留下的份额不能带进新一轮
            issued = 0;
            inFlight = 0;
            queue.clear();
            removed = 0;
            collected = 0;
            collectedItems = 0;
            failures = 0;
            startedAt = System.nanoTime();
            done = finished;
        }
        // 取世界与区块引用必须在全局主线程；玩家点界面进来的调用可能在自己的区域线程
        try {
            if (!scheduler.once(() -> expand(worlds))) {
                failedTask();
                complete();
            }
        } catch (RuntimeException failed) {
            logFailure(failed);
            complete();
        }
        return true;
    }

    /** 每轮从世界拿到的区块列表（自检覆盖它：不碰服务端也能跑完整的分批流程）。 */
    protected List<Chunk> collect(List<World> worlds) {
        List<Chunk> work = new ArrayList<>();
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                work.add(chunk);
            }
        }
        return work;
    }

    /** 派发：在主线程拿世界里的区块引用，然后按批派给各自的区域。 */
    private void expand(List<World> worlds) {
        if (!scheduler.running()) {
            failedTask();
            complete();
            return;
        }
        List<Chunk> work;
        try {
            work = collect(worlds);
        } catch (RuntimeException failed) {
            logFailure(failed);
            complete();
            return;
        }
        synchronized (tally) {
            // 屏障可能在 collect 之前就撤下了本轮：那就一块都不派
            if (!active) {
                return;
            }
            queue.addAll(work);
        }
        if (work.isEmpty()) {
            complete();
            return;
        }
        issue();
    }

    /**
     * 派一批：都在全局 / 主线程上调用。一批的最后一块回调时再回到全局调度器派下一批，
     * 所以派发链条是「回调 → 全局 → 下一批」，没有递归。
     */
    private void issue() {
        List<Chunk> batch = new ArrayList<>();
        synchronized (tally) {
            if (!active) {
                return;
            }
            while (batch.size() < pace.batch() && !queue.isEmpty()) {
                batch.add(queue.poll());
            }
            issued = batch.size();
        }
        if (batch.isEmpty()) {
            return;
        }
        int refused = 0;
        for (Chunk chunk : batch) {
            try {
                if (!schedule(chunk)) { failedTask(); refused++; }
            } catch (RuntimeException rejected) {
                logFailure(rejected);
                refused++;
            }
        }
        // 拒收的块不会回调，也不占 inFlight，但必须结掉各自的批次份额。
        for (int i = 0; i < refused; i++) finishedOne(true);
    }

    /**
     * 在区块所属区域跑一块；调度器不收（已停用）返回 false。
     *
     * <p>记账与交付都在这一个回调和它的 finally 里：进来时本轮还活动才 {@code inFlight++}（开始交付），
     * finally 恰好 {@code inFlight--}（交付完了）。停用只影响「要不要 sweep」，不影响「跑没跑完」。</p>
     */
    protected boolean schedule(Chunk chunk) {
        return scheduler.onChunk(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
            boolean live;
            synchronized (tally) {
                live = active;
                if (live) {
                    inFlight++;
                }
            }
            try {
                // 屏障之后的区块：不动实体，它也没有东西要交付
                if (live) {
                    sweep(chunk);
                }
            } catch (RuntimeException failed) {
                logFailure(failed);
            } finally {
                end(live);
            }
        });
    }

    /** 一个已排上的区块走完了：在途只减自己记过的那一份，批次无论停用与否都要结掉。 */
    private void end(boolean counted) {
        synchronized (tally) {
            if (counted) {
                inFlight--;
            }
            // 交付完了才叫醒等屏障的人；判断与 notify 都在锁里，不会漏
            if (inFlight <= 0) {
                tally.notifyAll();
            }
        }
        finishedOne(counted);
    }

    /** 单个区块：生物移除，掉落物转入内存垃圾箱并排队保存。 */
    protected void sweep(Chunk chunk) {
        if (!scheduler.running()) {
            failedTask();
            return;
        }
        if (!chunk.isLoaded()) {
            return;
        }
        Entity[] entities = chunk.getEntities();
        if (entities.length == 0) {
            return;
        }
        // 只看这个区块所在世界的设置：世界例外优先，其次全局默认
        String name = chunk.getWorld().getName();
        boolean drops = policy.drops(name);
        boolean creatures = policy.creatures(name);
        if (!drops && !creatures) {
            return;
        }
        // floor：区块里待清的东西少于它就先不动，攒到下一轮一起清（省得每轮都为一块骨头扫全服）
        int floor = pace.floor();
        if (floor > 0) {
            int candidates = 0;
            for (Entity entity : entities) {
                if (clearable(decide, policy, entity)) {
                    candidates++;
                }
            }
            if (candidates < floor) {
                return;
            }
        }
        int hits = 0;
        List<TrashStore.SweepDeposit> deposits = new ArrayList<>();
        try {
            for (Entity entity : entities) {
                if (!clearable(decide, policy, entity)) {
                    continue;
                }
                if (entity instanceof Item item) {
                    if (item.getPersistentDataContainer().has(new org.bukkit.NamespacedKey("voidclear", "player_drop_operation"))) continue;
                    if (recovery != null && !recovery.reconcile(item)) continue;
                    var stack = item.getItemStack().clone();
                    // Deposit as much as the real chest accepts; any remainder stays as a world drop.
                    if (trash == null || Decide.empty(stack)) continue;
                    if (recovery != null) {
                        long beforeSequence = recovery.sequence(item);
                        deposits.add(new TrashStore.SweepDeposit(item.getUniqueId(), beforeSequence, stack,
                                (remainder, sequence) -> {
                                    if (trash.mysql() && (!Bukkit.isOwnedByCurrentRegion(item) || !item.isValid()
                                            || recovery.sequence(item) != beforeSequence
                                            || !java.util.Arrays.equals(item.getItemStack().serializeAsBytes(), stack.serializeAsBytes())))
                                        throw new IllegalStateException("清扫来源等待期间已变化，保留凭据");
                                    recovery.applied(item, remainder, sequence);
                                    countCollectedItems(stack, remainder);
                                }));
                        continue;
                    }
                    if (!trash.deposit(stack, remainder -> {
                        if (remainder == null) item.remove(); else item.setItemStack(remainder);
                        countCollectedItems(stack, remainder);
                    })) continue;
                    synchronized (tally) {
                        collected++;
                    }
                    hits++;
                } else {
                    entity.remove();
                    hits++;
                }
            }
            // One transaction per chunk instead of one per dropped item.
            if (!deposits.isEmpty()) {
                if (trash.mysql() && recovery != null) {
                    synchronized (tally) { issued++; }
                    recovery.depositAsync(chunk, deposits, (accepted, failed) -> {
                        synchronized (tally) {
                            if (active) {
                                collected += accepted; removed += accepted;
                                if (failed) failures++;
                            }
                        }
                        finishedOne(true);
                    });
                    return;
                }
                int accepted = trash.depositEntities(deposits);
                synchronized (tally) {
                    collected += accepted;
                }
                hits += accepted;
            }
        } finally {
            if (hits > 0) {
                synchronized (tally) {
                    removed += hits;
                }
            }
        }
    }

    private void countCollectedItems(org.bukkit.inventory.ItemStack before, org.bukkit.inventory.ItemStack remainder) {
        synchronized (tally) {
            if (active) collectedItems += before.getAmount() - (remainder == null ? 0 : remainder.getAmount());
        }
    }

    public long lastCollectedItems() { synchronized (tally) { return lastCollectedItems; } }

    private void logFailure(RuntimeException failed) {
        failedTask();
        java.util.logging.Logger.getLogger("VoidClear").log(java.util.logging.Level.WARNING,
                "区块清扫失败，已跳过并继续其余区块", failed);
    }

    private void failedTask() { synchronized (tally) { if (active) failures++; } }
    public int lastFailures() { synchronized (tally) { return lastFailures; } }
    public boolean hasResult() { synchronized (tally) { return hasResult; } }

    /** 上一轮处理的目标数，包含仅部分入箱的掉落物实体。 */
    public int lastTotal() {
        synchronized (tally) {
            return lastTotal;
        }
    }

    public int lastCollected() {
        synchronized (tally) {
            return lastCollected;
        }
    }

    public long lastDurationMillis() {
        synchronized (tally) {
            return lastDurationMillis;
        }
    }

    public long durationMillis() {
        synchronized (tally) {
            return active ? java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    : lastDurationMillis;
        }
    }

    public static boolean clearable(Decide decide, Sections.Policy policy, Entity entity) {
        return skipReason(decide, policy, entity) == null;
    }

    /** Shared read-only eligibility for preview and sweep; null means a current candidate. */
    public static String skipReason(Decide decide, Sections.Policy policy, Entity entity) {
        if (!entity.isValid()) return "实体已失效";
        if (Tags.has(entity)) return "保护标记";
        String world = entity.getWorld().getName();
        if (entity instanceof ExperienceOrb) return policy.experience(world) ? null : "世界类别关闭";
        if (entity instanceof Item item) {
            if (!policy.drops(world)) return "世界类别关闭";
            if ((long) item.getTicksLived() < policy.dropProtectSeconds() * 20L) return "新掉落保护";
            if (item.getPersistentDataContainer().has(new org.bukkit.NamespacedKey("voidclear", "player_drop_operation")))
                return "交易托管来源";
            return decide.sweeps(item) ? null : "物品豁免";
        }
        if (entity instanceof org.bukkit.entity.Animals && !policy.animals(world)
                || Decide.isMonster(entity) && !policy.monsters(world) || !policy.creatures(world))
            return "世界类别关闭";
        if (entity instanceof org.bukkit.entity.Player) return "玩家";
        if (entity instanceof org.bukkit.entity.Boss) return "Boss";
        return decide.sweeps(entity) ? null : "实体保留规则";
    }

    /**
     * 一块的批次份额结束（任意区域线程）：已排出的区块全部回调完才算一批结束。
     * 还有下一批就回全局调度器、至少隔一拍再派（不递归、不在同一拍里跑完）；没下一批就收尾。
     *
     * <p>{@code issued} 在这里减，{@code inFlight} 不在这里碰 —— 它只由 {@link #end} 按实体对加减。</p>
     *
     * @param live 这块进来时本轮还是活动的；false 表示屏障之后的回调，只结批次
     */
    private void finishedOne(boolean live) {
        boolean nextBatch;
        boolean finished;
        synchronized (tally) {
            issued--;
            // 屏障之后：不派下一批、不收尾、不叫完成回调。停用方已认领落库
            finished = live && active && issued <= 0 && queue.isEmpty();
            nextBatch = live && active && !finished && issued <= 0;
        }
        if (finished) {
            try {
                if (!scheduler.once(this::complete)) complete();
            } catch (RuntimeException failed) {
                logFailure(failed);
                complete();
            }
        } else if (nextBatch) {
            // 排不上（正在停用，或调度器限定了线程）：补一次同步派发，否则本轮永远卡在中间
            try {
                if (!scheduler.later(1L, this::issue) && !scheduler.once(this::issue)) {
                    failedTask();
                    complete();
                }
            } catch (RuntimeException failed) {
                logFailure(failed);
                complete();
            }
        }
    }

    /**
     * 一块的结算（自检替身自己造任务时用：它没有走 {@link #schedule} 的记账）。
     * 只结批次份额，不动 {@code inFlight}；产品回调走 {@link #end}。
     */
    protected void settle() {
        finishedOne(true);
    }

    /**
     * 停用屏障（永久状态，之后 {@link #start} 一律拒绝，已排队但未跑的任务见 {@link #active} 为假直接跳过）。
     *
     * <p>报告停用后不再动实体、不再派下一批、不再叫完成回调；停用前已经开跑的区块照常跑完。
     * 在 {@code scheduler.shutdown()} 之前调用 {@link #stopAndAwait(long)}。</p>
     *
     * <p>不等任何没开始的任务：调度器自己排着的队列可能永远不会再跑，等它就是等死。
     * 这里也不清零 {@code inFlight}：只有真跑完的区块自己会减。</p>
     */
    public void stop() {
        synchronized (tally) {
            if (stopped) {
                return;
            }
            stopped = true;
            active = false;
            done = null;
            queue.clear();
            if (inFlight <= 0) {
                tally.notifyAll();
            }
        }
    }

    public boolean stopped() {
        return stopped;
    }


    /** 还在交付的区块数（只计已开跑、尚未走完 finally 的）。 */
    public int inFlight() {
        synchronized (tally) {
            return inFlight;
        }
    }

    /**
     * 停用并等已开跑的区块交付完（最多 {@code millis} 毫秒，≤0 表示一直等）。
     *
     * <p>等的是 {@link #inFlight} 归零（{@code wait}/{@code notify}），不是调度队列：
     * 它们在锁外各自跑完、交付、再回来减计数。调用方必须站在不会被这些区块依赖的线程上
     * （停用线程 / 全局线程），不能在区块任务里调。</p>
     *
     * @return true 表示现在没有任何区块还在交付，可以落库
     */
    public boolean stopAndAwait(long millis) {
        stop();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis));
        boolean cut = false;
        synchronized (tally) {
            while (inFlight > 0) {
                // 中断不能让这里提前返回，否则调用方会在交付没进库时就落库；
                // 无超时就一直等，有超时就按剩余时间等，中断标志留到返回前恢复
                if (millis > 0) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0L) {
                        break;
                    }
                    cut |= waitFor(Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left)));
                } else {
                    cut |= waitFor(0L);
                }
            }
            if (inFlight > 0) {
                if (cut) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }
        if (cut) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * 在 {@link #tally} 上等一拍（0 = 一直等到有人 notify）；反复被中断也照旧等。
     * 调用方持锁。
     *
     * @return 期间是否被中断过
     */
    private boolean waitFor(long millis) {
        boolean cut = false;
        try {
            if (millis <= 0L) {
                tally.wait();
            } else {
                tally.wait(millis);
            }
        } catch (InterruptedException interrupted) {
            cut = true;
        }
        return cut;
    }

    /** 只收一次尾：迟到的回调或重复的完成信号都不会再触发第二次。 */
    private void complete() {
        Runnable callback;
        synchronized (tally) {
            if (!active) {
                return;
            }
            active = false;
            lastTotal = removed;
            lastCollected = collected;
            lastCollectedItems = collectedItems;
            lastFailures = failures;
            hasResult = true;
            lastDurationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            callback = done;
            done = null;
            queue.clear();
        }
        if (callback != null) {
            callback.run();
        }
    }
}
