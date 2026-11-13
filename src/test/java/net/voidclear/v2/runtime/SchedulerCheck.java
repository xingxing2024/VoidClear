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
        var server = (Server) Proxy.newProxyInstance(SchedulerCheck.class.getClassLoader(),
                new Class<?>[]{Server.class}, (p, m, a) -> switch (m.getName()) {
                    case "isGlobalTickThread" -> global[0];
                    case "isPrimaryThread" -> true; // Folia 区域线程：不能误当全局线程。
                    case "getGlobalRegionScheduler" -> scheduler;
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
            System.out.println("SchedulerCheck: ALL OK");
        } finally {
            field.set(null, previous);
        }
    }
}
