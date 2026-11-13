package net.voidclear.v2.store;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;

/** Manual investigation evidence only: never replay a partially applied transfer. */
public final class DeliveryFailureLog {
    private DeliveryFailureLog() { }

    public static Path write(Path directory, Properties evidence) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, System.currentTimeMillis() + "-", ".tmp");
        Path file = directory.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".properties");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                evidence.store(Channels.newOutputStream(channel), "VoidClear partial delivery; NEVER auto-replay or auto-compensate");
                channel.force(true);
            }
            // Fail closed on filesystems without atomic rename; never publish a partial final file.
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failed) {
            try { Files.deleteIfExists(temp); } catch (IOException cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
        // shortcut: directory force is best-effort (unsupported on Windows); this is not a crash-recovery WAL.
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        catch (IOException | UnsupportedOperationException | SecurityException ignored) { }
        return file;
    }
}
