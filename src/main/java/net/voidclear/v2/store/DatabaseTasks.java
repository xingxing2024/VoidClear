package net.voidclear.v2.store;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Ordered JDBC work; callbacks schedule Bukkit effects back onto their owner thread. */
public final class DatabaseTasks implements AutoCloseable {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "VoidClear-database");
        thread.setDaemon(true);
        return thread;
    });

    public int queued() { return worker.getQueue().size(); }
    public int running() { return worker.getActiveCount(); }

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        try { return CompletableFuture.supplyAsync(task, worker); }
        catch (java.util.concurrent.RejectedExecutionException stopped) {
            return CompletableFuture.failedFuture(stopped);
        }
    }

    @Override public void close() {
        worker.shutdown();
        boolean interrupted = false;
        while (!worker.isTerminated()) {
            try { worker.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException cut) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
