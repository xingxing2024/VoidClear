import java.io.DataOutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

/** Opt-in comparison of packaged receipt readers; excludes Bukkit saves and fsync. */
public final class ReceiptReadBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java ReceiptReadBenchmark.java <before.jar> <after.jar>");
        Path fixture = Files.createTempDirectory(Path.of("target"), "receipt-read-benchmark-").resolve("player.dat");
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(fixture)))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(10); output.writeUTF("BukkitValues");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF("benchmark-token");
            output.writeByte(0);
            output.writeByte(9); output.writeUTF("primitive-list"); output.writeByte(3); output.writeInt(16384);
            for (int i = 0; i < 16384; i++) output.writeInt(i);
            output.writeByte(0);
        }
        try (var before = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()}, null);
             var after = new URLClassLoader(new java.net.URL[]{Path.of(args[1]).toUri().toURL()}, null)) {
            var methods = new java.lang.reflect.Method[2];
            var loaders = new ClassLoader[]{before, after};
            for (int i = 0; i < 2; i++) methods[i] = Class.forName("net.voidclear.v2.store.PlayerSaveReceipt", true, loaders[i])
                    .getMethod("read", Path.class, String.class);
            for (int warmup = 0; warmup < 40; warmup++) for (var method : methods) read(method, fixture);
            double[][] samples = new double[2][7];
            for (int batch = 0; batch < 7; batch++) for (int turn = 0; turn < 2; turn++) {
                int i = (batch + turn) % 2;
                long start = System.nanoTime();
                for (int repeat = 0; repeat < 20; repeat++) read(methods[i], fixture);
                samples[i][batch] = (System.nanoTime() - start) / 20_000_000.0;
            }
            for (int i = 0; i < 2; i++) {
                Arrays.sort(samples[i]);
                String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[i]))));
                System.out.println("{\"sha256\":\"" + hash + "\",\"primitiveInts\":16384,\"batches\":7,\"readsPerBatch\":20,\"medianReadMs\":"
                        + samples[i][3] + ",\"includesSaveOrFsync\":false}");
            }
        }
    }

    private static void read(java.lang.reflect.Method method, Path fixture) throws Exception {
        if (!"benchmark-token".equals(method.invoke(null, fixture, "voidclear:player_receipt")))
            throw new AssertionError("Receipt reader returned a different token");
    }
}
