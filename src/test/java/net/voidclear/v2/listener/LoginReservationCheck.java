package net.voidclear.v2.listener;

import io.papermc.paper.connection.PlayerLoginConnection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class LoginReservationCheck {
    public static final class LegacyNativeConnection {
        boolean connected = true;
        public boolean isConnected() { return connected; }
    }
    public static final class LegacyWrapper {
        private final LegacyNativeConnection connection = new LegacyNativeConnection();
    }
    public static final class LegacyEvent {
        private final LegacyWrapper wrapper = new LegacyWrapper();
        public LegacyWrapper getConnection() { return wrapper; }
    }
    public static void main(String[] args) throws Exception {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        Map<UUID, UUID> sessions = new HashMap<>();
        Map<UUID, Player> joined = new HashMap<>();
        Map<UUID, BooleanSupplier> pending = new HashMap<>();
        boolean[] connected = {true};
        var connection = (PlayerLoginConnection) Proxy.newProxyInstance(LoginReservationCheck.class.getClassLoader(),
                new Class<?>[]{PlayerLoginConnection.class}, (proxy, method, values) -> {
                    if (method.getName().equals("isConnected")) return connected[0];
                    throw new AssertionError(method.getName());
                });
        var event = new AsyncPlayerPreLoginEvent("test", InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress(),
                id, false, null, "localhost", connection);
        sessions.put(id, session);
        pending.put(id, PlayerTransfers.connectionStatus(event));
        PlayerTransfers.retireDisconnectedLogin(id, sessions, joined, pending);
        assert sessions.get(id).equals(session) : "A live pre-login connection lost its reservation";
        connected[0] = false;
        PlayerTransfers.retireDisconnectedLogin(id, sessions, joined, pending);
        assert sessions.isEmpty() && pending.isEmpty() : "A disconnected pre-login reservation blocked reconnect";

        sessions.put(id, session);
        pending.put(id, () -> false);
        joined.put(id, (Player) Proxy.newProxyInstance(LoginReservationCheck.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, values) -> { throw new AssertionError(method.getName()); }));
        PlayerTransfers.retireDisconnectedLogin(id, sessions, joined, pending);
        assert sessions.get(id).equals(session) : "Cleanup retired a joined player";
        joined.clear();
        pending.clear();
        assert PlayerTransfers.connectionStatus(new Object()) == null : "Old API fallback must not invent connection evidence";
        PlayerTransfers.retireDisconnectedLogin(id, sessions, joined, pending);
        assert sessions.get(id).equals(session) : "Missing connection evidence released a reservation";
        pending.put(id, () -> { throw new IllegalStateException("injected state failure"); });
        try { PlayerTransfers.retireDisconnectedLogin(id, sessions, joined, pending); throw new AssertionError("Failure ignored"); }
        catch (IllegalStateException expected) { }
        assert sessions.get(id).equals(session) : "Unknown connection state released a reservation";
        // Exercise early API fallback even when this test compiles against the newer API.
        var legacy = new LegacyEvent();
        var state = PlayerTransfers.nativeConnectionStatus(legacy.wrapper);
        assert state.getAsBoolean();
        legacy.wrapper.connection.connected = false;
        assert !state.getAsBoolean() : "Legacy native connection remained live";
        Player oldConnection = player(id), newConnection = player(id);
        sessions.put(id, session); joined.put(id, oldConnection);
        assert PlayerTransfers.ownsConnection(oldConnection, session, sessions, joined);
        joined.put(id, newConnection);
        assert !PlayerTransfers.ownsConnection(oldConnection, session, sessions, joined) : "Old connection owns new callback";
        assert PlayerTransfers.ownsConnection(newConnection, session, sessions, joined);
        sessions.put(id, UUID.randomUUID());
        assert !PlayerTransfers.ownsConnection(newConnection, session, sessions, joined) : "Old session owns new callback";
        assert !PlayerTransfers.ownsConnection(newConnection, null, sessions, joined);
        offlineReceiptGate();
        System.out.println("LoginReservationCheck: ALL OK");
    }

    @SuppressWarnings("unchecked")
    private static void offlineReceiptGate() throws Exception {
        // No server or JavaPlugin is constructed; exercise the real admission lock with local state only.
        Class<?> type = Class.forName("sun.misc.Unsafe");
        var singleton = type.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        var transfers = (PlayerTransfers) type.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), PlayerTransfers.class);
        for (String field : new String[]{"sessions", "joined", "pendingConnections", "retired"})
            set(transfers, field, new java.util.concurrent.ConcurrentHashMap<>());
        for (String field : new String[]{"blocked", "applying", "isolating", "receiptCleanup"})
            set(transfers, field, java.util.concurrent.ConcurrentHashMap.newKeySet());
        UUID player = UUID.randomUUID();
        assert transfers.withOfflineReceipt(player, () -> "checked").equals("checked");
        for (String field : new String[]{"sessions", "joined", "pendingConnections", "retired"}) {
            var map = (Map<UUID, Object>) get(transfers, field); map.put(player, new Object());
            refused(() -> transfers.withOfflineReceipt(player, () -> { throw new AssertionError("Unsafe state entered callback"); }));
            map.clear();
        }
        for (String field : new String[]{"blocked", "applying", "isolating", "receiptCleanup"}) {
            var state = (java.util.Set<UUID>) get(transfers, field); state.add(player);
            refused(() -> transfers.withOfflineReceipt(player, () -> { throw new AssertionError("Unsafe state entered callback"); }));
            state.clear();
        }
        set(transfers, "stopping", true);
        refused(() -> transfers.withOfflineReceipt(player, () -> true));
        set(transfers, "stopping", false);
        refused(() -> transfers.withOfflineReceipt(player, () -> { throw new IllegalStateException("injected I/O failure"); }));
        assert ((java.util.Set<?>) get(transfers, "receiptCleanup")).isEmpty() : "Failure retained the local gate";

        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var worker = new java.util.concurrent.FutureTask<>(() -> transfers.withOfflineReceipt(player, () -> {
            entered.countDown();
            try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Gate test timed out"); }
            catch (InterruptedException failed) { throw new AssertionError(failed); }
            return true;
        }));
        Thread thread = new Thread(worker, "receipt-gate-check"); thread.start();
        try {
            assert entered.await(5, java.util.concurrent.TimeUnit.SECONDS);
            refused(() -> transfers.withOfflineReceipt(player, () -> true));
            var profile = (com.destroystokyo.paper.profile.PlayerProfile) Proxy.newProxyInstance(
                    LoginReservationCheck.class.getClassLoader(), new Class<?>[]{com.destroystokyo.paper.profile.PlayerProfile.class},
                    (proxy, method, values) -> {
                        if (method.getName().equals("getId")) return player;
                        throw new AssertionError(method.getName());
                    });
            var event = new AsyncPlayerPreLoginEvent("test", InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress(),
                    player, false, profile, "localhost", null);
            transfers.login(event);
            assert event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_OTHER : "Login entered a file replacement";
            assert ((Map<?, ?>) get(transfers, "sessions")).isEmpty();
        } finally { release.countDown(); }
        assert worker.get(5, java.util.concurrent.TimeUnit.SECONDS);
        thread.join(5000);
        assert !thread.isAlive() && ((java.util.Set<?>) get(transfers, "receiptCleanup")).isEmpty();
        assert transfers.withOfflineReceipt(player, () -> true);
    }

    private static Object get(PlayerTransfers transfers, String name) throws Exception {
        var field = PlayerTransfers.class.getDeclaredField(name); field.setAccessible(true); return field.get(transfers);
    }
    private static void set(PlayerTransfers transfers, String name, Object value) throws Exception {
        var field = PlayerTransfers.class.getDeclaredField(name); field.setAccessible(true); field.set(transfers, value);
    }
    private static void refused(Runnable operation) {
        try { operation.run(); throw new AssertionError("Unsafe offline receipt operation accepted"); }
        catch (IllegalStateException expected) { }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(LoginReservationCheck.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("getUniqueId")) return id;
                    throw new AssertionError(method.getName());
                });
    }
}
