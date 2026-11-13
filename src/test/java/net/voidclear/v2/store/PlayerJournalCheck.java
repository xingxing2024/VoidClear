package net.voidclear.v2.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public final class PlayerJournalCheck {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "player-journal-").resolve("bin.db");
        UUID player = UUID.randomUUID(), session = UUID.randomUUID(), operation = UUID.randomUUID();
        Logger logger = Logger.getLogger("PlayerJournalCheck");
        try (TrashStore store = TrashStore.sqlite(file, logger);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            connection.createStatement().executeUpdate("CREATE TRIGGER refuse_op BEFORE INSERT ON voidclear_trash_items_player_ops BEGIN SELECT RAISE(ABORT,'injected journal failure'); END");
            try {
                store.replacePlayerPage(player, session, operation, null, new byte[]{1, 2, 3}, 0, 1, List.of(), java.util.Collections.singletonList(null));
                throw new AssertionError("Journal failure accepted");
            } catch (IllegalStateException expected) { }
            assert store.playerCheckpoint(player) == null;
            connection.createStatement().executeUpdate("DROP TRIGGER refuse_op");
            assert store.replacePlayerPage(player, session, operation, null, new byte[]{1, 2, 3}, 0, 1, List.of(), java.util.Collections.singletonList(null));
            assert store.playerCheckpoint(player).state().equals("PREPARED");
            UUID other = UUID.randomUUID(), otherSession = UUID.randomUUID(), otherOperation = UUID.randomUUID();
            assert store.replacePlayerPage(other, otherSession, otherOperation, null,
                     new byte[]{7}, 0, 1, List.of(), java.util.Collections.singletonList(null))
                    : "An unrelated player-only operation blocked an empty page";
            store.checkpointPlayer(other, otherSession, otherOperation, "DONE");
            connection.createStatement().executeUpdate("DELETE FROM voidclear_trash_items_item_fences");
            assert !store.replacePlayerPage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                    new byte[]{7}, 0, 1, List.of(), java.util.Collections.singletonList(null))
                    : "Legacy operations without item evidence must stay globally fenced";
        }
        try (TrashStore store = TrashStore.sqlite(file, logger)) {
            assert store.playerCheckpoint(player).operation().equals(operation);
            assert store.playerCheckpoint(player).state().equals("PREPARED");
            try {
                store.preparePlayerOnly(player, UUID.randomUUID(), UUID.randomUUID(), operation, new byte[]{9});
                throw new AssertionError("Unresolved operation overwritten");
            } catch (IllegalStateException expected) { }
            store.clearAll();
            assert store.playerCheckpoint(player).state().equals("PREPARED") : "Clear erased player fence";
            store.checkpointPlayer(player, session, operation, "CURSOR");
            UUID second = UUID.randomUUID();
            assert store.replacePlayerPage(player, session, second, operation, new byte[]{4}, 0, 1, List.of(), java.util.Collections.singletonList(null));
            try { store.checkpointPlayer(player, session, operation, "DONE"); throw new AssertionError("Stale op acknowledged"); }
            catch (IllegalStateException expected) { }
            store.checkpointPlayer(player, session, second, "DONE");
            assert store.playerCheckpoint(player).state().equals("DONE");
            try {
                store.preparePlayerOnly(player, UUID.randomUUID(), UUID.randomUUID(), operation, new byte[]{8});
                throw new AssertionError("Old remote inventory replaced newer receipt");
            } catch (IllegalStateException expected) { }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                 var rows = connection.createStatement().executeQuery("SELECT state,evidence FROM voidclear_trash_items_player_ops ORDER BY created_at")) {
                assert rows.next() && rows.getString(1).equals("DONE") && java.util.Arrays.equals(rows.getBytes(2), new byte[]{1,2,3});
                assert rows.next() && rows.getString(1).equals("DONE") && rows.getBytes(2)[0] == 7;
                assert rows.next() && rows.getString(1).equals("DONE") && rows.getBytes(2)[0] == 4;
                assert !rows.next();
            }
        }
        System.out.println("PlayerJournalCheck: ALL OK");
    }
}
