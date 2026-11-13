package net.voidclear.v2.store;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Read the actual named PDC tag, never search arbitrary compressed bytes for a token. */
public final class PlayerSaveReceipt {
    private PlayerSaveReceipt() { }

    public static void verifyAndForce(Path file, String key, String token) throws IOException {
        if (!java.util.Objects.requireNonNull(token).equals(read(file, key)))
            throw new IOException("玩家存档缺少本次交易收据");
        // saveData returns void and can swallow IO errors. Verify the new token first, then force that file.
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    /** Null means the validated file has no such PDC key, never that the file could not be read. */
    public static String read(Path file, String key) throws IOException {
        if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("玩家存档过大，无法验证");
        try (var input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            return parse(input, key, null);
        }
    }

    static String read(byte[] compressed, String key) throws IOException {
        if (compressed.length > 16 * 1024 * 1024) throw new IOException("玩家存档过大");
        try (var input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed))))) {
            return parse(input, key, null);
        }
    }

    /** Remove just the validated named tag; every other uncompressed byte stays identical. */
    static byte[] without(byte[] compressed, String key, String expected) throws IOException {
        if (compressed.length > 16 * 1024 * 1024) throw new IOException("玩家存档过大");
        byte[] raw;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            raw = gzip.readNBytes(32 * 1024 * 1024 + 1);
            if (raw.length > 32 * 1024 * 1024) throw new IOException("玩家存档解压超限");
        }
        var range = new Range(raw);
        String actual = parse(new DataInputStream(range.input), key, range);
        if (expected == null || !expected.equals(actual) || range.start < 0) throw new IOException("收据已变化或不存在");
        var changed = new ByteArrayOutputStream(raw.length);
        changed.write(raw, 0, range.start);
        changed.write(raw, range.end, raw.length - range.end);
        byte[] result = changed.toByteArray();
        if (parse(new DataInputStream(new ByteArrayInputStream(result)), key, null) != null)
            throw new IOException("收据移除验证失败");
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) { gzip.write(result); }
        return output.toByteArray();
    }

    private static String parse(DataInputStream input, String key, Range range) throws IOException {
        if (input.readUnsignedByte() != 10) throw new IOException("玩家存档根标签不是Compound");
        input.readUTF();
        Budget budget = new Budget();
        String token = compound(input, 0, false, key, budget, range);
        if (budget.remaining < 0 || input.read() != -1) throw new IOException("玩家存档超限或根标签后仍有数据");
        return token;
    }

    private static final class Range {
        final ByteArrayInputStream input;
        final int size;
        int start = -1, end;
        Range(byte[] raw) { input = new ByteArrayInputStream(raw); size = raw.length; }
        int position() { return size - input.available(); }
    }

    private static final class Budget { long remaining = 32L * 1024 * 1024; }

    private static int length(DataInputStream input, int width, Budget budget) throws IOException {
        int count = input.readInt();
        if (count < 0 || (long) count * width > budget.remaining) throw new IOException("NBT长度超限");
        return count;
    }

    private static String compound(DataInputStream input, int depth, boolean pdc, String key, Budget budget, Range range) throws IOException {
        if (depth > 64) throw new IOException("NBT嵌套超限");
        String found = null;
        var names = new java.util.HashSet<String>();
        while (true) {
            int start = range == null ? 0 : range.position();
            int type = input.readUnsignedByte();
            if (type == 0) break;
            if (--budget.remaining < 0) throw new IOException("NBT标签超限");
            String name = input.readUTF();
            budget.remaining -= name.length() * 2L;
            if (!names.add(name)) throw new IOException("NBT复合标签名称重复");
            if (depth == 0 && name.equals("BukkitValues") && type != 10) throw new IOException("玩家PDC类型无效");
            if (pdc && name.equals(key) && type != 8) throw new IOException("玩家收据类型无效");
            if (type == 8) {
                String value = input.readUTF(); budget.remaining -= value.length() * 2L;
                if (pdc && name.equals(key)) {
                    found = value;
                    if (range != null) {
                        range.start = start;
                        range.end = range.position();
                    }
                }
            } else if (type == 10) {
                String child = compound(input, depth + 1, depth == 0 && name.equals("BukkitValues"), key, budget, range);
                if (child != null) found = child;
            }
            else skip(input, type, depth + 1, budget);
        }
        return found;
    }

    private static void skip(DataInputStream input, int type, int depth, Budget budget) throws IOException {
        if (depth > 64 || budget.remaining < 0) throw new IOException("NBT超限");
        switch (type) {
            case 1 -> { input.readByte(); budget.remaining--; }
            case 2 -> { input.readShort(); budget.remaining -= 2; }
            case 3, 5 -> { input.readInt(); budget.remaining -= 4; }
            case 4, 6 -> { input.readLong(); budget.remaining -= 8; }
            case 7, 11, 12 -> {
                int width = type == 7 ? 1 : type == 11 ? 4 : 8;
                long bytes = (long) length(input, width, budget) * width;
                input.skipNBytes(bytes); budget.remaining -= bytes;
            }
            case 8 -> budget.remaining -= input.readUTF().length() * 2L;
            case 9 -> {
                int child = input.readUnsignedByte(), count = length(input, 1, budget);
                if (child < 0 || child > 12 || child == 0 && count != 0) throw new IOException("NBT列表类型无效");
                for (int i = 0; i < count; i++) { budget.remaining--; skip(input, child, depth + 1, budget); }
            }
            case 10 -> compound(input, depth, false, "", budget, null);
            default -> throw new IOException("NBT类型无效：" + type);
        }
    }
}
