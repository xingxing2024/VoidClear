package net.voidclear.v2.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Explicit current-inventory baseline reset, never recovery or replay of a missing transaction. */
public final class ReceiptCleanup {
    private final Path players, backups;
    private final String key;
    private Plan pending;
    public record Preview(UUID player, UUID receipt, String token, Instant expires) { }
    private record Plan(Preview preview, byte[] main, byte[] old, byte[] cleanMain, byte[] cleanOld) { }

    public ReceiptCleanup(Path players, Path backups, String key) {
        this.players = players.toAbsolutePath().normalize();
        this.backups = backups.toAbsolutePath().normalize();
        this.key = key;
    }

    public synchronized Preview preview(UUID player, TrashStore store) {
        pending = null;
        try {
            byte[] main = read(file(player, false)), old = optional(file(player, true));
            String receipt = PlayerSaveReceipt.read(main, key);
            if (receipt == null) throw new IOException("实际存档没有旧收据，无需清理");
            UUID operation = UUID.fromString(receipt);
            if (!operation.toString().equalsIgnoreCase(receipt)) throw new IOException("收据不是完整UUID");
            byte[] cleanMain = PlayerSaveReceipt.without(main, key, receipt), cleanOld = old;
            if (old != null) {
                String fallback = PlayerSaveReceipt.read(old, key);
                if (fallback != null && !fallback.equals(receipt)) throw new IOException("备用存档的收据不同，拒绝猜测");
                if (fallback != null) cleanOld = PlayerSaveReceipt.without(old, key, receipt);
            }
            var preview = new Preview(player, operation, UUID.randomUUID().toString(), Instant.now().plusSeconds(300));
            var plan = new Plan(preview, main, old, cleanMain, cleanOld);
            store.withOrphanReceipt(player, operation, () -> true);
            pending = plan;
            return preview;
        } catch (IOException failed) { throw new UncheckedIOException(failed); }
    }

    public synchronized Path confirm(UUID player, String token, TrashStore store) {
        Plan plan = pending;
        if (plan == null || !plan.preview.player.equals(player) || !plan.preview.token.equals(token)
                || !Instant.now().isBefore(plan.preview.expires))
            throw new IllegalStateException("确认码无效或已过期，请重新预览");
        pending = null; // One attempt only; a partial file replacement must never be blindly replayed.
        return store.withOrphanReceipt(player, plan.preview.receipt, () -> apply(plan));
    }

    private Path apply(Plan plan) {
        Path directory = backups.resolve(plan.preview.player + "-" + plan.preview.token);
        try {
            unchanged(plan);
            Files.createDirectories(backups);
            if (!backups.toRealPath().equals(backups.toAbsolutePath().normalize())) throw new IOException("备份路径含链接，拒绝写入");
            Files.createDirectory(directory);
            write(directory.resolve(plan.preview.player + ".dat"), plan.main);
            if (plan.old != null) write(directory.resolve(plan.preview.player + ".dat_old"), plan.old);
            String record = "time=" + Instant.now() + "\nplayer=" + plan.preview.player + "\nreceipt=" + plan.preview.receipt
                    + "\nbaseline=current-saved-inventory; no compensation; no database changes\n"
                    + "main.before=" + hash(plan.main) + "\nmain.after=" + hash(plan.cleanMain)
                    + "\nold.before=" + hash(plan.old) + "\nold.after=" + hash(plan.cleanOld) + "\n";
            write(directory.resolve("reset-plan.txt"), record.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!Arrays.equals(read(directory.resolve(plan.preview.player + ".dat")), plan.main)
                    || plan.old != null && !Arrays.equals(read(directory.resolve(plan.preview.player + ".dat_old")), plan.old))
                throw new IOException("备份校验失败");
            unchanged(plan);
            // Keep the active receipt until its fallback is safe; failure never restores an old inventory.
            if (plan.old != null && !Arrays.equals(plan.old, plan.cleanOld))
                replace(file(plan.preview.player, true), plan.old, plan.cleanOld);
            replace(file(plan.preview.player, false), plan.main, plan.cleanMain);
            if (PlayerSaveReceipt.read(file(plan.preview.player, false), key) != null
                    || plan.old != null && PlayerSaveReceipt.read(file(plan.preview.player, true), key) != null)
                throw new IOException("清理后的收据复核失败");
            write(directory.resolve("completed.txt"), ("completed=" + Instant.now() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return directory;
        } catch (IOException failed) {
            throw new IllegalStateException("收据清理未确认；保留备份核查，禁止补发或直接回放存档。备份目录：" + directory, failed);
        }
    }

    private void unchanged(Plan plan) throws IOException {
        if (!Arrays.equals(read(file(plan.preview.player, false)), plan.main)
                || !Arrays.equals(optional(file(plan.preview.player, true)), plan.old))
            throw new IOException("玩家存档在预览后已变化，请重新核查");
    }

    private Path file(UUID player, boolean old) { return players.resolve(player + (old ? ".dat_old" : ".dat")); }
    private static byte[] optional(Path path) throws IOException { return Files.notExists(path, LinkOption.NOFOLLOW_LINKS) ? null : read(path); }
    private static byte[] read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.getParent().toRealPath().equals(path.getParent().toAbsolutePath().normalize()))
            throw new IOException("玩家存档不是普通文件或路径含链接：" + path.getFileName());
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("玩家存档过大");
            return bytes;
        }
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void replace(Path path, byte[] before, byte[] after) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".voidclear-receipt-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(after);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!Arrays.equals(read(temporary), after) || !Arrays.equals(read(path), before)) throw new IOException("原子替换前存档已变化");
            // No non-atomic fallback: an unsupported filesystem must leave the original intact.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!Arrays.equals(read(path), after)) throw new IOException("原子替换后校验失败");
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String hash(byte[] bytes) {
        if (bytes == null) return "absent";
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
