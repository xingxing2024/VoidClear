package net.voidclear.v2.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class DiagnosticsCheck {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory(Path.of("target"), "diagnostics-");
        Path file = dir.resolve("bin.db");
        var store = TrashStore.sqlite(file, Logger.getLogger("DiagnosticsCheck"));
        try (store; var sql = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            UUID owner = UUID.randomUUID(), player = UUID.randomUUID(), session = UUID.randomUUID(), isolated = UUID.randomUUID();
            insert(sql, player, session, "DONE", 1000);
            insert(sql, player, session, "DROPS", 2000); // Historical source, absent from the latest player checkpoint.
            UUID quarantined = insert(sql, isolated, session, "QUARANTINED", 1);
            for (int index = 0; index < 12; index++) insert(sql, UUID.randomUUID(), UUID.randomUUID(), "PREPARED", 3000 + index);
            insert(sql, UUID.randomUUID(), UUID.randomUUID(), "CURSOR", 4000);
            try (var statement = sql.prepareStatement("INSERT INTO voidclear_trash_items_sessions VALUES (?,?,?)")) {
                statement.setString(1, isolated.toString()); statement.setString(2, owner.toString()); statement.setString(3, session.toString());
                statement.executeUpdate();
            }
            var before = snapshot(sql);
            var result = store.diagnostics();
            assert result.unfinished().equals(Map.of("DROPS", 1L, "QUARANTINED", 1L, "PREPARED", 12L, "CURSOR", 1L));
            assert result.sessions() == 1 && result.itemGroups() == 0;
            assert result.recent().size() == 10;
            assert result.recent().getFirst().operation().equals(quarantined) : "Quarantine must appear before newer routine operations";
            assert result.recent().getFirst().owner().equals(owner.toString());
            assert snapshot(sql).equals(before) : "Diagnostics changed recovery state, evidence, fences or revision";
            synchronized (store) {
                var background = java.util.concurrent.CompletableFuture.supplyAsync(store::diagnostics);
                assert background.get(5, java.util.concurrent.TimeUnit.SECONDS).revision() == result.revision()
                        : "Diagnostics must not wait on the player-thread store monitor";
            }
            var local = new DiagnosticReport.Local(Instant.now(), owner, false, 2, 1, 3, 1, 1);
            var lines = DiagnosticReport.lines(local, result);
            String text = String.join("\n", lines);
            assert text.contains("QUARANTINED=1") && text.contains(quarantined.toString()) && text.contains("SQLite");
            assert !text.contains("jdbc:") && !text.contains("evidence") && !text.contains(file.toString());
            Path report = DiagnosticReport.export(dir, lines);
            assert report.getFileName().toString().equals("diagnostics.txt") && Files.readAllLines(report).equals(lines);
            var failed = DiagnosticReport.lines(local, null);
            DiagnosticReport.export(dir, failed);
            assert Files.readAllLines(report).equals(failed) && String.join("", failed).contains("状态未知");
            assert DiagnosticReport.safe("&cAdmin\n\u001b[31m\u00a7r").equals("?cAdmin??[31m?r");
            var invalidDir = Files.writeString(dir.resolve("blocked"), "not a directory");
            try { DiagnosticReport.export(invalidDir, lines); throw new AssertionError("Export failure ignored"); }
            catch (IllegalStateException expected) { }
        }
        try { store.diagnostics(); throw new AssertionError("Closed database reported as healthy"); }
        catch (IllegalStateException expected) { }
        System.out.println("DiagnosticsCheck: ALL OK");
    }

    private static UUID insert(Connection sql, UUID player, UUID session, String state, long created) throws Exception {
        UUID operation = UUID.randomUUID();
        try (var statement = sql.prepareStatement("INSERT INTO voidclear_trash_items_player_ops VALUES (?,?,?,?,?,?)")) {
            statement.setString(1, operation.toString()); statement.setString(2, player.toString()); statement.setString(3, session.toString());
            statement.setString(4, state); statement.setBytes(5, new byte[]{1, 2, 3}); statement.setLong(6, created); statement.executeUpdate();
        }
        return operation;
    }

    private static List<String> snapshot(Connection sql) throws Exception {
        var result = new ArrayList<String>();
        for (String table : List.of("trash_items_meta", "trash_items_player_ops", "trash_items_players", "trash_items_sessions", "trash_items_item_fences")) {
            try (var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT * FROM voidclear_" + table + " ORDER BY 1")) {
                result.add(table);
                while (rows.next()) for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    result.add(rows.getMetaData().getColumnType(column) == java.sql.Types.BLOB
                            ? java.util.Arrays.toString(rows.getBytes(column)) : rows.getString(column));
            }
        }
        return result;
    }
}
