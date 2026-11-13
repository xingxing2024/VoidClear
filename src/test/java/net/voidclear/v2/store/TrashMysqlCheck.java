package net.voidclear.v2.store;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** Explicit opt-in check against the isolated port only; never part of offline checks. */
public final class TrashMysqlCheck {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:33317/voidclear_test?useSSL=false&serverTimezone=UTC";
        Logger logger = Logger.getLogger("TrashMysqlCheck");
        try (TrashStore a = TrashStore.mysql(url, "voidclear", "voidclear", "assert_", logger);
             TrashStore b = TrashStore.mysql(url, "voidclear", "voidclear", "assert_", logger)) {
            try (var sql = java.sql.DriverManager.getConnection(url, "voidclear", "voidclear");
                 var rows = sql.createStatement().executeQuery("SHOW INDEX FROM assert_trash_items_player_ops WHERE Key_name='assert_trash_items_ops_lookup'")) {
                for (String column : new String[]{"player_id", "state", "session_id"})
                    assert rows.next() && rows.getString("Column_name").equals(column) : "Player journal lookup index missing";
                assert !rows.next();
            }
            a.clear(a.ids()); b.clear(b.ids());
            var refresh = TrashStore.class.getDeclaredMethod("refreshDatabaseIfSafe");
            refresh.setAccessible(true);
            var cached = TrashStore.class.getDeclaredField("cachedItemCount");
            cached.setAccessible(true);
            cached.set(a, 123L);
            refresh.invoke(a);
            assert cached.get(a).equals(123L) : "Unchanged revision invalidated count cache";
            var stalePage = a.page(0, 45);
            assert b.collect(new byte[]{9}, () -> { });
            assert b.isCurrentPage(0, 45, b.page(0, 45));
            refresh.invoke(a);
            assert a.size() == 1 : "Changed revision was not refreshed";
            assert !a.isCurrentPage(0, 45, stalePage) : "Remote change did not invalidate an old page";
            var publishedPage = a.page(0, 45);
            assert a.isCurrentPage(0, 45, publishedPage);
            assert a.page(Integer.MAX_VALUE, Integer.MAX_VALUE).isEmpty();
            assert a.isCurrentPage(Integer.MAX_VALUE, Integer.MAX_VALUE, java.util.List.of());
            try { publishedPage.clear(); throw new AssertionError("Mutable published page"); }
            catch (UnsupportedOperationException expected) { }
            a.clear(a.ids());
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread one = new Thread(() -> {
                try { start.await(); for (int i = 0; i < 20; i++) assert a.collect(new byte[]{1}, () -> { }); }
                catch (Throwable e) { failed.set(e); }
            });
            Thread two = new Thread(() -> {
                try { start.await(); for (int i = 0; i < 20; i++) assert b.collect(new byte[]{2}, () -> { }); }
                catch (Throwable e) { failed.set(e); }
            });
            one.start(); two.start(); start.countDown(); one.join(); two.join();
            if (failed.get() != null) throw new AssertionError(failed.get());
            try (TrashStore reader = TrashStore.mysql(url, "voidclear", "voidclear", "assert_", logger)) {
                assert reader.size() == 40 : "Concurrent inserts lost items";
                assert reader.ids().stream().distinct().count() == 40 : "Reused IDs";
                assert reader.clear(reader.ids()) == 40;
            }
            assert a.collect(new byte[]{3}, () -> { });
        }
        try (TrashStore reader = TrashStore.mysql(url, "voidclear", "voidclear", "assert_", logger)) {
            assert reader.size() == 1 : "Shutdown republished stale items";
            assert reader.page(0, 45).getFirst().bytes()[0] == 3;
            reader.clear(reader.ids());
        }
        try (TrashStore store = TrashStore.mysql(url, "voidclear", "voidclear", "assert_", logger)) {
            try {
                store.collect(new byte[]{4}, () -> { throw new IllegalStateException("injected post-commit failure"); });
                throw new AssertionError("Post-commit failure was swallowed");
            } catch (IllegalStateException expected) {
                assert expected.getMessage().contains("已提交") : "Must distinguish committed failure";
            }
            assert store.size() == 1 : "Committed cache was rolled back";
        }
        try (TrashStore reader = TrashStore.mysql(url, "voidclear", "voidclear", "assert_", logger)) {
            assert reader.size() == 1 : "Committed data was lost after callback failure";
            assert reader.page(0, 45).getFirst().bytes()[0] == 4;
            reader.clear(reader.ids());
        }
        deltaWrites(url, logger);
        snapshotReaders(url, logger);
        connectionReuse(url, logger);
        var verify = TrashStore.class.getDeclaredMethod("verifyBatch", int[].class);
        verify.setAccessible(true);
        try { verify.invoke(null, (Object) new int[]{0}); throw new AssertionError("Zero-row batch accepted"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            assert expected.getCause() instanceof java.sql.SQLException;
        }
        System.out.println("TrashMysqlCheck: ALL OK");
    }

    private static void snapshotReaders(String url, Logger logger) throws Exception {
        try (TrashStore store = TrashStore.mysql(url, "voidclear", "voidclear", "snapshot_", logger);
             DatabaseTasks tasks = new DatabaseTasks()) {
            store.clear(store.ids());
            assert store.collect(new byte[]{1}, () -> { });
            var published = TrashStore.class.getDeclaredField("published"); published.setAccessible(true);
            var base = TrashStore.class.getDeclaredField("baseItems"); base.setAccessible(true);
            Object snapshot = published.get(store);
            var count = snapshot.getClass().getDeclaredField("count"); count.setAccessible(true);
            count.set(snapshot, 123L); // Raw-byte fixtures cannot be deserialized; this marks an already computed count.
            Object originalBytes = ((java.util.Map<?, ?>) base.get(store)).values().iterator().next();
            var player = java.util.UUID.randomUUID(); var owner = java.util.UUID.randomUUID();
            var session = java.util.UUID.randomUUID(); var operation = java.util.UUID.randomUUID();
            store.bindSessionOwner(owner);
            assert store.acquirePlayerSession(player, owner, session);
            store.preparePlayerOnly(player, session, operation, null, new byte[]{1});
            store.checkpointPlayer(player, session, operation, "DONE");
            assert store.clear(java.util.List.of(-1L)) == 0;
            assert published.get(store) == snapshot : "Journal-only commits rebuilt the bin snapshot";
            assert count.get(snapshot).equals(123L) : "Journal-only commits discarded the published count";
            assert ((java.util.Map<?, ?>) base.get(store)).values().iterator().next() == originalBytes
                    : "Journal-only commits recopied committed item bytes";
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writer = tasks.submit(() -> {
                synchronized (store) {
                    entered.countDown();
                    try { assert release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException cut) { throw new IllegalStateException(cut); }
                    return store.collect(new byte[]{2}, () -> { });
                }
            });
            assert entered.await(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    assert store.size() == 1;
                    var page = store.page(0, 45);
                    assert store.isCurrentPage(0, 45, page);
                    assert page.size() == 1 && page.getFirst().bytes()[0] == 1;
                    return true;
                });
                assert reader.get(1, java.util.concurrent.TimeUnit.SECONDS) : "Reader waited for writer monitor";
            } finally { release.countDown(); }
            assert writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assert store.size() == 2;
            assert published.get(store) != snapshot : "Item commit retained the old snapshot";
            assert count.get(published.get(store)) == null : "Item commit retained the old item count";
            try (var remote = TrashStore.mysql(url, "voidclear", "voidclear", "snapshot_", logger)) {
                synchronized (store) {
                    Object beforeRemote = published.get(store);
                    var oldPage = store.page(0, 45);
                    assert remote.collect(new byte[]{3}, () -> { });
                    store.checkpointPlayer(player, session, operation, "DONE");
                    assert store.size() == 3 : "Journal-only transaction missed a remote item commit";
                    assert published.get(store) != beforeRemote : "Remote revision did not publish a new snapshot";
                    assert !store.isCurrentPage(0, 45, oldPage) : "Remote commit kept a stale page current";
                }
            }
            store.clear(store.ids());
        }
    }

    /** Writes reuse the parked connection, and a server-killed parked connection is replaced, not surfaced. */
    private static void connectionReuse(String url, Logger logger) throws Exception {
        try (TrashStore store = TrashStore.mysql(url, "voidclear", "voidclear", "pool_", logger);
             var admin = java.sql.DriverManager.getConnection(url, "voidclear", "voidclear")) {
            store.clear(store.ids());
            long before = connections(admin);
            for (int i = 0; i < 10; i++) assert store.collect(new byte[]{1}, () -> { });
            long opened = connections(admin) - before;
            assert opened <= 4 : "Writes opened " + opened + " connections instead of reusing one";
            try (var rows = admin.createStatement().executeQuery(
                    "SELECT ID FROM information_schema.PROCESSLIST WHERE USER='voidclear' AND ID<>CONNECTION_ID()")) {
                var kill = admin.createStatement();
                while (rows.next()) kill.execute("KILL " + rows.getLong(1));
            }
            assert store.collect(new byte[]{2}, () -> { }) : "Killed parked connection was not replaced";
            assert store.size() == 11;
            store.clear(store.ids());
        }
    }

    private static long connections(java.sql.Connection admin) throws Exception {
        try (var rows = admin.createStatement().executeQuery("SHOW GLOBAL STATUS LIKE 'Connections'")) {
            assert rows.next();
            return rows.getLong(2);
        }
    }

    private static void deltaWrites(String url, Logger logger) throws Exception {
        try (TrashStore store = TrashStore.mysql(url, "voidclear", "voidclear", "delta_", logger);
             var connection = java.sql.DriverManager.getConnection(url, "voidclear", "voidclear")) {
            store.clear(store.ids());
            var sql = connection.createStatement();
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS delta_audit (kind VARCHAR(10) NOT NULL)");
            for (String kind : new String[]{"INSERT", "UPDATE", "DELETE"}) {
                sql.executeUpdate("DROP TRIGGER IF EXISTS delta_" + kind);
                sql.executeUpdate("CREATE TRIGGER delta_" + kind + " AFTER " + kind
                        + " ON delta_trash_items FOR EACH ROW INSERT INTO delta_audit VALUES ('" + kind + "')");
            }
            try {
                for (int i = 0; i < 20; i++) assert store.collect(new byte[]{1}, () -> {});
                sql.executeUpdate("DELETE FROM delta_audit");
                assert store.collect(new byte[]{2}, () -> {});
                try (var rows = sql.executeQuery("SELECT kind,COUNT(*) FROM delta_audit GROUP BY kind")) {
                    assert rows.next() && rows.getString(1).equals("INSERT") && rows.getInt(2) == 1 : "Appending rewrote existing rows";
                    assert !rows.next() : "Append updated or deleted untouched rows";
                }
                sql.executeUpdate("DELETE FROM delta_audit");
                long last = store.ids().getLast();
                assert store.clear(java.util.List.of(last)) == 1;
                try (var rows = sql.executeQuery("SELECT kind,COUNT(*) FROM delta_audit GROUP BY kind")) {
                    assert rows.next() && rows.getString(1).equals("DELETE") && rows.getInt(2) == 1 : "Tail removal rewrote existing rows";
                    assert !rows.next();
                }
                sql.executeUpdate("DELETE FROM delta_audit");
                var beforeFailedShift = store.ids();
                long beforeRevision;
                try (var rows = sql.executeQuery("SELECT revision FROM delta_trash_items_meta WHERE id=1")) {
                    assert rows.next(); beforeRevision = rows.getLong(1);
                }
                sql.executeUpdate("CREATE TRIGGER delta_fail BEFORE UPDATE ON delta_trash_items FOR EACH ROW BEGIN IF OLD.position=10 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected middle batch failure'; END IF; END");
                try {
                    store.clear(java.util.List.of(beforeFailedShift.get(7)));
                    throw new AssertionError("Middle batch failure accepted");
                } catch (IllegalStateException expected) { }
                finally { sql.executeUpdate("DROP TRIGGER IF EXISTS delta_fail"); }
                assert store.ids().equals(beforeFailedShift) : "Failed middle batch changed local rows";
                try (var rows = sql.executeQuery("SELECT id,position FROM delta_trash_items ORDER BY position,id")) {
                    for (int i = 0; i < beforeFailedShift.size(); i++) {
                        assert rows.next() && rows.getLong(1) == beforeFailedShift.get(i) && rows.getInt(2) == i
                                : "Earlier DELETE/UPDATE escaped failed transaction";
                    }
                    assert !rows.next();
                }
                try (var rows = sql.executeQuery("SELECT revision FROM delta_trash_items_meta WHERE id=1")) {
                    assert rows.next() && rows.getLong(1) == beforeRevision : "Failed middle batch advanced revision";
                }
                sql.executeUpdate("DELETE FROM delta_audit");
                assert store.clear(java.util.List.of(store.ids().get(7))) == 1;
                try (var rows = sql.executeQuery("SELECT kind,COUNT(*) FROM delta_audit GROUP BY kind ORDER BY kind")) {
                    assert rows.next() && rows.getString(1).equals("DELETE") && rows.getInt(2) == 1;
                    assert rows.next() && rows.getString(1).equals("UPDATE") && rows.getInt(2) == 12 : "Wrong shifted-row updates";
                    assert !rows.next();
                }
                assert store.collect(new byte[]{5}, () -> {});
                var expectedIds = store.ids();
                try (var rows = sql.executeQuery("SELECT id,position FROM delta_trash_items ORDER BY position,id")) {
                    for (int i = 0; i < expectedIds.size(); i++) {
                        assert rows.next() && rows.getLong(1) == expectedIds.get(i) && rows.getInt(2) == i : "Middle deletion broke order";
                    }
                    assert !rows.next();
                }
                sql.executeUpdate("DELETE FROM delta_audit");
                assert store.clear(java.util.List.of(-1L)) == 0;
                try (var rows = sql.executeQuery("SELECT COUNT(*) FROM delta_audit")) {
                    assert rows.next() && rows.getInt(1) == 0 : "No-op wrote rows";
                }
                sql.executeUpdate("CREATE TRIGGER delta_fail BEFORE INSERT ON delta_trash_items FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected delta write failure'");
                try {
                    store.collect(new byte[]{3}, () -> { throw new AssertionError("Failed SQL delivered an effect"); });
                    throw new AssertionError("Delta insert failure swallowed");
                } catch (IllegalStateException expected) { }
                assert store.size() == 20;
                try (var rows = sql.executeQuery("SELECT COUNT(*) FROM delta_trash_items")) {
                    assert rows.next() && rows.getInt(1) == 20 : "Failed delta changed persisted rows";
                }
            } finally {
                for (String kind : new String[]{"INSERT", "UPDATE", "DELETE", "fail"}) sql.executeUpdate("DROP TRIGGER IF EXISTS delta_" + kind);
                store.clear(store.ids());
                sql.executeUpdate("DROP TABLE IF EXISTS delta_audit");
            }
        }
    }
}
