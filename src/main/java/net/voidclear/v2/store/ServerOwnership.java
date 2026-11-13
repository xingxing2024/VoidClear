package net.voidclear.v2.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Local process exclusion is required before reusing this installation's durable SQL ownership. */
public final class ServerOwnership implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final UUID id;
    private final Thread shutdownHook;

    public ServerOwnership(Path file) throws IOException {
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("本服会话归属文件正被其他进程占用");
            if (channel.size() == 0) {
                id = UUID.randomUUID();
                ByteBuffer bytes = StandardCharsets.US_ASCII.encode(id.toString());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            } else {
                if (channel.size() != 36) throw new IOException("本服会话归属文件损坏，禁止自动换身份接管");
                ByteBuffer bytes = ByteBuffer.allocate(36);
                while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("归属文件截断");
                bytes.flip();
                id = UUID.fromString(StandardCharsets.US_ASCII.decode(bytes).toString());
            }
            lock = acquired;
            shutdownHook = new Thread(() -> {
                try { channel.close(); } catch (IOException ignored) { }
            }, "VoidClear-ownership-close");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IOException | RuntimeException failed) {
            if (acquired != null) try { acquired.release(); } catch (IOException close) { failed.addSuppressed(close); }
            try { channel.close(); } catch (IOException close) { failed.addSuppressed(close); }
            throw failed;
        }
    }

    public UUID id() { return id; }

    @Override public void close() throws IOException {
        try { lock.release(); } finally {
            channel.close();
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
    }
}
