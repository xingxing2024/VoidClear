package net.voidclear.v2.store;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small bounded operation log; entries intentionally contain counts, not item data. */
public final class HistoryLog implements AutoCloseable {
    private static final int LIMIT = 1000;
    private final Path file;
    private final Path archive;
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> new Thread(task, "VoidClear-history"));

    /** 同一队列保持记录写入与随后查询的顺序；停用时由 close 等待排队工作。 */
    public void execute(Runnable task) {
        worker.execute(task);
    }

    @Override
    public void close() {
        worker.shutdown();
        boolean interrupted = false;
        try {
            while (!worker.isTerminated()) {
                try {
                    worker.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException cut) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public record DailySweep(LocalDate date, long removed, long groups, long durationMillis) {
    }

    public HistoryLog(Plugin plugin) {
        file = plugin.getDataFolder().toPath().resolve("history.log");
        archive = file.resolveSibling("sweep-history");
    }

    public synchronized void add(String event) {
        try {
            Files.createDirectories(file.getParent());
            Instant now = Instant.now();
            Files.writeString(file, now + " " + event + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            List<String> lines = Files.readAllLines(file);
            if (lines.size() > LIMIT) {
                int cut = lines.size() - LIMIT;
                archiveBeforeTrim(lines, cut, now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(365));
                replace(file, lines.subList(cut, lines.size()));
            }
        } catch (IOException failed) {
            throw new IllegalStateException("无法写入操作记录", failed);
        }
    }

    public synchronized List<String> recent(int count) {
        List<String> lines = all();
        return List.copyOf(lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }

    public synchronized List<String> matching(int count, String type, String player) {
        List<String> found = recent(LIMIT).stream()
                .filter(line -> type == null || line.contains(" " + type.toUpperCase(java.util.Locale.ROOT) + " "))
                .filter(line -> player == null || line.toLowerCase(java.util.Locale.ROOT)
                        .contains("player=" + player.toLowerCase(java.util.Locale.ROOT))
                        || line.toLowerCase(java.util.Locale.ROOT).contains("actor=" + player.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        return found.subList(Math.max(0, found.size() - count), found.size());
    }

    public synchronized List<DailySweep> dailySweeps(int days) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(days - 1L);
        var totals = new TreeMap<LocalDate, DailySweep>();
        try {
            var daysWithRecords = sweepDays(all());
            try (var files = Files.newDirectoryStream(archive, "*.log")) {
                for (Path source : files) {
                    LocalDate day = archiveDay(source);
                    if (day == null || day.isBefore(start.minusDays(1)) || day.isAfter(today.plusDays(1))) continue;
                    var rows = daysWithRecords.computeIfAbsent(day, ignored -> new LinkedHashMap<>());
                    archived(day).forEach((line, count) -> rows.merge(line, count, Math::max));
                }
            } catch (java.nio.file.NoSuchFileException absent) {
                // Nothing has needed archiving yet; the live log is sufficient.
            }
            for (var rows : daysWithRecords.values()) rows.forEach((line, count) -> {
                int space = line.indexOf(' ');
                LocalDate local = Instant.parse(line.substring(0, space)).atZone(zone).toLocalDate();
                if (!local.isBefore(start) && !local.isAfter(today))
                    mergeSweep(totals, local, line.substring(space + 1), count);
            });
        } catch (IOException failed) {
            throw new IllegalStateException("无法读取清扫历史归档", failed);
        }
        return List.copyOf(totals.values());
    }

    private void archiveBeforeTrim(List<String> lines, int cut, LocalDate oldest) throws IOException {
        var evicted = sweepDays(lines.subList(0, cut));
        var live = sweepDays(lines);
        for (LocalDate day : evicted.keySet()) {
            if (day.isBefore(oldest)) continue;
            var rows = archived(day);
            live.get(day).forEach((line, count) -> rows.merge(line, count, Math::max));
            var snapshot = new ArrayList<String>();
            rows.forEach((line, count) -> snapshot.addAll(java.util.Collections.nCopies(count, line)));
            Files.createDirectories(archive);
            replace(archive.resolve(day + ".log"), snapshot);
        }
        if (!Files.isDirectory(archive)) return;
        try (var files = Files.newDirectoryStream(archive, "*.log")) {
            for (Path old : files) {
                LocalDate day = archiveDay(old);
                if (day != null && day.isBefore(oldest)) Files.deleteIfExists(old);
            }
        }
    }

    private static LocalDate archiveDay(Path path) {
        String name = path.getFileName().toString();
        try { return LocalDate.parse(name.substring(0, name.length() - 4)); }
        catch (java.time.format.DateTimeParseException unrelated) { return null; }
    }

    private Map<String, Integer> archived(LocalDate day) throws IOException {
        Path path = archive.resolve(day + ".log");
        var rows = new LinkedHashMap<String, Integer>();
        try {
            for (String line : Files.readAllLines(path)) {
                if (!day.equals(sweepDay(line))) throw new IOException("清扫历史归档损坏：" + path);
                rows.merge(line, 1, Integer::sum);
            }
        } catch (java.nio.file.NoSuchFileException absent) {
            // No sweep from this day has needed archiving.
        }
        return rows;
    }

    private static Map<LocalDate, Map<String, Integer>> sweepDays(List<String> lines) {
        var days = new TreeMap<LocalDate, Map<String, Integer>>();
        for (String line : lines) {
            LocalDate day = sweepDay(line);
            if (day != null) days.computeIfAbsent(day, ignored -> new LinkedHashMap<>()).merge(line, 1, Integer::sum);
        }
        return days;
    }

    private static LocalDate sweepDay(String line) {
        int space = line.indexOf(' ');
        if (space < 0 || !line.startsWith("SWEEP ", space + 1)) return null;
        try {
            field(line, "removed");
            field(line, "collected_groups");
            field(line, "duration_ms");
            return Instant.parse(line.substring(0, space)).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (java.time.format.DateTimeParseException | NumberFormatException invalid) {
            return null;
        }
    }

    private static void mergeSweep(Map<LocalDate, DailySweep> totals, LocalDate date, String event, int count) {
        var next = new DailySweep(date, field(event, "removed") * count, field(event, "collected_groups") * count, field(event, "duration_ms") * count);
        totals.merge(date, next, (a, b) -> new DailySweep(date, a.removed() + b.removed(),
                a.groups() + b.groups(), a.durationMillis() + b.durationMillis()));
    }

    private static String csvRow(DailySweep day) {
        return day.date() + "," + day.removed() + "," + day.groups() + "," + day.durationMillis();
    }

    private static void replace(Path path, List<String> lines) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, lines);
        try {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized Path exportDailyCsv(int days) {
        Path output = file.resolveSibling("sweep-stats.csv");
        List<String> rows = new ArrayList<>(List.of("date,removed,collected_groups,duration_ms"));
        dailySweeps(days).forEach(day -> rows.add(csvRow(day)));
        try {
            Files.write(output, rows);
            return output;
        } catch (IOException failed) {
            throw new IllegalStateException("无法导出清扫统计", failed);
        }
    }

    private List<String> all() {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException failed) {
            throw new IllegalStateException("无法读取操作记录", failed);
        }
    }

    private static long field(String event, String name) {
        for (String token : event.split(" ")) {
            if (token.startsWith(name + "=")) return Long.parseLong(token.substring(name.length() + 1));
        }
        return 0;
    }
}
