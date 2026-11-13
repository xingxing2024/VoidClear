package net.voidclear.v2.store;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class TrashStoreCheck {
    public static void main(String[] args) throws Exception {
        var file = Files.createTempDirectory(java.nio.file.Path.of("target"), "trash-check-").resolve("trash.db");
        Logger logger = Logger.getLogger("TrashCheck");
        TrashStore store = TrashStore.sqlite(file, logger);
        assert store.size() == 0;
        byte[] data = {1, 2, 3};
        AtomicInteger removed = new AtomicInteger();
        assert store.collect(data, removed::incrementAndGet);
        data[0] = 9;
        assert removed.get() == 1;
        assert store.page(0, 28).getFirst().bytes()[0] == 1;
        var originalPage = store.page(0, 28);
        assert store.isCurrentPage(0, 28, originalPage);
        assert !store.isCurrentPage(-1, 28, originalPage);
        assert !store.isCurrentPage(0, 0, originalPage);
        assert store.isCurrentPage(Integer.MAX_VALUE, Integer.MAX_VALUE, List.of());
        assert store.page(Integer.MAX_VALUE, Integer.MAX_VALUE).isEmpty();
        assert TrashStore.samePage(originalPage, List.of(new TrashStore.Entry(originalPage.getFirst().id(), new byte[]{1, 2, 3})));
        assert !TrashStore.samePage(originalPage, List.of(new TrashStore.Entry(-1, new byte[]{1, 2, 3})));
        assert !TrashStore.samePage(originalPage, List.of(new TrashStore.Entry(originalPage.getFirst().id(), new byte[]{1, 2, 4})));
        byte[] exposed = originalPage.getFirst().bytes(); exposed[0] = 100;
        assert store.isCurrentPage(0, 28, originalPage) : "Read-only comparison exposed mutable payloads";
        long id = store.page(0, 28).getFirst().id();
        assert store.claim(id, current -> new byte[]{4}); // Partial inventory acceptance.
        assert store.page(0, 28).getFirst().bytes()[0] == 4;
        assert !store.isCurrentPage(0, 28, originalPage) : "Changed payload was treated as current";
        assert store.claim(id, current -> current); // Full inventory keeps the entry.
        try { store.claim(id, current -> { throw new IllegalStateException("codec"); }); }
        catch (IllegalStateException expected) { }
        assert store.size() == 1;

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        Runnable claim = () -> {
            try { start.await(); } catch (InterruptedException cut) { throw new AssertionError(cut); }
            if (store.claim(id, current -> { claims.incrementAndGet(); return null; })) { }
        };
        Thread a = new Thread(claim), b = new Thread(claim);
        a.start(); b.start(); start.countDown(); a.join(); b.join();
        assert claims.get() == 1 && store.size() == 0;
        assert store.collect(new byte[]{5}, () -> { });
        List<Long> confirmed = store.ids();
        assert store.collect(new byte[]{6}, () -> { });
        assert store.clear(confirmed) == 1 && store.size() == 1;
        try { store.collect(new byte[]{7}, () -> { throw new IllegalStateException("remove failed"); }); }
        catch (IllegalStateException expected) { }
        assert store.size() == 2 : "Post-commit failure must retain committed evidence";
        assert store.clear(List.of(store.ids().getLast())) == 1;
        store.close();
        assert !store.collect(new byte[]{8}, () -> { throw new AssertionError("closed"); });
        try (TrashStore restored = TrashStore.sqlite(file, logger)) {
            assert restored.size() == 1;
            assert restored.page(0, 28).getFirst().bytes()[0] == 6;
            assert restored.collect(new byte[]{9}, () -> { });
            assert restored.ids().get(1) > restored.ids().get(0);
        }
        var legacy = file.resolveSibling("trash.yml");
        Files.writeString(legacy, "items: [\n");
        try (TrashStore migration = TrashStore.sqlite(file.resolveSibling("migration.db"), logger)) {
            try { migration.migrateLegacy(legacy); throw new AssertionError("Broken YAML accepted"); }
            catch (IllegalStateException expected) { }
            assert migration.size() == 0;
        }
        assert Files.readString(legacy).equals("items: [\n");
        var capacityFile = file.resolveSibling("capacity.db");
        try (TrashStore full = TrashStore.sqlite(capacityFile, logger)) {
            synchronized (full) {
                var transaction = TrashStore.class.getDeclaredMethod("transaction", java.util.function.Supplier.class);
                transaction.setAccessible(true);
                transaction.invoke(full, (java.util.function.Supplier<Void>) () -> {
                    for (int i = 0; i < TrashStore.CAPACITY; i++) assert full.collect(new byte[]{1}, () -> { });
                    return null;
                });
                assert !full.collect(new byte[]{2}, () -> { throw new AssertionError("Full bin removed entity"); });
                assert full.size() == TrashStore.CAPACITY;
                assert full.page(222, 45).size() == 10;
                assert full.clear(List.of(full.ids().getFirst())) == 1;
                assert full.collect(new byte[]{3}, () -> { });
                assert full.size() == TrashStore.CAPACITY;
            }
        }
        try (TrashStore restored = TrashStore.sqlite(capacityFile, logger)) {
            assert restored.size() == TrashStore.CAPACITY : "Full bin restart lost entries";
        }
        System.out.println("TrashStoreCheck: ALL OK");
    }
}
