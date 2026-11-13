package net.voidclear.v2.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DatabaseTasksCheck {
    public static void main(String[] args) throws Exception {
        var order = Collections.synchronizedList(new ArrayList<Integer>());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        DatabaseTasks tasks = new DatabaseTasks();
        var first = tasks.submit(() -> {
            assert !Thread.currentThread().getName().equals("main");
            entered.countDown();
            try { assert release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException cut) { throw new IllegalStateException(cut); }
            order.add(1);
            return 1;
        });
        assert entered.await(5, TimeUnit.SECONDS);
        var failed = tasks.submit(() -> { throw new IllegalStateException("injected"); });
        var last = tasks.submit(() -> { order.add(2); return 2; });
        assert tasks.running() == 1 && tasks.queued() == 2 : "Queue metrics must include blocked and waiting work";
        assert !first.isDone() && !last.isDone() && order.isEmpty();
        release.countDown();
        tasks.close();
        assert first.join() == 1 && last.join() == 2;
        assert failed.isCompletedExceptionally();
        assert order.equals(java.util.List.of(1, 2));
        assert tasks.submit(() -> 3).isCompletedExceptionally();
        assert tasks.running() == 0 && tasks.queued() == 0 : "Failure or rejection leaked queue metrics";
        System.out.println("DatabaseTasksCheck: ALL OK");
    }
}
