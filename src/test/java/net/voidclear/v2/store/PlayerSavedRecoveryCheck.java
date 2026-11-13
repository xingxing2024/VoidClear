package net.voidclear.v2.store;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

public final class PlayerSavedRecoveryCheck {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "saved-recovery-");
        Path database = directory.resolve("bin.db"), file = directory.resolve("player.dat");
        UUID player = UUID.randomUUID(), session = UUID.randomUUID(), operation = UUID.randomUUID();
        byte[] evidence = ("planned.storage.length=36\nplanned.cursor.length=1\nplanned.cursor.0=empty\nplanned.drops.length=0\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try (TrashStore store = TrashStore.sqlite(database, Logger.getLogger("PlayerSavedRecoveryCheck"));
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            store.preparePlayerOnly(player, session, operation, null, evidence);
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Missing file released fence";
            receipt(file, UUID.randomUUID().toString());
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Old receipt released fence";
            receipt(file, operation.toString());
            byte[] valid = Files.readAllBytes(file), corrupt = valid.clone(); corrupt[corrupt.length - 8] ^= 1;
            Files.write(file, corrupt);
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Corrupt file released fence";
            Files.write(file, valid);
            for (String invalid : new String[]{"planned.cursor.0=nonempty", "planned.drops.length=1", "planned.storage.length=35"}) {
                String key = invalid.substring(0, invalid.indexOf('='));
                var properties = new java.util.Properties();
                properties.load(new java.io.ByteArrayInputStream(evidence));
                properties.setProperty(key, invalid.substring(invalid.indexOf('=') + 1));
                var output = new java.io.ByteArrayOutputStream(); properties.store(output, "invalid fixture");
                setEvidence(connection, operation, output.toByteArray());
                assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : invalid;
            }
            setEvidence(connection, operation, evidence);
            UUID source = UUID.randomUUID();
            store.registerPlayerDrop(source, operation, 0, new byte[]{1});
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Registered source ignored";
            // Test fixture only: no production cleanup API is introduced.
            connection.createStatement().executeUpdate("DELETE FROM voidclear_trash_items_player_drops");
            connection.createStatement().executeUpdate("CREATE TRIGGER refuse_recovery BEFORE UPDATE ON voidclear_trash_items_player_ops BEGIN SELECT RAISE(ABORT,'injected recovery failure'); END");
            try { store.recoverSavedPlayer(player, file, "voidclear:player_receipt"); throw new AssertionError("Failed journal update accepted"); }
            catch (IllegalStateException expected) { }
            assert store.playerCheckpoint(player).state().equals("PREPARED") : "Partial acknowledgment committed";
            assert store.replacePlayerPage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                    new byte[]{1}, 0, 1, List.of(), java.util.Collections.singletonList(null))
                    : "A player-only recovery failure blocked unrelated page transactions";
            connection.createStatement().executeUpdate("DROP TRIGGER refuse_recovery");
            assert store.recoverSavedPlayer(player, file, "voidclear:player_receipt");
            assert store.playerCheckpoint(player).state().equals("DONE");
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Completed operation recovered twice";
            assert store.replacePlayerPage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                    new byte[]{1}, 0, 1, List.of(), java.util.Collections.singletonList(null)) : "Page fence remained after proven confirmation";
            UUID next = UUID.randomUUID();
            store.preparePlayerOnly(player, session, next, operation, evidence);
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Old op confirmed new checkpoint";
            receipt(file, next.toString());
            store.checkpointPlayer(player, session, next, "QUARANTINED");
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Quarantine automatically released";
        }
        fullClaim(directory.resolve("claim.db"), directory.resolve("claim.dat"), evidence, false);
        fullClaim(directory.resolve("partial.db"), directory.resolve("partial.dat"), evidence, true);
        loadedDrop(directory.resolve("loaded.db"), directory.resolve("loaded.dat"));
        ownedRecovery(directory.resolve("owned.db"), directory.resolve("owned.dat"), evidence);
        System.out.println("PlayerSavedRecoveryCheck: ALL OK");
    }

    private static void ownedRecovery(Path database, Path file, byte[] evidence) throws Exception {
        UUID owner = UUID.randomUUID(), player = UUID.randomUUID(), previous = UUID.randomUUID();
        UUID current = UUID.randomUUID(), operation = UUID.randomUUID();
        try (var store = TrashStore.sqlite(database, Logger.getLogger("OwnedRecoveryCheck"));
             var foreign = TrashStore.sqlite(database, Logger.getLogger("ForeignRecoveryCheck"))) {
            store.bindSessionOwner(owner); foreign.bindSessionOwner(UUID.randomUUID());
            assert store.acquirePlayerSession(player, owner, previous);
            store.preparePlayerOnly(player, previous, operation, null, evidence);
            receipt(file, operation.toString());
            assert store.acquirePlayerSession(player, owner, current);
            try { foreign.recoverSavedPlayer(player, file, "voidclear:player_receipt"); throw new AssertionError("Foreign owner recovered a saved transaction"); }
            catch (IllegalStateException expected) { }
            assert store.playerCheckpoint(player).state().equals("PREPARED");
            assert store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Owned reconnect could not verify the prior session receipt";
            assert store.playerCheckpoint(player).session().equals(previous);
            assert store.playerCheckpoint(player).state().equals("DONE");
            assert store.ownsPlayerSession(player, owner, current) : "Recovery replaced current ownership";
        }
    }

    private static void fullClaim(Path database, Path file, byte[] plan, boolean partial) throws Exception {
        UUID player = UUID.randomUUID(), session = UUID.randomUUID(), parent = UUID.randomUUID();
        UUID operation = UUID.randomUUID(), source = UUID.randomUUID();
        try (TrashStore store = TrashStore.sqlite(database, Logger.getLogger("FullClaimRecoveryCheck"));
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            store.preparePlayerOnly(player, session, parent, null, plan);
            store.registerPlayerDrop(source, parent, 0, new byte[]{1});
            store.checkpointPlayer(player, session, parent, "DROPS");
            var properties = new java.util.Properties();
            properties.load(new java.io.ByteArrayInputStream(plan));
            properties.setProperty("claim.entity", source.toString());
            properties.setProperty("claim.parent", parent.toString());
            properties.setProperty("claim.sequence", "1");
            properties.setProperty("source", "AQ==");
            properties.setProperty("remainder", partial ? "Ag==" : "empty");
            var output = new java.io.ByteArrayOutputStream(); properties.store(output, "full claim");
            byte[] claim = output.toByteArray();
            store.preparePlayerOnly(player, session, operation, parent, claim);
            receipt(file, operation.toString());
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Missing source receipt accepted";
            try (var insert = connection.prepareStatement("INSERT INTO voidclear_trash_items_sweeps (entity_id,sequence,before_data,remainder_data) VALUES (?,1,?,?)")) {
                insert.setString(1, source.toString()); insert.setBytes(2, new byte[]{1}); insert.setBytes(3, new byte[]{2}); insert.executeUpdate();
            }
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Nonterminal source accepted";
            if (partial) {
                try (var insert = connection.prepareStatement("INSERT INTO voidclear_trash_items_sweeps (entity_id,sequence,before_data,remainder_data) VALUES (?,2,?,NULL)")) {
                    insert.setString(1, source.toString()); insert.setBytes(2, new byte[]{3}); insert.executeUpdate();
                }
                assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Broken source byte chain accepted";
                connection.createStatement().executeUpdate("UPDATE voidclear_trash_items_sweeps SET before_data=X'02' WHERE sequence=2");
            } else connection.createStatement().executeUpdate("UPDATE voidclear_trash_items_sweeps SET remainder_data=NULL");
            for (String invalid : new String[]{"claim.sequence=2", "claim.parent=" + UUID.randomUUID(), "source=Ag==", "remainder=AQ==", "planned.cursor.0=nonempty"}) {
                var changed = new java.util.Properties(); changed.putAll(properties);
                int equals = invalid.indexOf('='); changed.setProperty(invalid.substring(0, equals), invalid.substring(equals + 1));
                output.reset(); changed.store(output, "invalid claim");
                setEvidence(connection, operation, output.toByteArray());
                assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : invalid;
            }
            setEvidence(connection, operation, claim);
            receipt(file, UUID.randomUUID().toString());
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Stale claim player file accepted";
            receipt(file, operation.toString());
            connection.createStatement().executeUpdate("CREATE TRIGGER refuse_claim BEFORE UPDATE ON voidclear_trash_items_player_ops BEGIN SELECT RAISE(ABORT,'claim recovery failure'); END");
            try { store.recoverSavedPlayer(player, file, "voidclear:player_receipt"); throw new AssertionError("Partial claim confirmation committed"); }
            catch (IllegalStateException expected) { }
            assert store.playerCheckpoint(player).state().equals("PREPARED");
            connection.createStatement().executeUpdate("DROP TRIGGER refuse_claim");
            assert store.recoverSavedPlayer(player, file, "voidclear:player_receipt");
            assert store.playerCheckpoint(player).state().equals("DONE");
            assert store.unresolvedPlayerDrops(player) : "Recovery erased historical parent fence";
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Full claim confirmed twice";
            assert store.sweepReceipts(source).size() == (partial ? 2 : 1) : "Recovery replayed source";
        }
    }

    private static void setEvidence(java.sql.Connection connection, UUID operation, byte[] evidence) throws Exception {
        try (var update = connection.prepareStatement("UPDATE voidclear_trash_items_player_ops SET evidence=? WHERE op_id=?")) {
            update.setBytes(1, evidence); update.setString(2, operation.toString()); update.executeUpdate();
        }
    }

    private static void loadedDrop(Path database, Path file) throws Exception {
        UUID player = UUID.randomUUID(), session = UUID.randomUUID(), parent = UUID.randomUUID(), entity = UUID.randomUUID();
        byte[] evidence = "planned.storage.length=36\nplanned.cursor.length=1\nplanned.cursor.0=empty\nplanned.drops.length=1\nplanned.drops.0=AQ==\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try (var store = TrashStore.sqlite(database, Logger.getLogger("LoadedDropCheck"));
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            store.preparePlayerOnly(player, session, parent, null, evidence);
            var chunk = new TrashStore.DropChunk(UUID.randomUUID(), 62, -63);
            store.registerPlayerDrop(entity, parent, 0, new byte[]{1}, chunk);
            store.checkpointPlayer(player, session, parent, "DROPS");
            assert store.playerDropChunks(player).equals(java.util.List.of(chunk)) : "Drop chunk hint lost";
            var loaded = java.util.Map.of(entity, new TrashStore.LoadedDrop(0, new byte[]{1}));
            assert !store.verifiedPlayerDrops(player, java.util.Map.of()) : "Missing world source admitted";
            assert !store.verifiedPlayerDrops(player, java.util.Map.of(entity, new TrashStore.LoadedDrop(0, new byte[]{2}))) : "Wrong source admitted";
            assert store.verifiedPlayerDrops(player, loaded) : "Saved source refused";
            UUID operation = UUID.randomUUID();
            byte[] claim = "planned.storage.length=36\nplanned.cursor.length=1\nplanned.cursor.0=empty\nplanned.drops.length=0\nsource=AQ==\nremainder=Ag==\nclaim.sequence=1\nclaim.entity=ENTITY\nclaim.parent=PARENT\n"
                    .replace("ENTITY", entity.toString()).replace("PARENT", parent.toString()).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            store.preparePlayerOnly(player, session, operation, parent, claim);
            connection.createStatement().executeUpdate("INSERT INTO voidclear_trash_items_sweeps VALUES ('" + entity + "',1,X'01',X'02')");
            receipt(file, operation.toString());
            assert !store.recoverSavedPlayer(player, file, "voidclear:player_receipt") : "Unproven remainder admitted";
            assert store.recoverSavedPlayer(player, file, "voidclear:player_receipt", loaded) : "Proven saved partial claim refused";
            assert store.playerCheckpoint(player).state().equals("DONE");
            assert store.unresolvedPlayerDrops(player) : "Source evidence deleted";
            assert store.verifiedPlayerDrops(player, loaded) : "Saved old source cannot reconcile forward";
            connection.createStatement().executeUpdate("UPDATE voidclear_trash_items_sweeps SET before_data=X'03'");
            assert !store.verifiedPlayerDrops(player, loaded) : "Broken chain admitted";
        }
    }

    private static void receipt(Path file, String token) throws Exception {
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(10); output.writeUTF("BukkitValues");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF(token);
            output.writeByte(0); output.writeByte(0);
        }
    }
}
