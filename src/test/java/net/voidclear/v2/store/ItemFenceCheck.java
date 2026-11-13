package net.voidclear.v2.store;

import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Real JDBC transactions with opaque item bytes; serialization itself is covered by the server probe. */
public final class ItemFenceCheck {
    private static final class Stack extends ItemStack {
        private final byte[] bytes;
        Stack(int value) { bytes = new byte[]{(byte) value}; }
        @Override public boolean isEmpty() { return false; }
        @Override public byte[] serializeAsBytes() { return bytes.clone(); }
    }

    public static void main(String[] args) throws Exception {
        Path db = Files.createTempDirectory(Path.of("target"), "item-fence-").resolve("bin.db");
        Logger logger = Logger.getLogger("ItemFenceCheck");
        UUID player = UUID.randomUUID(), session = UUID.randomUUID(), operation = UUID.randomUUID();
        long fenced;
        try (TrashStore store = TrashStore.sqlite(db, logger)) {
            assert store.collect(new byte[]{1}, () -> {});
            assert store.collect(new byte[]{2}, () -> {});
            assert store.replacePlayerPage(player, session, operation, null, new byte[]{9},
                    1, 1, store.page(1, 1), List.of(new Stack(3)));
            fenced = store.page(1, 1).getFirst().id();
        }
        try (TrashStore store = TrashStore.sqlite(db, logger);
             TrashStore remote = TrashStore.sqlite(db, logger);
             var sql = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            assert !replace(remote, 1, 4) : "Another server circulated an unconfirmed deposit after restart";
            assert replace(remote, 0, 5) : "An unrelated page was globally blocked";
            store.checkpointPlayer(player, session, operation, "QUARANTINED");
            assert !replace(remote, 1, 4) : "Quarantine released the item fence";
            assert replace(remote, 0, 6) : "Quarantine blocked unrelated items";
            // Removing an earlier row shifts page numbers, but not the item fence.
            assert remote.replacePage(0, 1, remote.page(0, 1), Collections.singletonList(null));
            assert remote.page(0, 1).getFirst().id() == fenced;
            assert !replace(remote, 0, 7) : "Page shifting bypassed the item fence";
            // Legacy evidence has no row set: keep the conservative global block, never guess.
            UUID legacy = UUID.randomUUID(), legacySession = UUID.randomUUID(), legacyOp = UUID.randomUUID();
            remote.preparePlayerOnly(legacy, legacySession, legacyOp, null, new byte[]{1});
            sql.createStatement().executeUpdate("DELETE FROM voidclear_trash_items_item_fences WHERE op_id='" + legacyOp + "'");
            assert !replace(remote, 1, 8) : "A legacy operation lost its global fence";
            remote.checkpointPlayer(legacy, legacySession, legacyOp, "DONE");
            assert replace(remote, 1, 8) : "Legacy confirmation failed to release its global fence";
        }
        Path failureDb = db.resolveSibling("failed.db");
        try (TrashStore store = TrashStore.sqlite(failureDb, logger);
             var sql = DriverManager.getConnection("jdbc:sqlite:" + failureDb.toAbsolutePath())) {
            sql.createStatement().executeUpdate("CREATE TRIGGER refuse_fence BEFORE INSERT ON voidclear_trash_items_item_fences WHEN NEW.item_id<>0 BEGIN SELECT RAISE(ABORT,'injected fence failure'); END");
            try { replace(store, 0, 1); throw new AssertionError("A failed fence write was accepted"); }
            catch (IllegalStateException expected) { }
            assert store.size() == 0 : "Failed fencing committed a deposit";
            try (var rows = sql.createStatement().executeQuery("SELECT COUNT(*) FROM voidclear_trash_items_players")) {
                assert rows.next() && rows.getInt(1) == 0 : "Failed fencing committed a player journal";
            }
            try (var rows = sql.createStatement().executeQuery("SELECT COUNT(*) FROM voidclear_trash_items_item_fences")) {
                assert rows.next() && rows.getInt(1) == 0 : "Failed fencing left a partial fence";
            }
            sql.createStatement().executeUpdate("DROP TRIGGER refuse_fence");
            assert store.replacePlayerPage(player, session, operation, null, new byte[]{9}, 0, 1, List.of(), List.of(new Stack(2)));
            assert !replace(store, 0, 3);
            store.checkpointPlayer(player, session, operation, "CURSOR");
            assert replace(store, 0, 3) : "Durable receipt did not release the item fence";
            UUID next = UUID.randomUUID();
            store.preparePlayerOnly(player, session, next, operation, new byte[]{1});
            try (var rows = sql.createStatement().executeQuery("SELECT COUNT(*) FROM voidclear_trash_items_item_fences WHERE op_id='" + operation + "'")) {
                assert rows.next() && rows.getInt(1) == 0 : "Superseded item fence markers accumulated";
            }
        }
        System.out.println("ItemFenceCheck: ALL OK");
    }

    private static boolean replace(TrashStore store, int page, int value) {
        UUID player = UUID.randomUUID(), session = UUID.randomUUID(), op = UUID.randomUUID();
        boolean accepted = store.replacePlayerPage(player, session, op, null, new byte[]{1},
                page, 1, store.page(page, 1), List.of(new Stack(value)));
        if (accepted) store.checkpointPlayer(player, session, op, "DONE");
        return accepted;
    }
}
