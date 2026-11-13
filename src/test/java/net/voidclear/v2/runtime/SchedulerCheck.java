package net.voidclear.v2.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;

/** 全局线程原地执行，区域线程只排队，任务异常不能导致重复执行。 */
public final class SchedulerCheck {
    public static void main(String[] args) throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        var previous = field.get(null);
        boolean[] global = {false};
        var queued = new ArrayDeque<Runnable>();
        var scheduler = (GlobalRegionScheduler) Proxy.newProxyInstance(SchedulerCheck.class.getClassLoader(),
                new Class<?>[]{GlobalRegionScheduler.class}, (p, m, a) -> {
                    if (m.getName().equals("execute")) {
                        queued.add((Runnable) a[1]);
                        return null;
                    }
                    throw new AssertionError(m.getName());
                });
        var delayed = new java.util.TreeMap<Long, java.util.ArrayDeque<Runnable>>();
        long[] tick = {0};
        var paper = (org.bukkit.scheduler.BukkitScheduler) Proxy.newProxyInstance(SchedulerCheck.class.getClassLoader(),
                new Class<?>[]{org.bukkit.scheduler.BukkitScheduler.class}, (p,m,a) -> {
                    long delay;
                    if(m.getName().equals("runTask")) delay=0;
                    else if(m.getName().equals("runTaskLater")) delay=(Long)a[2];
                    else throw new AssertionError(m.getName());
                    delayed.computeIfAbsent(tick[0]+delay, key->new java.util.ArrayDeque<>()).add((Runnable)a[1]);
                    return null;
                });
        var server = (Server) Proxy.newProxyInstance(SchedulerCheck.class.getClassLoader(),
                new Class<?>[]{Server.class}, (p, m, a) -> switch (m.getName()) {
                    case "isGlobalTickThread" -> global[0];
                    case "isPrimaryThread" -> true; // Folia 区域线程：不能误当全局线程。
                    case "getGlobalRegionScheduler" -> scheduler;
                    case "getScheduler" -> paper;
                    default -> throw new AssertionError(m.getName());
                });
        try {
            field.set(null, server);
            int[] runs = {0};
            Scheduler.global(null, () -> runs[0]++);
            assert runs[0] == 0 && queued.size() == 1 : "region work must queue";
            global[0] = true;
            queued.remove().run();
            Scheduler.global(null, () -> runs[0]++);
            assert runs[0] == 2 && queued.isEmpty() : "global work runs inline once";
            var failure = new IllegalStateException("task failure");
            try {
                Scheduler.global(null, () -> { runs[0]++; throw failure; });
                throw new AssertionError("task failure swallowed");
            } catch (IllegalStateException expected) {
                assert expected == failure;
            }
            assert runs[0] == 3 && queued.isEmpty() : "failed task must not be rescheduled";
            var adapter = Scheduler.create(null);
            int[] playerRuns = {0};
            adapter.playerNextTick(null, () -> {
                playerRuns[0]++;
                adapter.playerNextTick(null, () -> playerRuns[0]++);
            });
            for(int expected=0;expected<=2;expected++) {
                tick[0]=expected;
                int budget=10;
                while(!delayed.isEmpty() && delayed.firstKey()<=tick[0] && budget-->0) {
                    var work=delayed.pollFirstEntry().getValue();
                    while(!work.isEmpty()) work.remove().run();
                }
                assert playerRuns[0]==expected : "player retries must advance to a distinct tick";
            }
            System.out.println("SchedulerCheck: ALL OK");
        } finally {
            field.set(null, previous);
        }
    }
}
