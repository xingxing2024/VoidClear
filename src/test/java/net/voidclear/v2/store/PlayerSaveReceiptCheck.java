package net.voidclear.v2.store;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

public final class PlayerSaveReceiptCheck {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempDirectory(Path.of("target"), "player-receipt-").resolve("player.dat");
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(10); output.writeUTF("BukkitValues");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF("expected-token");
            output.writeByte(0); output.writeByte(0);
        }
        PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token");
        assert PlayerSaveReceipt.read(file, "voidclear:player_receipt").equals("expected-token");
        assert PlayerSaveReceipt.read(file, "voidclear:absent") == null;
        byte[] valid = Files.readAllBytes(file);
        byte[] corrupt = valid.clone(); corrupt[corrupt.length - 8] ^= 1;
        Files.write(file, corrupt);
        try { PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Corrupt gzip checksum accepted"); }
        catch (IOException expected) { }
        Files.write(file, java.util.Arrays.copyOf(valid, valid.length - 4));
        try { PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Truncated gzip trailer accepted"); }
        catch (IOException expected) { }
        Files.write(file, valid);
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF("");
            for (String value : new String[]{"expected-token", "stale-token"}) {
                output.writeByte(10); output.writeUTF("BukkitValues");
                output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF(value);
                output.writeByte(0);
            }
            output.writeByte(0);
        }
        try { PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Duplicate PDC compound accepted"); }
        catch (IOException expected) { }
        Files.write(file, valid);
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(10); output.writeUTF("BukkitValues");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF("expected-token");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF("stale-token");
            output.writeByte(0); output.writeByte(0);
        }
        try { PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Duplicate receipt with later stale value accepted"); }
        catch (IOException expected) { }
        Files.write(file, valid);
        try { PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "wrong-token"); throw new AssertionError("Stale receipt accepted"); }
        catch (IOException expected) { }
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF("expected-token");
            output.writeByte(0);
        }
        try { PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Non-PDC token accepted"); }
        catch (IOException expected) { }
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF(""); output.writeByte(7); output.writeUTF("bad"); output.writeInt(Integer.MAX_VALUE);
        }
        try { PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Unbounded length accepted"); }
        catch (IOException expected) { }
        try { PlayerSaveReceipt.read(file, "voidclear:absent"); throw new AssertionError("Malformed file treated as missing receipt"); }
        catch (IOException expected) { }
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(10); output.writeUTF("BukkitValues");
            output.writeByte(3); output.writeUTF("voidclear:player_receipt"); output.writeInt(1);
            output.writeByte(0); output.writeByte(0);
        }
        try { PlayerSaveReceipt.read(file, "voidclear:player_receipt"); throw new AssertionError("Wrong receipt type treated as absent"); }
        catch (IOException expected) { }
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF(""); output.writeByte(0);
        }
        assert PlayerSaveReceipt.read(file, "voidclear:player_receipt") == null;
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(10); output.writeUTF("BukkitValues");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF("expected-token");
            output.writeByte(0);
            output.writeByte(9); output.writeUTF("large-list"); output.writeByte(3); output.writeInt(16384);
            for (int i = 0; i < 16384; i++) output.writeInt(i);
            output.writeByte(0);
        }
        PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", "expected-token");
        byte[] buffered = Files.readAllBytes(file); buffered[buffered.length - 8] ^= 1;
        Files.write(file, buffered);
        try { PlayerSaveReceipt.read(file, "voidclear:player_receipt"); throw new AssertionError("Buffered read skipped the late gzip checksum"); }
        catch (IOException expected) { }
        byte[] rich = fixture("expected-token", true), expectedClean = fixture(null, true);
        byte[] cleaned = PlayerSaveReceipt.without(rich, "voidclear:player_receipt", "expected-token");
        assert PlayerSaveReceipt.read(cleaned, "voidclear:player_receipt") == null;
        assert java.util.Arrays.equals(unzip(cleaned), unzip(expectedClean)) : "Unrelated NBT bytes changed";
        assert PlayerSaveReceipt.read(cleaned, "other:keep").equals("\u0000中文\ud83d\udce6");
        try { PlayerSaveReceipt.without(rich, "voidclear:player_receipt", "stale"); throw new AssertionError("Stale reset accepted"); }
        catch (IOException expected) { }
        try { PlayerSaveReceipt.without(cleaned, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Absent reset accepted"); }
        catch (IOException expected) { }
        byte[] bad = rich.clone(); bad[bad.length - 8] ^= 1;
        try { PlayerSaveReceipt.without(bad, "voidclear:player_receipt", "expected-token"); throw new AssertionError("Reset ignored CRC"); }
        catch (IOException expected) { }
        System.out.println("PlayerSaveReceiptCheck: ALL OK");
    }

    static byte[] fixture(String receipt, boolean inventory) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(3); output.writeUTF("DataVersion"); output.writeInt(4671);
            output.writeByte(10); output.writeUTF("BukkitValues");
            output.writeByte(8); output.writeUTF("other:keep"); output.writeUTF("\u0000中文\ud83d\udce6");
            if (receipt != null) { output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF(receipt); }
            output.writeByte(7); output.writeUTF("other:bytes"); output.writeInt(4); output.write(new byte[]{0, -1, 10, 8});
            output.writeByte(10); output.writeUTF("other:nested");
            output.writeByte(8); output.writeUTF("voidclear:player_receipt"); output.writeUTF("lookalike");
            output.writeByte(0); output.writeByte(0);
            for (String name : new String[]{"Inventory", "EnderItems"}) {
                output.writeByte(9); output.writeUTF(name); output.writeByte(10); output.writeInt(inventory ? 1 : 0);
                if (inventory) {
                    output.writeByte(8); output.writeUTF("id"); output.writeUTF("minecraft:diamond");
                    output.writeByte(3); output.writeUTF("count"); output.writeInt(17);
                    output.writeByte(1); output.writeUTF("Slot"); output.writeByte(4);
                    output.writeByte(0);
                }
            }
            output.writeByte(9); output.writeUTF("Pos"); output.writeByte(6); output.writeInt(3);
            output.writeDouble(-0.0); output.writeDouble(64.5); output.writeDouble(Double.NaN);
            output.writeByte(0);
        }
        return bytes.toByteArray();
    }

    static byte[] unzip(byte[] bytes) throws IOException {
        try (var input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) { return input.readAllBytes(); }
    }
}
