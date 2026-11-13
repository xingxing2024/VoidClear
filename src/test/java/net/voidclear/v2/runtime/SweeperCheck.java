package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Settings;
import net.voidclear.v2.rule.Decide;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分批调度的离线自检。{@link FakeSweeper} 只覆盖两处：{@link Sweeper#collect} 提供固定的区块列表，
 * {@link Sweeper#schedule} 决定「这块排到哪跑」。派发、计数、「下一拍再派」、收尾都是产品代码。
 *
 * <p>三个调度形态，正好对应真实服务的差异：</p>
 * <ul>
 *   <li>Paper：{@code onChunk} 当场同步执行（回调里再排下一批），批次之间仍要隔一拍；</li>
 *   <li>Folia：{@code onChunk} 当场执行 / 抛错（和真实 Folia 一样，只在本线程同步跑，跨线程直接抛）；</li>
 *   <li>停用：调度器拒收（{@code onChunk}/{@code once}/{@code later} 返回 false）。</li>
 * </ul>
 *
 * <pre>java -ea -cp "target/classes;target/test-classes;paper-api" net.voidclear.v2.runtime.SweeperCheck</pre>
 */
public final class SweeperCheck {

    private SweeperCheck() {
    }

    /** 替身：固定区块列表；schedule 原样复用产品代码的回调（扫 + 结算），只决定它何时跑。 */
    private static final class FakeSweeper extends Sweeper {
        final List<Chunk> chunks = new ArrayList<>();
        final Map<Chunk, Integer> ran = new HashMap<>();
        /** 下一拍队列：不该跑的活也留在这里，测试会检查它没被执行。 */
        final ArrayDeque<Runnable> next = new ArrayDeque<>();
        int scheduled;

        /** true = Paper（同步跑）；false = Folia（下一拍队列）。 */
        final boolean sync;

        FakeSweeper(Scheduler scheduler, Settings settings, boolean sync) {
            super(settings.pace(), settings.policy(), scheduler, new Decide(settings.policy()));
            this.sync = sync;
        }

        @Override
        protected List<Chunk> collect(List<World> worlds) {
            return chunks;
        }

        /** onChunk 替身造出来的任务：只结算，不判断停用屏障（真实回调在 schedule 里）。 */
        @Override
        protected boolean schedule(Chunk chunk) {
            scheduled++;
            Runnable task = () -> {
                ran.merge(chunk, 1, Integer::sum);
                try {
                    work(chunk).step();
                } finally {
                    settle();
                }
            };
            if (!sync) {
                next.add(task);
                return true;
            }
            // 与真实 Folia 一致：同一个线程里当场跑任务，跨线程调用直接抛错
            task.run();
            return true;
        }
    }

    /** 调度器替身：folia=true 的区域 onChunk 当场跑，否则挂到 sweeper 的下一拍队列；once 立即执行；later 进下一拍队列。 */
    private static Scheduler scheduler(FakeSweeper[] holder, boolean reject, boolean folia) {
        return (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onChunk" -> {
                        if (reject) {
                            yield false;
                        }
                        if (folia) {
                            ((Runnable) args[3]).run();
                        } else {
                            holder[0].next.add((Runnable) args[3]);
                        }
                        yield true;
                    }
                    case "once" -> {
                        if (reject) {
                            yield false;
                        }
                        ((Runnable) args[0]).run();
                        yield true;
                    }
                    case "later" -> {
                        if (reject) {
                            yield false;
                        }
                        holder[0].next.add((Runnable) args[1]);
                        yield true;
                    }
                    case "running" -> !reject;
                    case "folia" -> folia;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeScheduler";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
    }

    public static void main(String[] args) {
        foliaBatches();
        foliaRejectsCrossThreadChunk();
        paperSync();
        emptyAndRejected();
        throwingChunk();
        failedDispatch();
        stopBarrier();
        stopWaitsForRunningTask();
        stopSurvivesInterrupt();
        stopSkipsQueuedTask();
        concurrentBatchFinishesOnce();
        System.out.println("SweeperCheck: ALL OK");
    }

    /**
     * 真 Folia：{@code RegionScheduler}/Folia API 的区块方法只在本已拥有的线程上同步跑；
     * 全局限定的 {@code onChunk} 从全局线程调用会直接抛错（Canvas 26.3 实测）。
     * 这里断言产品代码遇到这种抛错不会让整轮卡死。
     */
    private static void foliaRejectsCrossThreadChunk() {
        List<Runnable> next = new ArrayList<>();
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onChunk" -> throw new IllegalStateException("Thread failed main thread check: Cannot retrieve chunk");
                    case "once" -> { ((Runnable) args[0]).run(); yield true; }
                    case "later" -> { next.add((Runnable) args[1]); yield true; }
                    case "running" -> true;
                    case "folia" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "CrossRegionScheduler";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
        Settings settings = batchTwo();
        Sweeper sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler,
                new Decide(settings.policy())) {
            @Override protected List<Chunk> collect(List<World> worlds) {
                return List.of(chunk(0), chunk(1), chunk(2));
            }
        };
        int[] done = {0};
        sweeper.start(List.of(), () -> done[0]++);
        // 每跑一拍才能结一批：队列空时再补一拍，让「下一批」真正排上
        for (int i = 0; i < 30; i++) {
            List<Runnable> now = new ArrayList<>(next);
            next.clear();
            now.forEach(Runnable::run);
            if (!sweeper.active()) break;
        }
        // 最后一批全部拒收、又没人再排一拍时，产品代码会当场收尾
        if (sweeper.active() && next.isEmpty()) sweeper.settle();
        assert done[0] == 1 && !sweeper.active() : "区域调度器拒收后本轮要正常收尾，实际 " + done[0];
        assert sweeper.inFlight() == 0 : "拒收的块不能占在途";
    }

    /** 真并发：区块正在 sweep 里（被 latch 卡住）时报告停用，停用线程必须一直等；放开后才返回。 */
    private static void stopWaitsForRunningTask() {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        List<Runnable> next = new ArrayList<>();
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onChunk" -> { next.add((Runnable) args[3]); yield true; }
                    case "once" -> { ((Runnable) args[0]).run(); yield true; }
                    case "later" -> { next.add((Runnable) args[1]); yield true; }
                    case "running" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "LatchScheduler";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
        Settings settings = batchTwo();
        Sweeper sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler,
                new Decide(settings.policy())) {
            @Override protected List<Chunk> collect(List<World> worlds) {
                return List.of(chunk(0));
            }
            @Override protected ChunkWork work(Chunk chunk) { return () -> {
                entered.countDown();
                try {
                    // 卡在本块结束之前：正是停用最危险的时刻
                    release.await();
                } catch (InterruptedException cut) {
                    Thread.currentThread().interrupt();
                }
             return true; }; }
        };
        int[] done = {0};
        sweeper.start(List.of(), () -> done[0]++);
        assert next.size() == 1 : "开局派一块";

        Thread worker = new Thread(next.remove(0), "block-worker");
        worker.start();
        await(entered, "区块任务应当已经进入 sweep");

        var returned = new java.util.concurrent.atomic.AtomicBoolean();
        boolean[] healthy = {false};
        var ready = new java.util.concurrent.CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            ready.countDown();
            returned.set(sweeper.stopAndAwait(0L));
            healthy[0] = sweeper.inFlight() == 0;
        }, "stopper");
        stopper.start();
        try {
            await(ready, "停用线程应当已开始等待");
            awaitWaiting(stopper);
            // 窗口内：区块卡在 sweep 里，停用线程必须还等着
            assert !returned.get() : "区块还在 sweep 里，停用线程不能提前返回";
            assert sweeper.inFlight() == 1 : "正在跑的块应记 1 个在途，实际 " + sweeper.inFlight();
            assert done[0] == 0 : "交付之前不该有完成回调";
        } finally {
            release.countDown();
            join(worker, "worker cleanup");
            join(stopper, "stopper cleanup");
        }
        assert returned.get() : "区块结束后停用线程应返回 true";
        assert healthy[0] && sweeper.inFlight() == 0 : "停用后计数只能归零，不能为负：" + sweeper.inFlight();
        assert done[0] == 0 && !sweeper.active() : "屏障之后不能有完成回调，实际 " + done[0];
    }

    private static void concurrentBatchFinishesOnce() {
        for (int round = 0; round < 100; round++) {
            var tasks = new ArrayList<Runnable>();
            var continuations = new java.util.concurrent.atomic.AtomicInteger();
            var entered = new java.util.concurrent.CountDownLatch(2);
            var release = new java.util.concurrent.CountDownLatch(1);
            Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                    new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "onChunk" -> { tasks.add((Runnable) args[3]); yield true; }
                        case "once" -> { ((Runnable) args[0]).run(); yield true; }
                        case "later" -> { continuations.incrementAndGet(); yield true; }
                        case "running" -> true;
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            Settings settings = batchTwo();
            var sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler,
                    new Decide(settings.policy())) {
                @Override protected List<Chunk> collect(List<World> worlds) {
                    return List.of(chunk(0), chunk(1), chunk(2));
                }
                @Override protected ChunkWork work(Chunk chunk) { return () -> {
                    entered.countDown();
                    await(release, "release concurrent batch");
                 return true; }; }
            };
            sweeper.start(List.of(), () -> { throw new AssertionError("batch is not final"); });
            Thread first = new Thread(tasks.get(0), "batch-first");
            Thread second = new Thread(tasks.get(1), "batch-second");
            first.start();
            second.start();
            try {
                await(entered, "both chunks must start");
            } finally {
                release.countDown();
                join(first, "first chunk finishes");
                join(second, "second chunk finishes");
            }
            assert continuations.get() == 1 : "concurrent batch must schedule exactly one continuation";
            assert sweeper.inFlight() == 0 : "concurrent delivery count settles";
            sweeper.stop();
        }
    }

    /** 停用线程被中断时，无超时的 stopAndAwait 不能提前返回，且要恢复中断标志。 */
    private static void stopSurvivesInterrupt() {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        List<Runnable> next = new ArrayList<>();
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onChunk" -> { next.add((Runnable) args[3]); yield true; }
                    case "once" -> { ((Runnable) args[0]).run(); yield true; }
                    case "later" -> { next.add((Runnable) args[1]); yield true; }
                    case "running" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "InterruptScheduler";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
        Settings settings = batchTwo();
        Sweeper sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler,
                new Decide(settings.policy())) {
            @Override protected List<Chunk> collect(List<World> worlds) {
                return List.of(chunk(0));
            }
            @Override protected ChunkWork work(Chunk chunk) { return () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException cut) {
                    Thread.currentThread().interrupt();
                }
             return true; }; }
        };
        sweeper.start(List.of(), () -> { });
        Thread worker = new Thread(next.remove(0), "interrupt-worker");
        worker.start();
        await(entered, "区块任务应当已经进入 sweep");

        var returned = new java.util.concurrent.atomic.AtomicBoolean();
        boolean[] restored = {false};
        var ready = new java.util.concurrent.CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            ready.countDown();
            returned.set(sweeper.stopAndAwait(0L));
            restored[0] = Thread.currentThread().isInterrupted();
        }, "interrupt-stopper");
        stopper.start();
        try {
            await(ready, "停用线程应当已开始等待");
            awaitWaiting(stopper);
            stopper.interrupt();
            awaitWaiting(stopper);
            assert !returned.get() : "被中断也不能提前返回";
            assert sweeper.inFlight() == 1 : "区块还在跑，在途应为 1，实际 " + sweeper.inFlight();
        } finally {
            release.countDown();
            join(worker, "worker cleanup");
            join(stopper, "stopper cleanup");
        }
        assert returned.get() : "区块结束后应返回 true";
        assert restored[0] : "中断标志必须在返回前恢复，不能悄悄吃掉";
        assert sweeper.inFlight() == 0 : "计数归零";
    }

    /** 已排队但没开跑的那一块：停用后它照旧会被调度器跑，但必须跳过不动实体。 */
    private static void stopSkipsQueuedTask() {
        List<Runnable> next = new ArrayList<>();
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onChunk" -> { next.add((Runnable) args[3]); yield true; }
                    case "once" -> { ((Runnable) args[0]).run(); yield true; }
                    case "later" -> { next.add((Runnable) args[1]); yield true; }
                    case "running" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "QueuedScheduler";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
        Settings settings = batchTwo();
        Map<Chunk, Integer> swept = new HashMap<>();
        Sweeper sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler,
                new Decide(settings.policy())) {
            @Override protected List<Chunk> collect(List<World> worlds) {
                return List.of(chunk(0), chunk(1));
            }
            @Override protected ChunkWork work(Chunk chunk) { return () -> {
                swept.merge(chunk, 1, Integer::sum);
             return true; }; }
        };
        int[] done = {0};
        sweeper.start(List.of(), () -> done[0]++);
        assert next.size() == 2 && sweeper.inFlight() == 0 : "两块都排着了，但一块都没开跑";

        assert sweeper.stopAndAwait(1000L) : "没有块在跑，wait 不该等";
        assert sweeper.inFlight() == 0 : "没开跑的块不占在途";

        List<Runnable> now = new ArrayList<>(next);
        next.clear();
        now.forEach(Runnable::run);
        assert swept.isEmpty() : "屏障之后跑起来的块必须跳过：" + swept;
        assert sweeper.inFlight() == 0 : "跳过也要把自己的份额结干净：" + sweeper.inFlight();
        assert done[0] == 0 && next.isEmpty() : "不收尾、不续派";
    }

    /** Folia 形态：onChunk 并行排到各自区域，一批全回调完才隔一拍派下一批。 */
    private static void foliaBatches() {
        FakeSweeper[] holder = new FakeSweeper[1];
        Scheduler scheduler = scheduler(holder, false, false);
        Settings settings = batchTwo();
        FakeSweeper sweeper = holder[0] = new FakeSweeper(scheduler, settings, false);
        for (int i = 0; i < 5; i++) {
            sweeper.chunks.add(chunk(i));
        }
        int[] done = {0};
        assert sweeper.start(List.of(), () -> done[0]++) : "第一轮应当开始";
        assert sweeper.scheduled == 2 : "开局只派一批 2 块，实际 " + sweeper.scheduled;

        pump(sweeper);
        assert done[0] == 0 && sweeper.scheduled == 2 : "一批跑完不能在同一拍里派下一批";
        pump(sweeper);
        assert sweeper.scheduled == 4 && done[0] == 0 : "应在下一拍派第二批，实际 " + sweeper.scheduled;
        assert sweeper.ran.size() == 2 : "第二批还没跑";
        pump(sweeper);
        assert sweeper.ran.size() == 4 && done[0] == 0 : "第二批跑完，第三批还没派";
        pump(sweeper);
        assert sweeper.scheduled == 5 && done[0] == 0 : "应派到第 5 块，实际 " + sweeper.scheduled;
        pump(sweeper);
        assert sweeper.ran.keySet().size() == 5
                && sweeper.ran.values().stream().allMatch(n -> n == 1) : "每块应恰好跑一次：" + sweeper.ran;
        assert done[0] == 1 : "完成回调必须且只能一次，实际 " + done[0];
        assert !sweeper.active() : "完成后应处于非活动状态";
        pump(sweeper);
        pump(sweeper);
        assert done[0] == 1 && sweeper.scheduled == 5 : "完成后不能再派/再完成";
    }

    /** Paper 形态：onChunk 同步执行，但批次之间的「下一拍」照样要等。 */
    private static void paperSync() {
        FakeSweeper[] holder = new FakeSweeper[1];
        Scheduler scheduler = scheduler(holder, false, false);
        Settings settings = batchTwo();
        FakeSweeper sweeper = holder[0] = new FakeSweeper(scheduler, settings, true);
        for (int i = 0; i < 5; i++) {
            sweeper.chunks.add(chunk(i));
        }
        int[] done = {0};
        sweeper.start(List.of(), () -> done[0]++);
        assert sweeper.scheduled == 2 && sweeper.ran.size() == 2 && done[0] == 0
                : "Paper 首批应同步跑完、且尚未完成（scheduled=" + sweeper.scheduled + ", ran=" + sweeper.ran + ")";
        pump(sweeper);
        assert sweeper.scheduled == 4 : "下一拍派第二批，实际 " + sweeper.scheduled;
        sweepRemaining(sweeper);
        assert sweeper.ran.size() == 5
                && sweeper.ran.values().stream().allMatch(n -> n == 1) : "每块都应恰好跑一次：" + sweeper.ran;
        assert done[0] == 1 : "完成只一次，实际 " + done[0];
    }

    /** Paper 形态下，之前同步排在队列里的第二批需要逐拍跑完。 */
    private static void sweepRemaining(FakeSweeper sweeper) {
        for (int i = 0; i < 10 && (sweeper.active() || !sweeper.next.isEmpty()); i++) {
            pump(sweeper);
        }
    }

    /** 停用屏障：已排队、没开跑的那一块不再动实体；真回调在停用之后结算也不派下一批、不收尾。 */
    private static void stopBarrier() {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chunks.add(chunk(i));
        }
        List<Runnable> next = new ArrayList<>();
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onChunk" -> { next.add((Runnable) args[3]); yield true; }
                    case "once" -> { ((Runnable) args[0]).run(); yield true; }
                    case "later" -> { next.add((Runnable) args[1]); yield true; }
                    case "running" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "StopScheduler";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
        Settings settings = batchTwo();
        Map<Chunk, Integer> swept = new HashMap<>();
        Sweeper sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler,
                new Decide(settings.policy())) {
            @Override protected List<Chunk> collect(List<World> worlds) {
                return chunks;
            }
            @Override protected ChunkWork work(Chunk chunk) { return () -> {
                swept.merge(chunk, 1, Integer::sum);
             return true; }; }
        };
        int[] done = {0};
        sweeper.start(List.of(), () -> done[0]++);
        assert next.size() == 2 && sweeper.active() : "开局派一批 2 块";

        sweeper.stop();
        assert !sweeper.active() && sweeper.inFlight() == 0 : "停用前没有块在跑";
        sweeper.stop();
        assert !sweeper.active() : "重复停用是空操作";

        for (int round = 0; round < 5; round++) {
            List<Runnable> now = new ArrayList<>(next);
            next.clear();
            now.forEach(Runnable::run);
        }
        assert swept.isEmpty() : "屏障之后不能再扫任何区块：" + swept;
        assert done[0] == 0 : "屏障之后不能有完成回调，实际 " + done[0];
        assert next.isEmpty() : "屏障之后不派下一批、不收尾，下一拍队列应为空";
        assert sweeper.inFlight() == 0 : "跑过的块都减干净了，不能是负数";
        assert sweeper.stopAndAwait(1000L) : "没有块在跑，应当立即返回 true";

        // 停用是永久的
        int[] second = {0};
        assert !sweeper.start(List.of(), () -> second[0]++) : "停用后再开始必须被拒绝";
        assert !sweeper.active() && sweeper.inFlight() == 0 && second[0] == 0 : "拒绝之后什么也不该发生";

        // 拒收（调度器不收）的块：不占在途，但批次份额照样要结掉
        List<Runnable> refused = new ArrayList<>();
        Scheduler refusing = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onChunk" -> false;
                    case "once" -> { ((Runnable) args[0]).run(); yield true; }
                    case "later" -> { refused.add((Runnable) args[1]); yield true; }
                    case "running" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "RefusingScheduler";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
        Sweeper refusingSweeper = new Sweeper(settings.pace(), settings.policy(), refusing,
                new Decide(settings.policy())) {
            @Override protected List<Chunk> collect(List<World> worlds) {
                return chunks;
            }
        };
        int[] refusedDone = {0};
        refusingSweeper.start(List.of(), () -> refusedDone[0]++);
        assert refusingSweeper.inFlight() == 0 : "拒收的块没有开跑，不能记在途";
        for (int i = 0; i < 10; i++) {
            List<Runnable> now = new ArrayList<>(refused);
            refused.clear();
            now.forEach(Runnable::run);
            if (!refusingSweeper.active()) break;
        }
        assert refusedDone[0] == 1 && !refusingSweeper.active()
                : "全部拒收时批次要正常收尾一次，实际 " + refusedDone[0];
        assert refusingSweeper.inFlight() == 0 : "拒收不能把在途减成负数";
        assert refusingSweeper.lastFailures() == chunks.size() : "拒收不能误报空结果成功";
    }

    /** 空世界列表：直接收尾一次，不派任何区块。 */
    private static void emptyAndRejected() {
        FakeSweeper[] holder = new FakeSweeper[1];
        Scheduler live = scheduler(holder, false, false);
        Settings settings = batchTwo();
        FakeSweeper empty = holder[0] = new FakeSweeper(live, settings, false);
        int[] done = {0};
        assert !empty.hasResult();
        assert empty.start(List.of(), () -> done[0]++) : "空列表也应正常开一轮";
        assert done[0] == 1 && empty.scheduled == 0 && !empty.active() : "空列表应当场收尾一次";
        pump(empty);
        assert done[0] == 1 : "空列表不应再回调";
        assert empty.hasResult() && empty.lastFailures() == 0;

        // 停用中：调度器拒收 -> 立刻收尾，不能挂着
        FakeSweeper[] dead = new FakeSweeper[1];
        Scheduler gone = scheduler(dead, true, false);
        FakeSweeper rejected = dead[0] = new FakeSweeper(gone, settings, false);
        rejected.chunks.add(chunk(0));
        int[] stopped = {0};
        rejected.start(List.of(), () -> stopped[0]++);
        assert stopped[0] == 1 && !rejected.active() : "调度器拒收时应在启动这一下收尾一次";
        assert rejected.scheduled == 0 : "拒收的调度器不该被调用 schedule";
        assert rejected.hasResult() && rejected.lastFailures() == 1;
    }

    private static void pump(FakeSweeper sweeper) {
        List<Runnable> now = new ArrayList<>(sweeper.next);
        sweeper.next.clear();
        for (Runnable task : now) {
            task.run();
        }
    }

    private static Settings batchTwo() {
        Settings settings = Settings.blank();
        YamlConfiguration config = new YamlConfiguration();
        config.set("sweep.batch", 2);
        settings.install(config);
        return settings;
    }

    /** 直接走产品 schedule：同步任务抛异常也不能打断后续派发。 */
    private static void throwingChunk() {
        List<Runnable> next = new ArrayList<>();
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "running" -> true;
                    case "once" -> { ((Runnable) args[0]).run(); yield true; }
                    case "onChunk" -> { ((Runnable) args[3]).run(); yield true; }
                    case "later" -> { next.add((Runnable) args[1]); yield true; }
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
        Settings settings = batchTwo();
        int[] attempted = {0};
        Sweeper sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler,
                new Decide(settings.policy())) {
            @Override protected List<Chunk> collect(List<World> worlds) {
                return List.of(chunk(0), chunk(1), chunk(2), chunk(3), chunk(4));
            }
            @Override protected ChunkWork work(Chunk chunk) { return () -> {
                attempted[0]++;
                if (attempted[0] == 1) throw new IllegalStateException("expected test failure");
             return true; }; }
        };
        int[] done = {0};
        sweeper.start(List.of(), () -> done[0]++);
        for (int i = 0; i < 10 && !next.isEmpty(); i++) {
            List<Runnable> now = new ArrayList<>(next);
            next.clear();
            now.forEach(Runnable::run);
        }
        assert attempted[0] == 5 && done[0] == 1 && !sweeper.active()
                : "异常区块不能让后续区块饿死或整轮卡住";
        assert sweeper.lastFailures() == 1;
        sweeper.start(List.of(), () -> done[0]++);
        for (int i = 0; i < 10 && !next.isEmpty(); i++) {
            List<Runnable> now = new ArrayList<>(next); next.clear(); now.forEach(Runnable::run);
        }
        assert done[0] == 2 && sweeper.lastFailures() == 0 : "上轮错误带入下一轮";
    }

    private static void failedDispatch() {
        for (String mode : List.of("collect", "continuation", "completion", "start")) {
            int[] once = {0}, done = {0};
            Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                    new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "running" -> true;
                        case "once" -> {
                            once[0]++;
                            if (mode.equals("start") || mode.equals("completion") && once[0] > 1)
                                throw new IllegalStateException("injected dispatch failure");
                            if (mode.equals("continuation") && once[0] > 1) yield false;
                            ((Runnable) args[0]).run(); yield true;
                        }
                        case "later" -> false;
                        case "onChunk" -> { ((Runnable) args[3]).run(); yield true; }
                        default -> method.getReturnType().isPrimitive() ? false : null;
                    });
            Settings settings = batchTwo();
            Sweeper sweeper = new Sweeper(settings.pace(), settings.policy(), scheduler, new Decide(settings.policy())) {
                @Override protected List<Chunk> collect(List<World> worlds) {
                    if (mode.equals("collect")) throw new IllegalStateException("injected collect failure");
                    return mode.equals("continuation") ? List.of(chunk(0), chunk(1), chunk(2)) : List.of(chunk(0));
                }
                @Override protected ChunkWork work(Chunk chunk) { return () -> {  return true; }; }
            };
            assert sweeper.start(List.of(), () -> done[0]++);
            assert !sweeper.active() && done[0] == 1 : "Dispatch failure hung or completed twice: " + mode;
            assert sweeper.hasResult() && sweeper.lastFailures() == 1 : mode;
        }
    }

    /** 空区块替身：清扫/派发都走产品代码，碰不到服务端对象。 */
    private static Chunk chunk(int x) {
        return (Chunk) Proxy.newProxyInstance(SweeperCheck.class.getClassLoader(),
                new Class<?>[]{Chunk.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> x;
                    case "getZ" -> 0;
                    case "getEntities" -> new Entity[0];
                    case "hashCode" -> x;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Chunk(" + x + ")";
                    default -> method.getReturnType().isPrimitive() ? false : null;
                });
    }

    private static void await(java.util.concurrent.CountDownLatch latch, String what) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("timeout: " + what);
        } catch (InterruptedException cut) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted: " + what);
        }
    }

    private static void join(Thread thread, String what) {
        try {
            thread.join(5000);
            if (thread.isAlive()) throw new AssertionError("still running: " + what);
        } catch (InterruptedException cut) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted: " + what);
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) throw new AssertionError("thread never waited: " + thread.getState());
            Thread.onSpinWait();
        }
    }
}
