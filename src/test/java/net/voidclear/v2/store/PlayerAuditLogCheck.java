package net.voidclear.v2.store;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlayerAuditLogCheck {
    private static final class Stack extends ItemStack {
        private final Material material;
        private final int amount;
        private final String variant;
        Stack(Material material, int amount, String variant) { this.material = material; this.amount = amount; this.variant = variant; }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public boolean isEmpty() {
            return amount <= 0 || material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
        }
        @Override public boolean isSimilar(ItemStack other) {
            return other instanceof Stack stack && material == stack.material && variant.equals(stack.variant);
        }
    }

    public static void main(String[] args) throws Exception {
        var diamond = new Stack(Material.DIAMOND, 64, "plain");
        assert PlayerAuditLog.movements(new ItemStack[]{diamond}, new ItemStack[]{null}, List.of(0))
                .equals(List.of(new PlayerAuditLog.Movement("WITHDRAW", "minecraft:diamond", 64)));
        assert PlayerAuditLog.movements(new ItemStack[]{null}, new ItemStack[]{diamond}, List.of(0))
                .equals(List.of(new PlayerAuditLog.Movement("DEPOSIT", "minecraft:diamond", 64)));
        assert PlayerAuditLog.movements(new ItemStack[]{diamond, null, diamond},
                new ItemStack[]{new Stack(Material.DIAMOND, 32, "plain"), new Stack(Material.DIAMOND, 32, "plain"), null}, List.of(0, 1)).isEmpty()
                : "Rearrangement or a changed decoration must not create audit movements";
        var exchanged = PlayerAuditLog.movements(new ItemStack[]{diamond},
                new ItemStack[]{new Stack(Material.DIAMOND, 64, "named")}, List.of(0));
        assert exchanged.size() == 2 && exchanged.stream().mapToLong(PlayerAuditLog.Movement::amount).sum() == 128
                : "Different metadata variants of the same material must retain both directions";
        assert PlayerAuditLog.movements(new ItemStack[]{diamond}, new ItemStack[]{diamond}, List.of(0)).isEmpty();
        for (Material empty : List.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.DIAMOND)) {
            int amount = empty == Material.DIAMOND ? 0 : 1;
            assert PlayerAuditLog.movements(new ItemStack[]{new Stack(empty, amount, "empty")}, new ItemStack[]{null}, List.of(0)).isEmpty();
        }
        try { PlayerAuditLog.movements(new ItemStack[1], new ItemStack[2], List.of(0)); throw new AssertionError("Sizes accepted"); }
        catch (IllegalArgumentException expected) { }

        Path dir = Files.createTempDirectory(Path.of("target"), "player-audit-");
        var log = new PlayerAuditLog(dir);
        UUID player = UUID.randomUUID(), server = UUID.randomUUID(), operation = UUID.randomUUID();
        var deposit = entry(player, "Alex", server, operation, "DEPOSIT", 64);
        var withdrawal = entry(player, "Alex", server, operation, "WITHDRAW", 32);
        log.append(List.of(deposit, entry(UUID.randomUUID(), "Alexander", server, operation, "DEPOSIT", 1), withdrawal));
        assert log.matching(20, null, "alex").equals(List.of(deposit, withdrawal)) : "Player filtering must be exact";
        assert log.matching(20, "WITHDRAW", player.toString()).equals(List.of(withdrawal));
        assert new PlayerAuditLog(dir).matching(1, null, null).equals(List.of(withdrawal)) : "Restart must preserve recent records";
        var escaped = entry(UUID.randomUUID(), "名字\t&cAdmin\nOther+%", server, operation, "DEPOSIT", 2);
        log.append(List.of(escaped));
        assert Files.readAllLines(dir.resolve("player-audit.log")).size() == 4;
        assert log.matching(1, null, null).equals(List.of(escaped)) : "Escaping must round trip without forging records";
        for (String[] query : new String[][]{{"0", "DEPOSIT"}, {"21", "DEPOSIT"}, {"1", "SWEEP"}}) {
            try { log.matching(Integer.parseInt(query[0]), query[1], null); throw new AssertionError("Invalid query accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        var plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(PlayerAuditLogCheck.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, (proxy, method, values) -> method.getName().equals("getDataFolder") ? dir.toFile() : null);
        var history = new HistoryLog(plugin);
        var queried = new java.util.concurrent.CompletableFuture<List<PlayerAuditLog.Entry>>();
        history.execute(() -> log.append(List.of(deposit)));
        history.execute(() -> queried.complete(log.matching(1, null, null)));
        history.close();
        assert queried.join().equals(List.of(deposit)) : "Queries and shutdown must follow pending appends";

        Path rotating = Files.createDirectory(dir.resolve("rotating"));
        var rotated = new PlayerAuditLog(rotating);
        rotated.append(List.of(deposit));
        long firstSize = Files.size(rotating.resolve("player-audit.log"));
        try (var out = Files.newOutputStream(rotating.resolve("player-audit.log"), StandardOpenOption.APPEND)) {
            out.write(" ".repeat(PlayerAuditLog.MAX_FILE_BYTES - Math.toIntExact(firstSize)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        rotated.append(List.of(withdrawal));
        assert Files.size(rotating.resolve("player-audit.log.1")) == PlayerAuditLog.MAX_FILE_BYTES;
        assert rotated.matching(20, null, null).equals(List.of(deposit, withdrawal)) : "Queries must include the rotated file";
        Files.writeString(rotating.resolve("player-audit.log"), "broken record\n", StandardOpenOption.APPEND);
        try { rotated.matching(20, null, null); throw new AssertionError("Corrupt records reported as an empty audit"); }
        catch (IllegalStateException expected) { }
        Path blocked = Files.writeString(dir.resolve("blocked"), "not a directory");
        try { new PlayerAuditLog(blocked).append(List.of(deposit)); throw new AssertionError("Failed append was ignored"); }
        catch (IllegalStateException expected) { }
        assert !Files.exists(dir.resolve("history.log")) : "Trade audit must not replace sweep history";
        System.out.println("PlayerAuditLogCheck: ALL OK");
    }

    private static PlayerAuditLog.Entry entry(UUID player, String name, UUID server, UUID operation, String action, long amount) {
        return new PlayerAuditLog.Entry(Instant.parse("2026-11-10T00:00:00Z"), player, name, server, operation,
                new PlayerAuditLog.Movement(action, "minecraft:diamond", amount));
    }
}
