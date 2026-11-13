package net.voidclear.v2.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;

/** Real SQLite and real atomic file replacements, never a server's player directory. */
public final class ReceiptCleanupCheck {
    private static final String KEY = "voidclear:player_receipt";
    private static final String TABLE = "voidclear_trash_items";

    public static void main(String[] args) throws Exception {
        for (int fallback = 0; fallback < 3; fallback++) successfulReset(fallback);
        changedFilesAndConfirmation();
        invalidFilesAndBackup();
        databaseGuards();
        System.out.println("ReceiptCleanupCheck: ALL OK");
    }

    private static void successfulReset(int fallback) throws Exception {
        try (var f = new Fixture()) {
            byte[] old = fallback == 0 ? null : PlayerSaveReceiptCheck.fixture(fallback == 1 ? f.operation.toString() : null, false);
            if (old != null) Files.write(f.old(), old);
            String database = f.databaseSnapshot();
            var preview = f.cleaner.preview(f.player, f.store);
            assert preview.player().equals(f.player) && preview.receipt().equals(f.operation);
            assert preview.expires().isAfter(Instant.now()) && preview.expires().isBefore(Instant.now().plusSeconds(301));
            assert Arrays.equals(Files.readAllBytes(f.main()), f.original) : "Preview wrote a player file";
            assert !Files.exists(f.backups);
            Path backup = f.cleaner.confirm(f.player, preview.token(), f.store);
            assert Arrays.equals(Files.readAllBytes(backup.resolve(f.player + ".dat")), f.original);
            assert Arrays.equals(PlayerSaveReceiptCheck.unzip(Files.readAllBytes(f.main())),
                    PlayerSaveReceiptCheck.unzip(PlayerSaveReceiptCheck.fixture(null, true))) : "Inventory or unrelated NBT changed";
            assert PlayerSaveReceipt.read(f.main(), KEY) == null;
            if (old == null) assert !Files.exists(f.old());
            else {
                assert Arrays.equals(Files.readAllBytes(backup.resolve(f.player + ".dat_old")), old);
                assert PlayerSaveReceipt.read(f.old(), KEY) == null;
                assert Arrays.equals(PlayerSaveReceiptCheck.unzip(Files.readAllBytes(f.old())),
                        PlayerSaveReceiptCheck.unzip(PlayerSaveReceiptCheck.fixture(null, false)));
                if (fallback == 2) assert Arrays.equals(Files.readAllBytes(f.old()), old) : "Receipt-free fallback was rewritten";
            }
            assert Files.readString(backup.resolve("reset-plan.txt")).contains("no compensation; no database changes");
            assert Files.exists(backup.resolve("completed.txt"));
            assert f.databaseSnapshot().equals(database) : "Receipt cleanup changed SQL evidence";
            refused(() -> f.cleaner.confirm(f.player, preview.token(), f.store));
            refused(() -> f.cleaner.preview(f.player, f.store));
        }
    }

    private static void changedFilesAndConfirmation() throws Exception {
        try (var f = new Fixture()) {
            var first = f.cleaner.preview(f.player, f.store);
            var next = f.cleaner.preview(f.player, f.store);
            refused(() -> f.cleaner.confirm(f.player, first.token(), f.store));
            refused(() -> f.cleaner.confirm(UUID.randomUUID(), next.token(), f.store));
            refused(() -> f.cleaner.confirm(f.player, UUID.randomUUID().toString(), f.store));
            assert Arrays.equals(Files.readAllBytes(f.main()), f.original);
            f.cleaner.confirm(f.player, next.token(), f.store);
        }
        for (boolean oldChanged : new boolean[]{false, true}) try (var f = new Fixture()) {
            var preview = f.cleaner.preview(f.player, f.store);
            byte[] changed = PlayerSaveReceiptCheck.fixture(f.operation.toString(), false);
            Files.write(oldChanged ? f.old() : f.main(), changed);
            refused(() -> f.cleaner.confirm(f.player, preview.token(), f.store));
            assert Arrays.equals(Files.readAllBytes(f.main()), oldChanged ? f.original : changed);
            assert !Files.exists(f.backups) : "Changed files should fail before any backup/write";
            refused(() -> f.cleaner.confirm(f.player, preview.token(), f.store));
        }
        try (var f = new Fixture()) {
            var preview = f.cleaner.preview(f.player, f.store);
            var field = ReceiptCleanup.class.getDeclaredField("pending");
            field.setAccessible(true);
            Object plan = field.get(f.cleaner);
            var constructor = plan.getClass().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            var components = plan.getClass().getRecordComponents();
            Object[] values = new Object[components.length];
            values[0] = new ReceiptCleanup.Preview(preview.player(), preview.receipt(), preview.token(), Instant.EPOCH);
            for (int i = 1; i < components.length; i++) {
                var accessor = components[i].getAccessor(); accessor.setAccessible(true);
                values[i] = accessor.invoke(plan);
            }
            field.set(f.cleaner, constructor.newInstance(values));
            refused(() -> f.cleaner.confirm(f.player, preview.token(), f.store));
            assert Arrays.equals(Files.readAllBytes(f.main()), f.original);
        }
    }

    private static void invalidFilesAndBackup() throws Exception {
        for (byte[] bad : new byte[][]{new byte[]{1, 2, 3}, PlayerSaveReceiptCheck.fixture(null, true),
                PlayerSaveReceiptCheck.fixture("1-1-1-1-1", true)}) try (var f = new Fixture()) {
            Files.write(f.main(), bad);
            refused(() -> f.cleaner.preview(f.player, f.store));
            assert Arrays.equals(Files.readAllBytes(f.main()), bad);
        }
        try (var f = new Fixture()) {
            byte[] other = PlayerSaveReceiptCheck.fixture(UUID.randomUUID().toString(), false);
            Files.write(f.old(), other);
            refused(() -> f.cleaner.preview(f.player, f.store));
            assert Arrays.equals(Files.readAllBytes(f.old()), other);
            assert Arrays.equals(Files.readAllBytes(f.main()), f.original);
        }
        try (var f = new Fixture()) {
            Files.write(f.old(), f.original);
            Files.writeString(f.backups, "not a directory");
            var preview = f.cleaner.preview(f.player, f.store);
            refused(() -> f.cleaner.confirm(f.player, preview.token(), f.store));
            assert Arrays.equals(Files.readAllBytes(f.main()), f.original);
            assert Arrays.equals(Files.readAllBytes(f.old()), f.original);
            assert Files.readString(f.backups).equals("not a directory");
        }
        try (var f = new Fixture()) {
            Files.delete(f.main()); Files.createDirectory(f.main());
            refused(() -> f.cleaner.preview(f.player, f.store));
        }
    }

    private static void databaseGuards() throws Exception {
        for (String guard : new String[]{"missing-owner", "foreign-owner", "checkpoint", "checkpoint-other-player",
                "history", "history-other-player", "drop", "fence", "sql-failure"}) try (var f = new Fixture()) {
            // Recheck at confirmation, not only during the earlier successful preview.
            var preview = f.cleaner.preview(f.player, f.store);
            String player = f.player.toString(), operation = f.operation.toString(), other = UUID.randomUUID().toString();
            String sql = switch (guard) {
                case "missing-owner" -> "DELETE FROM " + TABLE + "_sessions";
                case "foreign-owner" -> "UPDATE " + TABLE + "_sessions SET owner_id='" + other + "'";
                case "checkpoint", "checkpoint-other-player" -> "INSERT INTO " + TABLE + "_players VALUES('"
                        + (guard.equals("checkpoint") ? player : other) + "','" + operation + "','" + other + "','DONE')";
                case "history", "history-other-player" -> "INSERT INTO " + TABLE + "_player_ops VALUES('"
                        + (guard.equals("history") ? other : operation) + "','" + (guard.equals("history") ? player : other)
                        + "','" + other + "','DONE',X'01',0)";
                case "drop" -> "INSERT INTO " + TABLE + "_player_drops VALUES('" + other + "','" + operation + "',0,X'01')";
                case "fence" -> "INSERT INTO " + TABLE + "_item_fences VALUES('" + operation + "',1)";
                case "sql-failure" -> "DROP TABLE " + TABLE + "_item_fences";
                default -> throw new AssertionError(guard);
            };
            try (var statement = f.connection.createStatement()) { statement.executeUpdate(sql); }
            refused(() -> f.cleaner.confirm(f.player, preview.token(), f.store));
            refused(() -> f.cleaner.preview(f.player, f.store));
            assert Arrays.equals(Files.readAllBytes(f.main()), f.original) : guard;
            assert !Files.exists(f.backups) : guard;
            if (guard.equals("foreign-owner")) {
                try (var rows = f.connection.createStatement().executeQuery("SELECT owner_id FROM " + TABLE + "_sessions")) {
                    assert rows.next() && rows.getString(1).equals(other) : "Foreign owner was taken over";
                }
            }
        }
        try (var f = new Fixture(); var unbound = TrashStore.sqlite(f.database, Logger.getLogger("ReceiptCleanupCheck"))) {
            refused(() -> f.cleaner.preview(f.player, unbound));
            assert Arrays.equals(Files.readAllBytes(f.main()), f.original);
        }
    }

    private static void refused(Runnable operation) {
        try { operation.run(); throw new AssertionError("Unsafe receipt cleanup was accepted"); }
        catch (IllegalArgumentException | IllegalStateException | java.io.UncheckedIOException expected) { }
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory(Path.of("target"), "receipt-cleanup-");
        final Path database = directory.resolve("bin.db"), backups = directory.resolve("backups");
        final UUID player = UUID.randomUUID(), operation = UUID.randomUUID(), owner = UUID.randomUUID(), session = UUID.randomUUID();
        final byte[] original = PlayerSaveReceiptCheck.fixture(operation.toString(), true);
        final TrashStore store = TrashStore.sqlite(database, Logger.getLogger("ReceiptCleanupCheck"));
        final Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        final ReceiptCleanup cleaner = new ReceiptCleanup(directory, backups, KEY);

        Fixture() throws Exception {
            store.bindSessionOwner(owner);
            assert store.acquirePlayerSession(player, owner, session);
            Files.write(main(), original);
        }
        Path main() { return directory.resolve(player + ".dat"); }
        Path old() { return directory.resolve(player + ".dat_old"); }
        String databaseSnapshot() throws Exception {
            StringBuilder result = new StringBuilder();
            for (String suffix : new String[]{"", "_players", "_player_ops", "_player_drops", "_item_fences"}) {
                try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + TABLE + suffix)) {
                    rows.next(); result.append(rows.getLong(1)).append('/');
                }
            }
            try (var rows = connection.createStatement().executeQuery("SELECT player_id,owner_id,session_id FROM " + TABLE + "_sessions")) {
                while (rows.next()) result.append(rows.getString(1)).append(rows.getString(2)).append(rows.getString(3));
            }
            try (var rows = connection.createStatement().executeQuery("SELECT revision FROM " + TABLE + "_meta")) {
                rows.next(); result.append(rows.getLong(1));
            }
            return result.toString();
        }
        @Override public void close() throws Exception { connection.close(); store.close(); }
    }
}
