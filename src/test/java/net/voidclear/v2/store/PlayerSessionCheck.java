package net.voidclear.v2.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.logging.Logger;

public final class PlayerSessionCheck {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "session-check-");
        Path identity = directory.resolve("server-owner.id"), database = directory.resolve("bin.db");
        UUID owner;
        try (var lock = new ServerOwnership(identity)) {
            owner = lock.id();
            try (var duplicate = new ServerOwnership(identity)) { throw new AssertionError("Two owners of one installation"); }
            catch (java.nio.channels.OverlappingFileLockException | java.io.IOException expected) { }
        }
        try (var lock = new ServerOwnership(identity)) { assert lock.id().equals(owner) : "Restart changed installation identity"; }
        Files.writeString(identity, "broken");
        try (var lock = new ServerOwnership(identity)) { throw new AssertionError("Corrupt identity regenerated"); }
        catch (java.io.IOException expected) { }
        assert Files.readString(identity).equals("broken") : "Corrupt evidence overwritten";

        UUID player = UUID.randomUUID(), other = UUID.randomUUID(), first = UUID.randomUUID(), next = UUID.randomUUID();
        Logger logger = Logger.getLogger("PlayerSessionCheck");
        try (var a = TrashStore.sqlite(database, logger); var b = TrashStore.sqlite(database, logger);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            assert a.acquirePlayerSession(player, owner, first);
            assert !b.acquirePlayerSession(player, other, next) : "Foreign owner took a live player";
            assert a.ownsPlayerSession(player, owner, first);
            assert a.sessionCheckpoint(player, owner, first, "x") == null : "Owned session without receipt must be null";
            try { a.sessionCheckpoint(player, other, first, "not-owned"); throw new AssertionError("Foreign session accepted"); }
            catch (IllegalStateException expected) { assert expected.getMessage().equals("not-owned"); }
            a.clearAll();
            assert a.ownsPlayerSession(player, owner, first) : "Clear deleted durable ownership";
            connection.createStatement().executeUpdate("CREATE TRIGGER refuse_session BEFORE UPDATE ON voidclear_trash_items_sessions BEGIN SELECT RAISE(ABORT,'injected session failure'); END");
            try { a.acquirePlayerSession(player, owner, next); throw new AssertionError("Failed update accepted"); }
            catch (IllegalStateException expected) { }
            assert a.ownsPlayerSession(player, owner, first) : "Failed acquire destroyed old owner";
            connection.createStatement().executeUpdate("DROP TRIGGER refuse_session");
            assert a.acquirePlayerSession(player, owner, next);
            try { a.releasePlayerSession(player, owner, first); throw new AssertionError("Stale quit released new session"); }
            catch (IllegalStateException expected) { }
            assert b.ownsPlayerSession(player, owner, next);
            b.bindSessionOwner(other);
            try {
                b.preparePlayerOnly(player, next, UUID.randomUUID(), null, new byte[]{1});
                throw new AssertionError("Foreign store journaled a player operation");
            } catch (IllegalStateException expected) { }
            assert b.playerCheckpoint(player) == null;
            a.bindSessionOwner(owner);
            UUID operation = UUID.randomUUID();
            try { a.preparePlayerOnly(player, first, operation, null, new byte[]{1}); throw new AssertionError("Stale session prepared a transaction"); }
            catch (IllegalStateException expected) { }
            assert a.playerCheckpoint(player) == null;
            a.preparePlayerOnly(player, next, operation, null, new byte[]{1});
            assert a.sessionCheckpoint(player, owner, next, "x").operation().equals(operation);
            try { b.checkpointPlayer(player, next, operation, "DONE"); throw new AssertionError("Foreign owner confirmed a transaction"); }
            catch (IllegalStateException expected) { }
            try { a.checkpointPlayer(player, first, operation, "DONE"); throw new AssertionError("Stale session confirmed a transaction"); }
            catch (IllegalStateException expected) { }
            try { a.checkpointPlayer(player, next, UUID.randomUUID(), "DONE"); throw new AssertionError("Wrong operation confirmed a transaction"); }
            catch (IllegalStateException expected) { }
            assert a.playerCheckpoint(player).state().equals("PREPARED");
            a.checkpointPlayer(player, next, operation, "CURSOR");
            a.checkpointPlayer(player, next, operation, "DONE");
            UUID quarantined = UUID.randomUUID();
            a.preparePlayerOnly(player, next, quarantined, operation, new byte[]{1});
            a.checkpointPlayer(player, next, quarantined, "QUARANTINED");
            try { a.checkpointPlayer(player, next, quarantined, "DONE"); throw new AssertionError("Quarantine was cleared"); }
            catch (IllegalStateException expected) { }
            assert a.playerCheckpoint(player).state().equals("QUARANTINED");
        }
        try (var store = TrashStore.sqlite(database, logger)) {
            assert !store.acquirePlayerSession(player, other, first) : "Restart expired foreign ownership";
            assert store.ownsPlayerSession(player, owner, next);
            store.releasePlayerSession(player, owner, next);
            assert store.acquirePlayerSession(player, other, first) : "Verified release prevented handoff";
        }
        System.out.println("PlayerSessionCheck: ALL OK");
    }
}
