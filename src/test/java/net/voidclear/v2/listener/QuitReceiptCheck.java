package net.voidclear.v2.listener;

import net.voidclear.VoidClearPlugin;
import net.voidclear.v2.store.TrashStore;
import org.bukkit.NamespacedKey;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/** Real disk/SQLite checks: a quit notification is never proof of a native player save. */
public final class QuitReceiptCheck {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory(java.nio.file.Path.of("target"), "quit-receipt-");
        var logger = Logger.getLogger("QuitReceiptCheck");
        logger.setUseParentHandlers(false);
        try (var store = TrashStore.sqlite(directory.resolve("bin.db"), logger)) {
            var plugin = allocate(VoidClearPlugin.class);
            field(plugin, VoidClearPlugin.class, "trash", store);
            field(plugin, org.bukkit.plugin.java.JavaPlugin.class, "logger", logger);
            var transfers = allocate(PlayerTransfers.class);
            var sessions = new ConcurrentHashMap<UUID, UUID>();
            var joined = new ConcurrentHashMap<UUID, org.bukkit.entity.Player>();
            field(transfers, PlayerTransfers.class, "plugin", plugin);
            field(transfers, PlayerTransfers.class, "sessions", sessions);
            field(transfers, PlayerTransfers.class, "joined", joined);
            field(transfers, PlayerTransfers.class, "retired", new ConcurrentHashMap<>());
            field(transfers, PlayerTransfers.class, "playerData", directory);
            field(transfers, PlayerTransfers.class, "logoutReceipt", new NamespacedKey("voidclear", "session_logout"));
            field(transfers, PlayerTransfers.class, "receipt", new NamespacedKey("voidclear", "player_receipt"));
            UUID owner = UUID.randomUUID(), id = UUID.randomUUID(), session = UUID.randomUUID();
            field(transfers, PlayerTransfers.class, "owner", owner);
            sessions.put(id, session);
            assert store.acquirePlayerSession(id, owner, session);
            var file = directory.resolve(id + ".dat");
            var retire = PlayerTransfers.class.getDeclaredMethod("retireAfterQuit", UUID.class, UUID.class,
                    String.class, TrashStore.PlayerCheckpoint.class, boolean.class);
            retire.setAccessible(true);
            Runnable verify = () -> {
                try { retire.invoke(transfers, id, session, "fresh-native-token", null, true); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            verify.run(); // Native save absent (failed, skipped, or not yet complete).
            retained(store, sessions, id, owner, session);
            write(file, "previous-login-token");
            verify.run();
            retained(store, sessions, id, owner, session);
            write(file, "fresh-native-token");
            byte[] valid = Files.readAllBytes(file), corrupt = valid.clone();
            corrupt[corrupt.length - 8] ^= 1;
            Files.write(file, corrupt);
            verify.run();
            retained(store, sessions, id, owner, session);
            Files.write(file, valid);
            retire.invoke(transfers, id, UUID.randomUUID(), "fresh-native-token", null, true);
            retained(store, sessions, id, owner, session);
            verify.run(); // Only the actual, complete, current native save permits release.
            assert !sessions.containsKey(id);
            assert !store.ownsPlayerSession(id, owner, session);
            assert store.acquirePlayerSession(id, UUID.randomUUID(), UUID.randomUUID()) : "Verified quit blocked handoff";
            disconnectGate(plugin, transfers, sessions, joined, store, owner, directory);
        }
        System.out.println("QuitReceiptCheck: ALL OK");
    }

    private static void disconnectGate(VoidClearPlugin plugin, PlayerTransfers transfers,
            Map<UUID, UUID> sessions, Map<UUID, org.bukkit.entity.Player> joined, TrashStore store,
            UUID owner, java.nio.file.Path directory) throws Exception {
        var schedule = PlayerTransfers.class.getDeclaredMethod("retireWhenDisconnected", UUID.class, UUID.class,
                String.class, TrashStore.PlayerCheckpoint.class, boolean.class);
        schedule.setAccessible(true);
        for (String mode : new String[]{"normal", "replaced", "stopping", "late-replaced", "late-joined", "late-stopping"}) {
            UUID id = UUID.randomUUID(), session = UUID.randomUUID(), replacement = UUID.randomUUID();
            sessions.put(id, session);
            assert store.acquirePlayerSession(id, owner, session);
            write(directory.resolve(id + ".dat"), "native-token");
            var queued = new java.util.ArrayDeque<Runnable>();
            var tick = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>>();
            boolean[] cancelled = {false};
            var task = proxy(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class, (object, method, args) -> {
                if (method.getName().equals("cancel")) { cancelled[0] = true; return null; }
                throw new AssertionError(method.getName());
            });
            var global = proxy(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class, (object, method, args) -> {
                if (method.getName().equals("runAtFixedRate")) {
                    assert args[0] == plugin && args[2].equals(1L) && args[3].equals(1L);
                    @SuppressWarnings("unchecked") var callback = (java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>) args[1];
                    tick.set(callback); return task;
                }
                throw new AssertionError(method.getName());
            });
            var async = proxy(io.papermc.paper.threadedregions.scheduler.AsyncScheduler.class, (object, method, args) -> {
                if (method.getName().equals("runNow")) {
                    assert args[0] == plugin;
                    @SuppressWarnings("unchecked") var callback = (java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>) args[1];
                    queued.add(() -> callback.accept(task)); return task;
                }
                throw new AssertionError(method.getName());
            });
            var player = proxy(org.bukkit.entity.Player.class, (object, method, args) -> { throw new AssertionError(method.getName()); });
            org.bukkit.entity.Player[] online = {player};
            var server = proxy(org.bukkit.Server.class, (object, method, args) -> switch (method.getName()) {
                case "getGlobalRegionScheduler" -> global;
                case "getAsyncScheduler" -> async;
                case "getPlayer" -> { assert args[0].equals(id); yield online[0]; }
                default -> throw new AssertionError(method.getName());
            });
            field(plugin, org.bukkit.plugin.java.JavaPlugin.class, "server", server);
            schedule.invoke(transfers, id, session, "native-token", null, true);
            assert tick.get() != null && queued.isEmpty() : "Verification ran inside quit";
            tick.get().accept(task);
            assert !cancelled[0] && queued.isEmpty() : "Online player was retired";
            online[0] = null; joined.put(id, player);
            tick.get().accept(task);
            assert !cancelled[0] && queued.isEmpty() : "Joined connection was retired";
            joined.remove(id);
            if (mode.equals("replaced")) {
                sessions.put(id, replacement); assert store.acquirePlayerSession(id, owner, replacement);
            }
            if (mode.equals("stopping")) field(transfers, PlayerTransfers.class, "stopping", true);
            tick.get().accept(task);
            assert cancelled[0] : "Completed or stale poll remained scheduled";
            if (mode.equals("replaced") || mode.equals("stopping")) {
                assert queued.isEmpty() : "Stale poll submitted verification";
            } else {
                assert queued.size() == 1 && store.ownsPlayerSession(id, owner, session) : "Release did not wait for background verification";
                if (mode.equals("late-replaced")) {
                    sessions.put(id, replacement); assert store.acquirePlayerSession(id, owner, replacement);
                }
                if (mode.equals("late-joined")) joined.put(id, player);
                if (mode.equals("late-stopping")) field(transfers, PlayerTransfers.class, "stopping", true);
                queued.remove().run();
            }
            if (mode.equals("normal")) {
                assert !sessions.containsKey(id) && !store.ownsPlayerSession(id, owner, session);
            } else {
                UUID expected = mode.endsWith("replaced") ? replacement : session;
                assert expected.equals(sessions.get(id)) && store.ownsPlayerSession(id, owner, expected) : "Stale callback released live ownership: " + mode;
            }
            joined.remove(id);
            field(transfers, PlayerTransfers.class, "stopping", false);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(QuitReceiptCheck.class.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void retained(TrashStore store, Map<UUID, UUID> sessions, UUID id, UUID owner, UUID session) {
        assert session.equals(sessions.get(id)) : "Unverified save cleared local ownership";
        assert store.ownsPlayerSession(id, owner, session) : "Unverified save cleared durable ownership";
        assert !store.acquirePlayerSession(id, UUID.randomUUID(), UUID.randomUUID()) : "Unverified quit admitted foreign owner";
    }

    private static void write(java.nio.file.Path path, String token) throws Exception {
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            out.writeByte(10); out.writeUTF("");
            out.writeByte(10); out.writeUTF("BukkitValues");
            out.writeByte(8); out.writeUTF("voidclear:session_logout"); out.writeUTF(token);
            out.writeByte(0); out.writeByte(0);
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
}
