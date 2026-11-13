package net.voidclear.v2.store;

import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Local, bounded audit only; never a recovery or compensation source. Calls run on the history worker. */
public final class PlayerAuditLog {
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final Path file;
    private final Path previous;

    public record Movement(String action, String material, long amount) {
        public Movement {
            if (!List.of("DEPOSIT", "WITHDRAW").contains(action)
                    || material == null || !material.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || amount <= 0)
                throw new IllegalArgumentException("Invalid audit movement");
        }
    }

    public record Entry(Instant time, UUID player, String name, UUID server, UUID operation, Movement movement) {
        public Entry {
            java.util.Objects.requireNonNull(time); java.util.Objects.requireNonNull(player);
            java.util.Objects.requireNonNull(server); java.util.Objects.requireNonNull(operation);
            java.util.Objects.requireNonNull(movement);
            if (name == null || name.isBlank() || name.length() > 128) throw new IllegalArgumentException("Invalid audit player");
        }
    }

    public PlayerAuditLog(Path directory) {
        file = directory.resolve("player-audit.log");
        previous = directory.resolve("player-audit.log.1");
    }

    /** Compare similar item variants on the player thread, then discard Bukkit objects before queuing I/O. */
    public static List<Movement> movements(ItemStack[] before, ItemStack[] after, List<Integer> slots) {
        if (before.length != after.length) throw new IllegalArgumentException("Different audit inventory sizes");
        var variants = new ArrayList<ItemStack>();
        var amounts = new ArrayList<Long>();
        for (int slot : slots) {
            if (slot < 0 || slot >= before.length) throw new IllegalArgumentException("Invalid audit slot");
            accumulate(variants, amounts, before[slot], -1);
            accumulate(variants, amounts, after[slot], 1);
        }
        var totals = new LinkedHashMap<String, Movement>();
        for (int index = 0; index < variants.size(); index++) {
            long delta = amounts.get(index);
            if (delta == 0) continue;
            String action = delta > 0 ? "DEPOSIT" : "WITHDRAW";
            String material = variants.get(index).getType().getKey().toString();
            String key = action + " " + material;
            long amount = Math.abs(delta) + (totals.containsKey(key) ? totals.get(key).amount() : 0);
            totals.put(key, new Movement(action, material, amount));
        }
        return List.copyOf(totals.values());
    }

    private static void accumulate(List<ItemStack> variants, List<Long> amounts, ItemStack stack, int sign) {
        if (stack == null || stack.isEmpty()) return;
        for (int index = 0; index < variants.size(); index++) if (stack.isSimilar(variants.get(index))) {
            amounts.set(index, amounts.get(index) + (long) sign * stack.getAmount());
            return;
        }
        variants.add(stack); amounts.add((long) sign * stack.getAmount());
    }

    public synchronized void append(List<Entry> entries) {
        if (entries.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        for (Entry entry : entries) text.append(entry.time()).append('\t').append(entry.player()).append('\t')
                .append(URLEncoder.encode(entry.name(), StandardCharsets.UTF_8)).append('\t').append(entry.server()).append('\t')
                .append(entry.operation()).append('\t').append(entry.movement().action()).append('\t')
                .append(entry.movement().material()).append('\t').append(entry.movement().amount()).append('\n');
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Audit batch too large");
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) + bytes.length > MAX_FILE_BYTES) {
                try { Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failed) { throw new IllegalStateException("无法写入存取审计", failed); }
    }

    public synchronized List<Entry> matching(int count, String action, String player) {
        if (count < 1 || count > 20 || action != null && !List.of("DEPOSIT", "WITHDRAW").contains(action))
            throw new IllegalArgumentException("Invalid audit query");
        var found = new ArrayList<Entry>();
        for (Path source : List.of(previous, file)) {
            try {
                if (!Files.exists(source)) continue;
                if (Files.size(source) > MAX_FILE_BYTES) throw new IOException("Audit file exceeds its limit");
                try (var lines = Files.lines(source, StandardCharsets.UTF_8)) {
                    lines.filter(line -> !line.isBlank()).map(PlayerAuditLog::parse)
                            .filter(entry -> action == null || entry.movement().action().equals(action))
                            .filter(entry -> player == null || entry.name().equalsIgnoreCase(player)
                                    || entry.player().toString().equalsIgnoreCase(player))
                            .forEach(entry -> { if (found.size() == count) found.removeFirst(); found.add(entry); });
                }
            } catch (IOException | IllegalArgumentException failed) { throw new IllegalStateException("无法读取存取审计", failed); }
        }
        return List.copyOf(found);
    }

    private static Entry parse(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 8) throw new IllegalArgumentException("Invalid audit record");
        return new Entry(Instant.parse(fields[0]), UUID.fromString(fields[1]), URLDecoder.decode(fields[2], StandardCharsets.UTF_8),
                UUID.fromString(fields[3]), UUID.fromString(fields[4]), new Movement(fields[5], fields[6], Long.parseLong(fields[7])));
    }
}
