package net.voidclear.v2.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

public final class HistoryLogCheck {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("voidclear-history-");
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                HistoryLogCheck.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, values) -> method.getName().equals("getDataFolder") ? dir.toFile() : null);
        HistoryLog log = new HistoryLog(plugin);
        for (int i = 0; i < 1002; i++) log.add("TEST actor=admin groups=" + i);
        assert log.recent(20).size() == 20;
        assert log.recent(20).get(19).endsWith("groups=1001");
        assert Files.readAllLines(dir.resolve("history.log")).size() == 1000;
        assert log.recent(0).isEmpty();
        log.add("SWEEP removed=42 collected_groups=3 duration_ms=2500");
        log.add("WIPE actor=Alex groups=2");
        assert log.matching(20, "WIPE", null).size() == 1;
        assert log.matching(20, null, "alex").size() == 1;
        var days = log.dailySweeps(1);
        assert days.size() == 1;
        assert days.getFirst().date().equals(LocalDate.now());
        assert days.getFirst().removed() == 42 && days.getFirst().groups() == 3;
        Path csv = log.exportDailyCsv(1);
        assert Files.readString(csv).contains("42,3,2500");
        java.util.List<Integer> order = new java.util.ArrayList<>();
        log.execute(() -> { log.add("ASYNC actor=Alex groups=1"); order.add(1); });
        log.execute(() -> {
            assert log.matching(20, "ASYNC", "Alex").size() == 1 : "Query must follow queued writes";
            order.add(2);
        });
        log.close();
        assert order.equals(java.util.List.of(1, 2)) : "Close must drain queued work in order";
        assert log.recent(1).getFirst().contains("ASYNC");
        try {
            log.execute(() -> {});
            throw new AssertionError("Closed history must reject new work");
        } catch (java.util.concurrent.RejectedExecutionException expected) { }
        Path migrationDir = Files.createTempDirectory("voidclear-history-migration-");
        org.bukkit.plugin.Plugin migrationPlugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                HistoryLogCheck.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, values) -> method.getName().equals("getDataFolder") ? migrationDir.toFile() : null);
        Path history = migrationDir.resolve("history.log");
        var oldSweep = java.time.ZonedDateTime.now().minusDays(50).toInstant()
                + " SWEEP removed=17 collected_groups=2 duration_ms=300";
        Files.write(history, java.util.Collections.nCopies(1000, oldSweep));
        try (var upgraded = new HistoryLog(migrationPlugin)) {
            upgraded.add("SWEEP removed=6 collected_groups=1 duration_ms=10");
        }
        try (var restarted = new HistoryLog(migrationPlugin)) {
            assert Files.readAllLines(history).size() == 1000;
            var totals = restarted.dailySweeps(365);
            assert totals.size() == 2;
            assert totals.getFirst().removed() == 17000 : "Trimming recent history lost a daily total";
            assert totals.getLast().removed() == 6 : "Restart counted a sweep twice";
            assert Files.readString(restarted.exportDailyCsv(365)).contains(",17000,2000,300000");
        }
        // A version without the archive can still append to history.log during a rollback.
        var rollbackLine = java.time.Instant.now() + " SWEEP removed=9 collected_groups=1 duration_ms=20\n";
        Files.writeString(history, rollbackLine, java.nio.file.StandardOpenOption.APPEND);
        try (var restarted = new HistoryLog(migrationPlugin)) {
            assert restarted.dailySweeps(1).getFirst().removed() == 15 : "A sweep written during rollback was omitted";
            Path obsolete = migrationDir.resolve("sweep-daily.csv");
            Files.writeString(obsolete, "corrupted old candidate rollup\n");
            restarted.add("SWEEP removed=4 collected_groups=1 duration_ms=5");
            assert restarted.dailySweeps(1).getFirst().removed() == 19;
            assert Files.readString(obsolete).startsWith("corrupted");
        }
        try (var restarted = new HistoryLog(migrationPlugin)) {
            Path savedHistory = Files.move(history, migrationDir.resolve("history.saved"));
            Files.createDirectory(history);
            try {
                restarted.add("SWEEP removed=99 collected_groups=1 duration_ms=5");
                throw new AssertionError("Writing to a directory must fail");
            } catch (IllegalStateException expected) { }
            Files.delete(history);
            Files.move(savedHistory, history);
            assert restarted.dailySweeps(1).getFirst().removed() == 19 : "A failed append inflated the daily total";
        }
        try (var restarted = new HistoryLog(migrationPlugin)) {
            var utcDay = java.time.Instant.parse(oldSweep.split(" ", 2)[0]).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            Path archivedDay = migrationDir.resolve("sweep-history").resolve(utcDay + ".log");
            byte[] saved = Files.readAllBytes(archivedDay);
            Files.writeString(archivedDay, "damaged archive\n");
            try {
                restarted.add("SWEEP removed=3 collected_groups=1 duration_ms=5");
                throw new AssertionError("Corrupt archive must report the failed rotation");
            } catch (IllegalStateException expected) { }
            assert restarted.recent(1).getFirst().contains("removed=3") : "Archive error prevented logging";
            assert Files.readAllLines(history).size() > 1000 : "Archive error discarded the unarchived rows";
            assert Files.readString(archivedDay).equals("damaged archive\n");
            Files.write(archivedDay, saved);
            restarted.add("WIPE actor=Alex groups=1");
            assert Files.readAllLines(history).size() == 1000;
            assert restarted.dailySweeps(365).getFirst().removed() == 17000 : "Rotation retry double-counted archived rows";
            assert restarted.dailySweeps(1).getFirst().removed() == 22;
        }
        Path stale = migrationDir.resolve("sweep-history").resolve(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(400) + ".log");
        Files.writeString(stale, "expired sweep\n");
        Files.write(history, java.util.Collections.nCopies(1000, java.time.Instant.now() + " TEST actor=admin"));
        try (var restarted = new HistoryLog(migrationPlugin)) {
            restarted.add("TEST actor=admin");
            assert !Files.exists(stale) : "Expired archives must be pruned even when rotating non-sweep records";
            assert restarted.dailySweeps(365).getFirst().removed() == 17000;
        }
        System.out.println("HistoryLogCheck: ALL OK");
    }
}
