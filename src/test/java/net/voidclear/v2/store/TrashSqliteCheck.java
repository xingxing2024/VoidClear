package net.voidclear.v2.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Logger;

/** Executes the real JDBC path, not the legacy YAML test backend. */
public final class TrashSqliteCheck {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "sqlite-check-").resolve("bin.db");
        Logger logger = Logger.getLogger("TrashSqliteCheck");
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            for (int i = 0; i < 100; i++) {
                assert store.collect(new byte[]{(byte) i}, () -> { });
                assert store.size() == i + 1 : "Refresh lost a pending item";
                assert store.page(0, 128).size() == i + 1;
            }
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.size() == 100 : "Database save failed";
            var entries = store.page(0, 128);
            for (int i = 0; i < 100; i++) assert entries.get(i).bytes()[0] == (byte) i;
            assert store.clear(store.ids()) == 100;
        }
        try (TrashStore first = TrashStore.sqlite(file, logger);
             TrashStore second = TrashStore.sqlite(file, logger)) {
            assert first.collect(new byte[]{10}, () -> { });
            first.close();
            assert second.collect(new byte[]{11}, () -> { });
            second.close();
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.size() == 2 : "Conflict retry dropped one server's item";
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.clear(store.ids()) == 2;
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.size() == 0 : "Delete did not persist";
        }
        incompleteDataRejected(logger);
        overCapacityRejected(logger);
        legacyMigration(logger);
        sweepReceiptAtomicity(logger);
        committedEffectsAllRun(logger);
        cachedSnapshotWrites(logger);
        failedWriteStaysRolledBackOnClose(logger);
        journalIndexUpgrade(logger);
        System.out.println("TrashSqliteCheck: ALL OK");
    }

    private static void journalIndexUpgrade(Logger logger) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "journal-index-").resolve("bin.db");
        try (var store = TrashStore.sqlite(file, logger);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            assert store.collect(new byte[]{7}, () -> {});
            connection.createStatement().executeUpdate("DROP INDEX voidclear_trash_items_ops_lookup");
        }
        for (int restart = 0; restart < 2; restart++) {
            try (var store = TrashStore.sqlite(file, logger);
                 var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
                assert store.size() == 1 && store.page(0, 1).getFirst().bytes()[0] == 7 : "Index upgrade changed items";
                for (String predicate : new String[]{"player_id='test' AND state='DROPS'",
                        "player_id='test' AND session_id='session' AND state='CURSOR'"}) {
                    try (var rows = connection.createStatement().executeQuery("EXPLAIN QUERY PLAN SELECT op_id FROM "
                            + "voidclear_trash_items_player_ops WHERE " + predicate)) {
                        assert rows.next() && rows.getString("detail").contains("USING INDEX voidclear_trash_items_ops_lookup")
                                : "Player journal query still scans all players";
                    }
                }
            }
        }
    }

    /** A batched sweep commits many sources at once; one failed removal must not leave the rest duplicated. */
    private static void committedEffectsAllRun(Logger logger) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "batch-effects-").resolve("bin.db");
        var write = TrashStore.class.getDeclaredMethod("transaction", java.util.function.Supplier.class); write.setAccessible(true);
        var later = new java.util.concurrent.atomic.AtomicInteger();
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            java.util.function.Supplier<Boolean> operation = () -> {
                assert store.collect(new byte[]{1}, () -> { throw new IllegalStateException("injected removal failure"); });
                assert store.collect(new byte[]{2}, later::incrementAndGet);
                return true;
            };
            try { write.invoke(store, operation); throw new AssertionError("Committed removal failure was hidden"); }
            catch (java.lang.reflect.InvocationTargetException expected) { assert expected.getCause() instanceof IllegalStateException; }
            assert later.get() == 1 : "A failed removal skipped later committed removals";
            assert store.size() == 2 : "Committed batch was rolled back in memory";
        }
    }

    private static void sweepReceiptAtomicity(Logger logger) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "sweep-receipt-").resolve("bin.db");
        var entity = java.util.UUID.randomUUID();
        var write = TrashStore.class.getDeclaredMethod("transaction", java.util.function.Supplier.class); write.setAccessible(true);
        var record = TrashStore.class.getDeclaredMethod("recordSweep", Connection.class, java.util.UUID.class, TrashStore.SweepReceipt.class);
        record.setAccessible(true);
        var connectionField = TrashStore.class.getDeclaredField("transactionConnection"); connectionField.setAccessible(true);
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        try (TrashStore store = TrashStore.sqlite(file, logger);
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            connection.createStatement().executeUpdate("CREATE TRIGGER refuse_receipt BEFORE INSERT ON voidclear_trash_items_sweeps BEGIN SELECT RAISE(ABORT,'injected receipt failure'); END");
            java.util.function.Supplier<Boolean> operation = () -> {
                assert store.collect(new byte[]{7}, effects::incrementAndGet);
                try { record.invoke(store, connectionField.get(store), entity, new TrashStore.SweepReceipt(1, new byte[]{7}, null)); }
                catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
                return true;
            };
            try { write.invoke(store, operation); throw new AssertionError("Receipt failure committed a bin grant"); }
            catch (java.lang.reflect.InvocationTargetException expected) { assert expected.getCause() instanceof IllegalStateException; }
            assert count(connection) == 0 && store.size() == 0 && effects.get() == 0;
            connection.createStatement().executeUpdate("DROP TRIGGER refuse_receipt");
            assert (boolean) write.invoke(store, operation);
            assert count(connection) == 1 && effects.get() == 1;
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.sweepReceipts(entity).size() == 1 : "Source receipt missing after restart";
            assert store.hasSweepReceipts(entity) : "Receipt index lost a committed source after restart";
            assert !store.hasSweepReceipts(java.util.UUID.randomUUID()) : "Receipt index matched an unknown entity";
            assert store.sweepReceipts(entity).getFirst().remainder() == null;
            assert store.clear(store.ids()) == 1;
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.size() == 0 && store.sweepReceipts(entity).size() == 1 : "Bin clear erased anti-replay receipt";
            byte[] copy = store.sweepReceipts(entity).getFirst().before(); copy[0] = 99;
            assert store.sweepReceipts(entity).getFirst().before()[0] == 7;
            Path legacy = file.resolveSibling("trash.yml");
            Files.writeString(legacy, "version: 1\nitems:\n- id: 1\n  data: AQ==\n");
            try { store.migrateLegacy(legacy); throw new AssertionError("Retained source receipts allowed reimport"); }
            catch (IllegalStateException expected) { assert store.size() == 0 && Files.exists(legacy); }
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            connection.createStatement().executeUpdate("INSERT INTO voidclear_trash_items_sweeps VALUES ('"
                    + java.util.UUID.randomUUID() + "',2,X'01',NULL)");
        }
        try { TrashStore.sqlite(file, logger); throw new AssertionError("Gapped anti-replay receipts accepted"); }
        catch (IllegalStateException expected) { assert hasCause(expected, java.sql.SQLException.class); }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM voidclear_trash_items_sweeps WHERE sequence=2");
            statement.executeUpdate("INSERT INTO voidclear_trash_items_sweeps VALUES ('"
                    + java.util.UUID.randomUUID() + "',1,X'01',X'')");
        }
        try { TrashStore.sqlite(file, logger); throw new AssertionError("Empty remainder accepted during startup"); }
        catch (IllegalStateException expected) { assert hasCause(expected, java.sql.SQLException.class); }
    }

    private static void legacyMigration(Logger logger) throws Exception {
        Path folder = Files.createTempDirectory(Path.of("target"), "legacy-import-");
        Path source = folder.resolve("trash.yml"), database = folder.resolve("bin.db");
        String yaml = "# keep original bytes\nversion: 1\nitems:\n- id: 1\n  data: AQ==\n- id: 2\n  data: Ag==\n";
        Files.writeString(source, yaml);
        // A trigger simulates disk/SQL failure halfway through the import.
        try (TrashStore store = TrashStore.sqlite(database, logger);
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            connection.createStatement().executeUpdate("CREATE TRIGGER fail_import BEFORE INSERT ON voidclear_trash_items WHEN NEW.id=2 BEGIN SELECT RAISE(ABORT,'injected import failure'); END");
            try { store.migrateLegacy(source); throw new AssertionError("Import failure accepted"); }
            catch (IllegalStateException expected) { }
            assert count(connection) == 0 : "Partial import persisted";
            assert store.size() == 0;
            assert Files.readString(source).equals(yaml) : "Source rewritten on failure";
            connection.createStatement().executeUpdate("DROP TRIGGER fail_import");
            // An existing backup forces rename failure, without overwriting that backup.
            Files.writeString(folder.resolve("trash.yml.migrated"), "existing backup");
            store.migrateLegacy(source);
            assert count(connection) == 2 && store.size() == 2;
            assert Files.readString(source).equals(yaml);
            assert Files.readString(folder.resolve("trash.yml.migrated")).equals("existing backup");
            store.clear(store.ids());
        }
        try (TrashStore reopened = TrashStore.sqlite(database, logger)) {
            reopened.migrateLegacy(source);
            assert reopened.size() == 0 : "Retained source refilled an emptied bin";
            Files.writeString(source, yaml + "# changed after migration\n");
            try { reopened.migrateLegacy(source); throw new AssertionError("Changed source ignored"); }
            catch (IllegalStateException expected) { }
            assert reopened.size() == 0;
            Files.writeString(source, yaml);
            assert reopened.collect(new byte[]{3}, () -> {}): "Migration left the store closed";
        }
        Path other = folder.resolve("nonempty.db");
        try (TrashStore occupied = TrashStore.sqlite(other, logger)) {
            assert occupied.collect(new byte[]{9}, () -> {});
        }
        try (TrashStore occupied = TrashStore.sqlite(other, logger)) {
            try { occupied.migrateLegacy(source); throw new AssertionError("Nonempty target imported"); }
            catch (IllegalStateException expected) { }
            assert occupied.size() == 1 && occupied.page(0, 1).get(0).bytes()[0] == 9;
        }
    }

    /**
     * Load must refuse incomplete data (empty blob) instead of silently truncating: SQLite schema is
     * NOT NULL, so a "bad" row has to be a non-NULL empty blob. Startup must fail and leave the DB intact.
     */
    private static void incompleteDataRejected(Logger logger) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "sqlite-bad-").resolve("bin.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("CREATE TABLE voidclear_trash_items "
                    + "(id INTEGER PRIMARY KEY AUTOINCREMENT, position INT NOT NULL, item_data BLOB NOT NULL, updated_at BIGINT NOT NULL)");
            connection.createStatement().executeUpdate("CREATE TABLE voidclear_trash_items_meta "
                    + "(id INT PRIMARY KEY, revision BIGINT NOT NULL)");
            connection.createStatement().executeUpdate("INSERT INTO voidclear_trash_items_meta (id,revision) VALUES (1,0)");
            insertRow(connection, 0, new byte[]{1, 2, 3});
            insertRow(connection, 1, new byte[0]); // empty blob: unusable but NOT NULL for the schema
            insertRow(connection, 2, new byte[]{4, 5, 6});
        }
        try {
            TrashStore.sqlite(file, logger);
            throw new AssertionError("Incomplete SQLite data was silently accepted");
        } catch (IllegalStateException expected) {
            assert hasCause(expected, java.sql.SQLException.class)
                    : "Root cause must be SQLException, got " + root(expected);
        }
        try (Connection connection = DriverManager.getConnection(url)) {
            assert count(connection) == 3 : "Load failure must leave every DB row intact, got " + count(connection);
        }
    }

    /** More rows than CAPACITY is also incomplete data: refuse at load, never truncate to the capacity. */
    private static void overCapacityRejected(Logger logger) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "sqlite-over-").resolve("bin.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("CREATE TABLE voidclear_trash_items "
                    + "(id INTEGER PRIMARY KEY AUTOINCREMENT, position INT NOT NULL, item_data BLOB NOT NULL, updated_at BIGINT NOT NULL)");
            connection.createStatement().executeUpdate("CREATE TABLE voidclear_trash_items_meta "
                    + "(id INT PRIMARY KEY, revision BIGINT NOT NULL)");
            connection.createStatement().executeUpdate("INSERT INTO voidclear_trash_items_meta (id,revision) VALUES (1,0)");
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO voidclear_trash_items (position,item_data,updated_at) VALUES (?,?,?)")) {
                for (int i = 0; i <= TrashStore.CAPACITY; i++) {
                    insert.setInt(1, i);
                    insert.setBytes(2, new byte[]{1});
                    insert.setLong(3, 0L);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        }
        try {
            TrashStore.sqlite(file, logger);
            throw new AssertionError("Over-capacity SQLite data was silently truncated");
        } catch (IllegalStateException expected) {
            assert hasCause(expected, java.sql.SQLException.class)
                    : "Root cause must be SQLException, got " + root(expected);
        }
        try (Connection connection = DriverManager.getConnection(url)) {
            assert count(connection) == TrashStore.CAPACITY + 1 : "Refusal must not delete rows, got " + count(connection);
        }
    }

    /** Writes skip the full-table read on an unchanged revision; remote writes and gapped positions must still be honoured. */
    private static void cachedSnapshotWrites(Logger logger) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "cached-writes-").resolve("bin.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (TrashStore a = TrashStore.sqlite(file, logger); TrashStore b = TrashStore.sqlite(file, logger)) {
            for (int i = 0; i < 10; i++) {
                assert a.collect(new byte[]{(byte) (2 * i)}, () -> { });
                assert b.collect(new byte[]{(byte) (2 * i + 1)}, () -> { });
            }
            assert a.clear(java.util.List.of(a.ids().get(3))) == 1;
            assert b.collect(new byte[]{99}, () -> { });
        }
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("UPDATE voidclear_trash_items SET position=position*3");
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.size() == 20 : "Interleaved writers lost or duplicated rows";
            assert store.collect(new byte[]{100}, () -> { });
            assert store.collect(new byte[]{101}, () -> { });
            assert store.clear(java.util.List.of(store.ids().get(0))) == 1;
        }
        try (Connection connection = DriverManager.getConnection(url);
             ResultSet rows = connection.createStatement().executeQuery("SELECT position,item_data FROM voidclear_trash_items ORDER BY position,id")) {
            byte[] expected = {1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 99, 100, 101};
            int position = 0;
            while (rows.next()) {
                assert rows.getInt(1) == position : "Positions not compact after cached writes";
                assert rows.getBytes(2)[0] == expected[position++] : "Wrong order after cached writes";
            }
            assert position == expected.length : "Row count mismatch after cached writes, got " + position;
        }
    }

    private static void insertRow(Connection connection, int position, byte[] data) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO voidclear_trash_items (position,item_data,updated_at) VALUES (?,?,?)")) {
            insert.setInt(1, position);
            insert.setBytes(2, data);
            insert.setLong(3, 0L);
            insert.executeUpdate();
        }
    }

    private static void failedWriteStaysRolledBackOnClose(Logger logger) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "rollback-close-").resolve("bin.db");
        var removed = new java.util.concurrent.atomic.AtomicInteger();
        try (TrashStore store = TrashStore.sqlite(file, logger);
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            assert store.collect(new byte[]{1}, removed::incrementAndGet);
            connection.createStatement().executeUpdate("CREATE TRIGGER refuse_write BEFORE INSERT ON voidclear_trash_items BEGIN SELECT RAISE(ABORT,'injected write failure'); END");
            try { store.collect(new byte[]{2}, removed::incrementAndGet); throw new AssertionError("Failed write accepted"); }
            catch (IllegalStateException expected) { }
            assert store.size() == 1 && removed.get() == 1 : "Failed transaction changed items or delivered its effect";
            connection.createStatement().executeUpdate("DROP TRIGGER refuse_write");
            store.close();
            assert count(connection) == 1 : "Close revived a rolled-back snapshot";
            store.close();
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.size() == 1 && store.page(0, 1).getFirst().bytes()[0] == 1;
        }
    }

    private static int count(Connection connection) throws Exception {
        try (ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM voidclear_trash_items")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static boolean hasCause(Throwable failed, Class<? extends Throwable> type) {
        for (Throwable current = failed; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static String root(Throwable failed) {
        Throwable current = failed;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getName() + ": " + current.getMessage();
    }
}
