package net.voidclear.probe;

import net.voidclear.VoidClearPlugin;
import net.voidclear.v2.store.TrashStore;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Test-server-only probe. Never packaged in the production plugin. */
public final class Probe extends JavaPlugin {
    private java.util.concurrent.CountDownLatch databaseGate;
    private org.bukkit.entity.Player retiredPlayer;
    private org.bukkit.entity.Item unreadySource;
    @Override public void onEnable() {
        if (!Boolean.getBoolean("voidclear.probe")) {
            getLogger().severe("Test probe disabled: explicit -Dvoidclear.probe=true is required");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("vcprobe").setExecutor((sender, command, label, args) -> {
            try {
                if (args.length == 2 && args[0].equals("clientsync") && sender instanceof org.bukkit.entity.Player player) {
                    // Ordering only: never force inventory publication in a regression test.
                    player.getScheduler().run(this, task -> {
                        player.sendMessage(net.kyori.adventure.text.Component.text("VC_CLIENT_SYNC_" + args[1]));
                    }, null);
                    return true;
                }
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender))
                    throw new IllegalArgumentException("Test probe is console-only");
                VoidClearPlugin plugin = (VoidClearPlugin) getServer().getPluginManager().getPlugin("VoidClear");
                if (plugin.scheduler().folia() && (args.length == 0 || !java.util.Set.of(
                        "dbpending", "tickping", "sessionclosed", "playerjournal", "ground", "loadstats", "dbhold", "dbrelease", "dbsnapshot", "sweepstate", "menurefresh").contains(args[0])
                        || args[0].equals("ground") && (args.length != 4 || !args[1].equals("local"))))
                    throw new IllegalArgumentException("Probe command has no verified Folia path");
                if (args.length == 1 && args[0].equals("dbhold")) {
                    if (databaseGate != null) throw new IllegalStateException("Database gate already held");
                    databaseGate = new java.util.concurrent.CountDownLatch(1);
                    var gate = databaseGate;
                    plugin.databaseTasks().submit(() -> {
                        sender.sendMessage("VC_DB_HELD");
                        try { if (!gate.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
                        catch (InterruptedException failed) { Thread.currentThread().interrupt(); throw new IllegalStateException(failed); }
                        return true;
                    });
                    return true;
                }
                if (args.length == 1 && args[0].equals("dbrelease")) {
                    if (databaseGate != null) { databaseGate.countDown(); databaseGate = null; }
                    sender.sendMessage("VC_DB_RELEASED");
                    return true;
                }
                if (args.length == 1 && args[0].equals("sweepstate")) {
                    sender.sendMessage("VC_SWEEP_STATE=" + plugin.sweeper().active() + ":"
                            + plugin.databaseTasks().queued() + ":" + plugin.sweeper().lastFailures());
                    return true;
                }
                if (args.length == 2 && args[0].equals("menurefresh")) {
                    var player = getServer().getPlayerExact(args[1]);
                    if (player == null) throw new AssertionError("Player missing");
                    plugin.scheduler().player(player, () -> {
                        try {
                            Object menu = player.getOpenInventory().getTopInventory().getHolder();
                            var values = menu.getClass().getDeclaredField("buttonValues"); values.setAccessible(true);
                            var inventory = menu.getClass().getDeclaredField("inventory"); inventory.setAccessible(true);
                            var original = (org.bukkit.inventory.Inventory) inventory.get(menu);
                            int[] writes = {0};
                            var counted = (org.bukkit.inventory.Inventory) java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(), new Class<?>[]{org.bukkit.inventory.Inventory.class}, (proxy, method, arguments) -> {
                                        if (method.getName().equals("setItem")) writes[0]++;
                                        try { return method.invoke(original, arguments); }
                                        catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                                    });
                            var buttons = plugin.trashListener().getClass().getDeclaredMethod("renderButtons", menu.getClass()); buttons.setAccessible(true);
                            var render = plugin.trashListener().getClass().getDeclaredMethod("render", menu.getClass()); render.setAccessible(true);
                            inventory.set(menu, counted);
                            try {
                                buttons.invoke(plugin.trashListener(), menu);
                                writes[0] = 0;
                                for (int i = 0; i < 100; i++) buttons.invoke(plugin.trashListener(), menu);
                                if (writes[0] != 0) throw new AssertionError("Unchanged buttons rewritten: " + writes[0]);
                                values.set(menu, java.util.Map.of());
                                buttons.invoke(plugin.trashListener(), menu);
                                int changed = writes[0];
                                if (changed == 0) throw new AssertionError("Changed buttons not rendered");
                                render.invoke(plugin.trashListener(), menu);
                                if (writes[0] <= changed) throw new AssertionError("Full render did not restore buttons");
                                sender.sendMessage("VC_MENU_REFRESH=100:0:" + changed + ":restored");
                            } finally { inventory.set(menu, original); }
                        } catch (Exception | AssertionError failed) { getLogger().log(java.util.logging.Level.SEVERE, "VC_PROBE_FAILED", failed); }
                    });
                    return true;
                }
                if (args.length == 2 && args[0].equals("dbsnapshot")) {
                    if (!args[1].matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid snapshot token");
                    plugin.history().execute(() -> {
                        try {
                            var method = TrashStore.class.getDeclaredMethod("connection"); method.setAccessible(true);
                            var table = TrashStore.class.getDeclaredField("table"); table.setAccessible(true);
                            var digest = java.security.MessageDigest.getInstance("SHA-256");
                            try (var connection = (java.sql.Connection) method.invoke(plugin.trash());
                                 var framed = new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest))) {
                                for (String suffix : java.util.List.of("", "_meta", "_sweeps", "_player_ops", "_players", "_player_drops", "_sessions", "_item_fences", "_drop_chunks")) {
                                    framed.writeUTF(suffix);
                                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table.get(plugin.trash()) + suffix + " ORDER BY 1,2")) {
                                        int columns = rows.getMetaData().getColumnCount();
                                        while (rows.next()) {
                                            framed.writeInt(columns);
                                            for (int column = 1; column <= columns; column++) {
                                                byte[] value = rows.getBytes(column);
                                                framed.writeInt(value == null ? -1 : value.length);
                                                if (value != null) framed.write(value);
                                            }
                                        }
                                        framed.writeInt(-1);
                                    }
                                }
                            }
                            sender.sendMessage("VC_DB_SNAPSHOT_" + args[1] + "=" + java.util.HexFormat.of().formatHex(digest.digest()));
                        } catch (Exception | AssertionError failed) {
                            getLogger().log(java.util.logging.Level.SEVERE, "VC_PROBE_FAILED", failed);
                        }
                    });
                    return true;
                }
                if (args.length == 2 && args[0].equals("unreadypickup")) {
                    retiredPlayer = getServer().getPlayerExact(args[1]);
                    if (retiredPlayer == null) throw new AssertionError("Player missing");
                    unreadySource = retiredPlayer.getWorld().dropItem(retiredPlayer.getLocation(), new ItemStack(Material.DIAMOND, 16),
                            item -> plugin.playerTransfers().markDrop(item, java.util.UUID.randomUUID()));
                    unreadySource.setPickupDelay(32767);
                    getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(retiredPlayer, unreadySource, 0));
                    if (!plugin.playerTransfers().pending(retiredPlayer)) throw new AssertionError("Pickup was not queued");
                    sender.sendMessage("VC_UNREADY_QUEUED");
                    return true;
                }
                if (args.length == 1 && args[0].equals("unreadyreleased")) {
                    if (!unreadySource.isValid() || unreadySource.getItemStack().getAmount() != 16)
                        throw new AssertionError("Unready source changed");
                    if (!plugin.sweepRecovery().reserve(unreadySource.getUniqueId())) throw new AssertionError("Abandoned pickup reservation leaked");
                    plugin.sweepRecovery().release(unreadySource.getUniqueId());
                    sender.sendMessage("VC_UNREADY_RELEASED");
                    return true;
                }
                if (args.length == 2 && args[0].equals("stalecallback")) {
                    var current = getServer().getPlayerExact(args[1]);
                    if (current == null || current == retiredPlayer) throw new AssertionError("New connection missing");
                    plugin.playerTransfers().applying(current);
                    plugin.playerTransfers().finished(retiredPlayer);
                    if (!plugin.playerTransfers().pending(current)) throw new AssertionError("Old connection cleared new pending");
                    plugin.playerTransfers().isolate(retiredPlayer, new IllegalStateException("Injected retired callback"));
                    plugin.playerTransfers().transactionSession(current);
                    plugin.playerTransfers().finished(current);
                    unreadySource.remove();
                    sender.sendMessage("VC_STALE_CALLBACK_OK");
                    return true;
                }
                if (args.length == 2 && args[0].equals("dbpending")) {
                    var player = getServer().getPlayerExact(args[1]);
                    sender.sendMessage("VC_DB_PENDING=" + (player != null && plugin.playerTransfers().pending(player)));
                    return true;
                }
                if (args.length == 2 && args[0].equals("tickping")) {
                    sender.sendMessage("VC_TICK_PING_" + args[1] + "=" + plugin.trash().size() + ":" + plugin.trash().itemCount());
                    return true;
                }
                if (args.length == 2 && args[0].equals("loadstats")) {
                    var players = getServer().getOnlinePlayers();
                    long pending = players.stream().filter(plugin.playerTransfers()::pending).count();
                    var memory = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                    var collectors = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
                    long collections = collectors.stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
                    long gcMillis = collectors.stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
                    sender.sendMessage("VC_LOAD_STATS_" + args[1] + "={\"players\":" + players.size()
                            + ",\"pending\":" + pending + ",\"binCount\":" + plugin.trash().itemCount()
                            + ",\"heapUsed\":" + memory.getUsed() + ",\"heapCommitted\":" + memory.getCommitted()
                            + ",\"gcCollections\":" + collections + ",\"gcMillis\":" + gcMillis
                            + ",\"threads\":" + java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount()
                            + ",\"tps\":" + (plugin.scheduler().folia() ? "[]" : java.util.Arrays.toString(getServer().getTPS()))
                            + ",\"averageTickMs\":" + (plugin.scheduler().folia() ? -1 : getServer().getAverageTickTime()) + "}");
                    return true;
                }
                if (args.length == 1 && args[0].equals("logintickets")) {
                    var field = plugin.playerTransfers().getClass().getDeclaredField("chunkUsers");
                    field.setAccessible(true);
                    int tracked = ((java.util.Map<?, ?>) field.get(plugin.playerTransfers())).size();
                    int actual = getServer().getWorlds().stream()
                            .mapToInt(world -> world.getPluginChunkTickets().getOrDefault(plugin, java.util.List.of()).size()).sum();
                    if (actual != tracked) throw new AssertionError("Chunk ticket tracking differs from server: " + tracked + "/" + actual);
                    sender.sendMessage("VC_LOGIN_TICKETS=" + actual);
                    return true;
                }
                if (args.length == 2 && args[0].equals("loadedsource")) {
                    var field = plugin.playerTransfers().getClass().getDeclaredField("loadedDrops");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var sources = (java.util.Map<java.util.UUID, TrashStore.LoadedDrop>) field.get(plugin.playerTransfers());
                    var id = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + args[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    sender.sendMessage("VC_LOADED_SOURCE=" + plugin.trash().verifiedPlayerDrops(id, sources));
                    return true;
                }
                if (args.length == 1 && args[0].equals("autosweep")) {
                    plugin.sweepTimer().begin(1);
                    sender.sendMessage("VC_AUTO_SWEEP_ARMED");
                    return true;
                }
                if (args.length == 1 && args[0].equals("reloadtimers")) {
                    reloadTimers(plugin);
                    sender.sendMessage("VC_RELOAD_TIMERS_OK");
                    return true;
                }
                if (args.length == 1 && args[0].equals("itemfences")) {
                    itemFenceCheck();
                    sender.sendMessage("VC_ITEM_FENCES_OK");
                    return true;
                }
                if (args.length == 2 && args[0].equals("sessionclosed")) {
                    java.util.UUID id = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + args[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    sender.sendMessage((plugin.playerTransfers().pendingLoginOrQuit(id) ? "VC_SESSION_PENDING_" : "VC_SESSION_CLOSED_") + args[1]);
                    return true;
                }
                if (args.length == 4 && args[0].equals("rejoindrop")) {
                    if (plugin.trash().mysql() && java.util.Set.of("pickup", "pickupfull", "consume").contains(args[2])) {
                        var player = getServer().getPlayerExact(args[1]);
                        if (player == null) throw new AssertionError("Pickup player missing");
                        if (args[2].equals("consume")) player.getInventory().setItem(35, null);
                        for (var item : player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class))
                            if (plugin.playerTransfers().managedDrop(item) && item.getItemStack().getType() == Material.DIAMOND)
                                getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(player, item, 0));
                        getServer().getScheduler().runTaskTimer(this, task -> {
                            if (plugin.playerTransfers().pending(player)) return;
                            task.cancel();
                            try {
                                rejoinDropCheck(plugin, args[1], args[2].equals("pickup") ? "inspect" : "verifyfull", Integer.parseInt(args[3]));
                                sender.sendMessage("VC_REJOIN_DROP_" + args[2] + "_OK");
                            } catch (Exception | AssertionError failed) {
                                getLogger().log(java.util.logging.Level.SEVERE, "VC_PROBE_FAILED", failed);
                            }
                        }, 1L, 1L);
                        return true;
                    }
                    rejoinDropCheck(plugin, args[1], args[2], Integer.parseInt(args[3]));
                    sender.sendMessage("VC_REJOIN_DROP_" + args[2] + "_OK");
                    return true;
                }
                if (args.length > 0 && args[0].equals("playerdrop")) {
                    managedDropCheck(plugin);
                    sender.sendMessage("VC_PLAYER_DROP_OK");
                    return true;
                }
                if (args.length > 0 && args[0].equals("playercut")) {
                    String stage = args[1];
                    if (!java.util.Set.of("prepared", "storage", "offhand", "cursor", "receipt", "confirmed").contains(stage))
                        throw new IllegalArgumentException("Unknown player cut");
                    var field = plugin.playerTransfers().getClass().getDeclaredField("testBoundary"); field.setAccessible(true);
                    field.set(plugin.playerTransfers(), (java.util.function.Consumer<String>) boundary -> {
                        if (!boundary.equals(stage)) return;
                        getLogger().info("VC_PLAYER_CUT_" + stage);
                        try { new java.util.concurrent.CountDownLatch(1).await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    });
                    sender.sendMessage("VC_PLAYER_CUT_ARMED_" + stage);
                    return true;
                }
                if (args.length > 0 && args[0].equals("playerjournal")) {
                    var connectionMethod = TrashStore.class.getDeclaredMethod("connection"); connectionMethod.setAccessible(true);
                    var table = TrashStore.class.getDeclaredField("table"); table.setAccessible(true);
                    try (var connection = (java.sql.Connection) connectionMethod.invoke(plugin.trash());
                         var statement = connection.prepareStatement("SELECT o.op_id,o.state,o.evidence FROM " + table.get(plugin.trash())
                                  + "_players p JOIN " + table.get(plugin.trash()) + "_player_ops o ON o.op_id=p.op_id WHERE p.player_id=?")) {
                        statement.setString(1, java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + args[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next()) throw new AssertionError("No durable player evidence");
                            var evidence = new java.util.Properties();
                            try (var input = new java.io.ByteArrayInputStream(rows.getBytes(3))) { evidence.load(input); }
                             boolean claim = evidence.containsKey("source") || evidence.containsKey("claim.entity");
                             if (args.length > 3 && args[3].equals("claim") && !claim)
                                 throw new AssertionError("Expected managed claim evidence");
                             boolean complete = "36".equals(evidence.getProperty("planned.storage.length"));
                             if (claim) {
                                 complete &= evidence.containsKey("source") && evidence.containsKey("remainder")
                                         && evidence.containsKey("claim.entity") && evidence.containsKey("claim.parent")
                                         && evidence.containsKey("claim.sequence");
                                 for (int slot = 0; slot < 36; slot++) complete &= evidence.containsKey("before.storage." + slot)
                                         && evidence.containsKey("planned.storage." + slot);
                             } else complete &= "36".equals(evidence.getProperty("before.storage.length"));
                             if (!complete)
                                 throw new AssertionError("Incomplete player evidence");
                            sender.sendMessage("VC_PLAYER_JOURNAL_" + args[2] + "=" + rows.getString(2) + "|"
                                    + java.util.Base64.getEncoder().encodeToString(rows.getBytes(3)));
                        }
                    }
                    return true;
                }
                if (args.length > 0 && args[0].equals("flushdb")) {
                    flushDatabase(plugin.trash());
                    sender.sendMessage("VC_FLUSHDB_" + args[1]);
                    return true;
                }
                if (args.length > 0 && args[0].equals("dbcount")) {
                    var connectionMethod = TrashStore.class.getDeclaredMethod("connection"); connectionMethod.setAccessible(true);
                    var table = TrashStore.class.getDeclaredField("table"); table.setAccessible(true);
                    long count = 0;
                    try (var connection = (java.sql.Connection) connectionMethod.invoke(plugin.trash());
                         var statement = connection.createStatement();
                         var rows = statement.executeQuery("SELECT item_data FROM " + table.get(plugin.trash()))) {
                        while (rows.next()) {
                            var stack = ItemStack.deserializeBytes(rows.getBytes(1));
                            if (stack.getType() == Material.valueOf(args[1])) count += stack.getAmount();
                        }
                    }
                    sender.sendMessage("VC_DBCOUNT_" + args[2] + "=" + count);
                    return true;
                }
                if (args.length > 0 && args[0].equals("ground")) {
                    if (args[1].equals("local")) {
                        var player = getServer().getPlayerExact(args[2]);
                        if (player == null) throw new IllegalArgumentException("Test player missing");
                        var location = player.getLocation();
                        var total = new java.util.concurrent.atomic.AtomicLong();
                        var remaining = new java.util.concurrent.atomic.AtomicInteger(9);
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                            int x = (location.getBlockX() >> 4) + dx, z = (location.getBlockZ() >> 4) + dz;
                            plugin.scheduler().onChunk(location.getWorld(), x, z, () -> {
                            long count = !location.getWorld().isChunkLoaded(x, z) ? 0 : java.util.Arrays.stream(location.getWorld().getChunkAt(x, z).getEntities())
                                    .filter(entity -> entity instanceof org.bukkit.entity.Item)
                                    .map(entity -> (org.bukkit.entity.Item) entity)
                                    .filter(item -> item.getItemStack().getType() == Material.QUARTZ)
                                    .mapToLong(item -> item.getItemStack().getAmount()).sum();
                            total.addAndGet(count);
                            if (remaining.decrementAndGet() == 0) sender.sendMessage("VC_GROUND_" + args[3] + "=" + total.get());
                        });
                        }
                        return true;
                    }
                    if (args[1].equals("seed")) {
                        var player = getServer().getPlayerExact(args[2]);
                        if (player == null) throw new IllegalArgumentException("Test player missing");
                        var item = player.getWorld().dropItem(player.getLocation().add(0, 3, 0), new ItemStack(Material.QUARTZ, 16));
                        item.setPickupDelay(32767); item.setGravity(false); item.setVelocity(new org.bukkit.util.Vector());
                    }
                    long count = getServer().getWorlds().stream().flatMap(world -> world.getEntitiesByClass(org.bukkit.entity.Item.class).stream())
                            .filter(item -> item.getItemStack().getType() == Material.QUARTZ).mapToLong(item -> item.getItemStack().getAmount()).sum();
                    sender.sendMessage("VC_GROUND_" + args[3] + "=" + count);
                    return true;
                }
                if (args.length > 0 && args[0].equals("saveworld")) {
                    if (!getServer().dispatchCommand(getServer().getConsoleSender(), "save-all flush")) throw new AssertionError("World save rejected");
                    sender.sendMessage("VC_SAVEWORLD_QUEUED_" + args[1]);
                    return true;
                }
                if (args.length > 0 && args[0].equals("checkpoint")) {
                    var player = getServer().getPlayerExact(args[1]);
                    if (player == null) throw new IllegalArgumentException("Test player missing");
                    player.saveData();
                    flushDatabase(plugin.trash());
                    sender.sendMessage("VC_CHECKPOINT_" + args[2]);
                    return true;
                }
                if (args.length > 0 && args[0].equals("dbfault")) {
                    databaseFault(args[1]);
                    sender.sendMessage("VC_DBFAULT_" + args[1] + "_OK");
                    return true;
                }
                if (args.length > 0 && args[0].equals("renderfault")) {
                    renderFault(plugin);
                    sender.sendMessage("VC_PROBE_RENDERFAULT_OK");
                    return true;
                }
                if (args.length > 0 && args[0].equals("sweepreceipt")) {
                    sweepReceiptCheck(plugin);
                    sender.sendMessage("VC_SWEEP_RECEIPT_OK");
                    return true;
                }
                if (args.length > 0 && args[0].equals("deliveryfault")) {
                    deliveryFault(plugin);
                    sender.sendMessage("VC_PROBE_DELIVERYFAULT_OK");
                    return true;
                }
                if (args.length > 0 && args[0].equals("cache")) {
                    cacheCheck(plugin);
                    sender.sendMessage("VC_PROBE_CACHE_OK");
                    return true;
                }
                if (args.length > 0 && args[0].equals("mysqlcache")) {
                    mysqlCacheCheck();
                    sender.sendMessage("VC_PROBE_MYSQLCACHE_OK");
                    return true;
                }
                if (args.length == 1 && args[0].equals("countfixture")) {
                    var store = plugin.trash();
                    plugin.clearTrash();
                    var write = TrashStore.class.getDeclaredMethod("transaction", java.util.function.Supplier.class); write.setAccessible(true);
                    byte[] stone = new ItemStack(Material.STONE, 64).serializeAsBytes();
                    write.invoke(store, (java.util.function.Supplier<Boolean>) () -> {
                        if (!store.collect(new ItemStack(Material.QUARTZ, 60).serializeAsBytes(), () -> {})) throw new AssertionError("partial seed");
                        for (int i = 1; i < TrashStore.CAPACITY; i++) if (!store.collect(stone, () -> {})) throw new AssertionError("partial capacity");
                        return true;
                    });
                    var world = getServer().getWorlds().getFirst();
                    var source = world.dropItem(world.getSpawnLocation().add(0, 5, 0), new ItemStack(Material.QUARTZ, 16));
                    source.setPickupDelay(32767); source.setGravity(false);
                    sender.sendMessage("VC_COUNT_FIXTURE=" + store.itemCount());
                    return true;
                }
                if (args.length == 0 || !args[0].equals("capacity")) throw new IllegalArgumentException("capacity required");
                TrashStore store = plugin.trash();
                plugin.clearTrash();
                byte[] full = new ItemStack(Material.STONE, 64).serializeAsBytes();
                long fill = System.nanoTime();
                synchronized (store) {
                    // One fixture transaction avoids 10,000 durable full-snapshot reads on SQLite.
                    var write = TrashStore.class.getDeclaredMethod("transaction", java.util.function.Supplier.class); write.setAccessible(true);
                    write.invoke(store, (java.util.function.Supplier<Boolean>) () -> {
                        for (int i = 0; i < TrashStore.CAPACITY; i++)
                            if (!store.collect(full, () -> { })) throw new AssertionError("fill rejected at " + i);
                        return true;
                    });
                }
                double fillMs = (System.nanoTime() - fill) / 1e6;
                if (store.itemCount() != 640000) throw new AssertionError("quantity mismatch");
                List<Double> reads = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    long start = System.nanoTime();
                    if (store.page(222, 45).size() != 10) throw new AssertionError("last page");
                    store.itemCount();
                    reads.add((System.nanoTime() - start) / 1e6);
                }
                long rejectStart = System.nanoTime();
                if (store.deposit(new ItemStack(Material.DIAMOND, 1), remaining -> { throw new AssertionError("full bin consumed drop"); })) {
                    throw new AssertionError("over capacity accepted");
                }
                double rejectMs = (System.nanoTime() - rejectStart) / 1e6;
                if (store.size() != 10000 || store.itemCount() != 640000) throw new AssertionError("rejection changed full bin");
                List<Double> repeatedRejects = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    long start = System.nanoTime();
                    if (store.deposit(new ItemStack(Material.DIAMOND, 1), remaining -> { throw new AssertionError("Rejected drop changed"); }))
                        throw new AssertionError("Repeated over-capacity deposit accepted");
                    repeatedRejects.add((System.nanoTime() - start) / 1e6);
                }
                repeatedRejects.sort(Double::compare);
                reads.sort(Double::compare);
                String papi = PlaceholderAPI.setPlaceholders((org.bukkit.OfflinePlayer) null,
                        "%voidclear_trash_count%|%voidclear_trash_stacks%|%voidclear_trash_capacity%|%voidclear_trash_free_slots%");
                if (!papi.equals("640000|10000|10000|0")) throw new AssertionError("PAPI capacity: " + papi);
                String result = "{\"fill_ms\":" + fillMs + ",\"last_page_p95_ms\":" + reads.get(94)
                        + ",\"full_rejection_ms\":" + rejectMs + ",\"repeated_rejection_p95_ms\":" + repeatedRejects.get(28)
                        + ",\"papi\":\"" + papi + "\"}";
                Files.writeString(getDataFolder().toPath().resolve("capacity.json"), result);
                sender.sendMessage("VC_PROBE_CAPACITY_OK " + result);
            } catch (Throwable failed) {
                getLogger().log(java.util.logging.Level.SEVERE, "VC_PROBE_FAILED", failed);
                sender.sendMessage("VC_PROBE_FAILED " + failed);
            }
            return true;
        });
        getDataFolder().mkdirs();
    }

    private void itemFenceCheck() throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        var file = Files.createTempDirectory(getDataFolder().toPath(), "item-fence-").resolve("bin.db");
        var player = java.util.UUID.randomUUID();
        var session = java.util.UUID.randomUUID();
        var operation = java.util.UUID.randomUUID();
        try (var store = TrashStore.sqlite(file, getLogger())) {
            if (!store.deposit(new ItemStack(Material.DIAMOND, 16), remainder -> {})
                    || !store.deposit(new ItemStack(Material.GOLD_INGOT, 8), remainder -> {})) throw new AssertionError("seed");
            if (!store.replacePlayerPage(player, session, operation, null, new byte[]{1}, 1, 1,
                    store.page(1, 1), List.of(new ItemStack(Material.GOLD_INGOT, 12)))) throw new AssertionError("prepare");
        }
        try (var store = TrashStore.sqlite(file, getLogger()); var remote = TrashStore.sqlite(file, getLogger())) {
            if (remote.replacePage(1, 1, remote.page(1, 1), java.util.Collections.singletonList(null)))
                throw new AssertionError("Unconfirmed gold circulated after restart");
            if (!remote.replacePage(0, 1, remote.page(0, 1), java.util.Collections.singletonList(null)))
                throw new AssertionError("Unrelated diamond blocked");
            // A sweep may merge into the same row; it must retain that row's fence despite changed bytes/page.
            if (!remote.deposit(new ItemStack(Material.GOLD_INGOT, 3), remainder -> {})) throw new AssertionError("merge");
            if (remote.replacePage(0, 1, remote.page(0, 1), java.util.Collections.singletonList(null)))
                throw new AssertionError("Sweep merge/page shift bypassed fence");
            store.checkpointPlayer(player, session, operation, "DONE");
            if (!remote.replacePage(0, 1, remote.page(0, 1), java.util.Collections.singletonList(null)))
                throw new AssertionError("Receipt failed to release fence");
            if (remote.size() != 0) throw new AssertionError("Unexpected remaining items");
        }
    }

    private void flushDatabase(TrashStore store) throws Exception {
        // A separate connection must see the exact committed rows before the crash cut.
        var connectionMethod = TrashStore.class.getDeclaredMethod("connection"); connectionMethod.setAccessible(true);
        var table = TrashStore.class.getDeclaredField("table"); table.setAccessible(true);
        synchronized (store) {
            var expected = store.page(0, 10000);
            try (var connection = (java.sql.Connection) connectionMethod.invoke(store);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id,item_data FROM " + table.get(store) + " ORDER BY position,id")) {
                for (var entry : expected) {
                    if (!rows.next() || rows.getLong(1) != entry.id() || !java.util.Arrays.equals(rows.getBytes(2), entry.bytes()))
                        throw new AssertionError("Database commit differs from the visible snapshot");
                }
                if (rows.next()) throw new AssertionError("Unexpected committed database rows");
            }
        }
    }

    private void renderFault(VoidClearPlugin plugin) throws Exception {
        TrashStore store = plugin.trash();
        plugin.clearTrash();
        var listener = plugin.trashListener();
        var configField = listener.getClass().getDeclaredField("config"); configField.setAccessible(true);
        Object config = configField.get(listener);
        Class<?> menuClass = Class.forName("net.voidclear.v2.listener.TrashListener$Menu");
        var constructor = menuClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object menu = constructor.newInstance(config, 0, java.util.Map.of());
        var render = listener.getClass().getDeclaredMethod("render", menuClass); render.setAccessible(true);
        var entries = menuClass.getDeclaredField("entries"); entries.setAccessible(true);
        var invalidated = menuClass.getDeclaredField("invalidated"); invalidated.setAccessible(true);
        var inventory = menuClass.getDeclaredField("inventory"); inventory.setAccessible(true);
        if (!store.collect(new ItemStack(Material.DIAMOND, 7).serializeAsBytes(), () -> {})) throw new AssertionError("seed rejected");
        render.invoke(listener, menu);
        Object oldEntries = entries.get(menu);
        var view = (org.bukkit.inventory.Inventory) inventory.get(menu);
        ItemStack[] before = view.getContents();
        if (!store.collect(new byte[]{1, 2, 3}, () -> {})) throw new AssertionError("corrupt seed rejected");
        var persisted = store.page(0, 45);
        try { render.invoke(listener, menu); throw new AssertionError("Corrupt page rendered"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
        }
        if (!invalidated.getBoolean(menu) || entries.get(menu) != oldEntries)
            throw new AssertionError("Failed render published a current page");
        if (!java.util.Arrays.equals(before, view.getContents())) throw new AssertionError("Failed decode changed inventory");
        var current = listener.getClass().getDeclaredMethod("current", menuClass); current.setAccessible(true);
        if ((boolean) current.invoke(listener, menu)) throw new AssertionError("Failed page can still commit");
        if (!store.isCurrentPage(0, 45, persisted)) throw new AssertionError("Corruption test changed stored bytes");
        // The corrupt test record cannot be counted by clearAll; remove only this isolated test data.
        store.clear(store.ids());
    }

    private void databaseFault(String mode) throws Exception {
        String direct = "jdbc:mysql://127.0.0.1:33317/voidclear_test?useSSL=false&serverTimezone=UTC";
        String url = mode.equals("lock") ? direct : direct.replace(":33317/", ":33318/");
        try (TrashStore store = TrashStore.mysql(url, "voidclear", "voidclear", "faultprobe_", getLogger())) {
            store.clear(store.ids());
            if (!store.deposit(new ItemStack(Material.STONE, 20), rest -> {})) throw new AssertionError("fault seed");
            if (mode.equals("delay")) {
                long began = System.nanoTime();
                if (!store.deposit(new ItemStack(Material.STONE, 1), rest -> {})) throw new AssertionError("delayed deposit");
                double ms = (System.nanoTime() - began) / 1e6;
                if (store.itemCount() != 21) throw new AssertionError("delay changed quantity");
                getLogger().info("VC_DB_DELAY_MS=" + ms);
            } else if (mode.equals("lock")) {
                try (var blocker = java.sql.DriverManager.getConnection(direct, "voidclear", "voidclear")) {
                    blocker.setAutoCommit(false);
                    blocker.createStatement().executeQuery("SELECT revision FROM faultprobe_trash_items_meta WHERE id=1 FOR UPDATE").close();
                    var release = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try { Thread.sleep(1500); blocker.rollback(); } catch (Exception failed) { throw new RuntimeException(failed); }
                    });
                    long began = System.nanoTime();
                    if (!store.deposit(new ItemStack(Material.STONE, 1), rest -> {})) throw new AssertionError("locked deposit");
                    double ms = (System.nanoTime() - began) / 1e6;
                    release.get();
                    if (ms < 1000 || store.itemCount() != 21) throw new AssertionError("Lock test not exercised");
                    getLogger().info("VC_DB_LOCK_MS=" + ms);
                }
            } else if (mode.equals("disconnect")) {
                // The harness closes the forwarding listener after this file becomes visible.
                Files.writeString(getDataFolder().toPath().resolve("disconnect-ready"), "ready");
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(getDataFolder().toPath().resolve("disconnect-go"))) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Disconnect gate timeout");
                    Thread.sleep(10);
                }
                long began = System.nanoTime();
                try {
                    store.deposit(new ItemStack(Material.STONE, 1), rest -> { throw new AssertionError("Disconnected write delivered items"); });
                    throw new AssertionError("Disconnected write accepted");
                } catch (IllegalStateException expected) { }
                getLogger().info("VC_DB_DISCONNECT_MS=" + (System.nanoTime() - began) / 1e6);
                if (store.itemCount() != 20) throw new AssertionError("Disconnect changed local count");
                try (TrashStore reader = TrashStore.mysql(direct, "voidclear", "voidclear", "faultprobe_", getLogger())) {
                    if (reader.itemCount() != 20) throw new AssertionError("Disconnect changed stored count");
                }
            } else throw new IllegalArgumentException("Unknown fault mode");
        }
    }

    private void cacheCheck(VoidClearPlugin plugin) throws Exception {
        TrashStore store = plugin.trash();
        plugin.clearTrash();
        if (!store.collect(new ItemStack(Material.STONE, 20).serializeAsBytes(), () -> {})) throw new AssertionError("seed");
        if (store.itemCount() != 20 || store.itemCount() != 20) throw new AssertionError("initial count");
        ItemStack cachedBeforeMerge = cachedStack(store, store.ids().getFirst());
        if (!store.deposit(new ItemStack(Material.STONE, 30), remainder -> {
            if (remainder != null) throw new AssertionError("unexpected remainder");
        })) throw new AssertionError("merge");
        if (cachedBeforeMerge.getAmount() != 20) throw new AssertionError("Merge mutated read-only cached stack");
        if (store.size() != 1 || store.itemCount() != 50) throw new AssertionError("merge mutated cached stack or stale count");
        var slots = new ArrayList<ItemStack>(java.util.Collections.nCopies(45, null));
        slots.set(0, new ItemStack(Material.DIAMOND, 3));
        if (!store.replacePage(0, 45, store.page(0, 45), slots)) throw new AssertionError("replace");
        if (store.itemCount() != 3) throw new AssertionError("replace kept old decode");
        if (!store.deposit(new ItemStack(Material.STONE, 4), remainder -> {})) throw new AssertionError("different type");
        if (store.size() != 2 || store.itemCount() != 7) throw new AssertionError("wrong metadata/type cache");
        long first = store.ids().getFirst();
        if (!store.claim(first, bytes -> new ItemStack(Material.GOLD_INGOT, 2).serializeAsBytes())) throw new AssertionError("claim");
        if (store.itemCount() != 6) throw new AssertionError("claim stale cache");
        if (store.clear(java.util.List.of(first)) != 1 || store.itemCount() != 4) throw new AssertionError("clear stale cache");
        var cacheField = TrashStore.class.getDeclaredField("decoded"); cacheField.setAccessible(true);
        synchronized (store) {
            if (((java.util.Map<?, ?>) cacheField.get(store)).containsKey(first)) throw new AssertionError("Persisted SQLite deletion retained cache");
        }
        if (store.clearAll() != 4 || store.itemCount() != 0) throw new AssertionError("clear all count");
    }

    private void sweepReceiptCheck(VoidClearPlugin plugin) throws Exception {
        TrashStore store = plugin.trash();
        var entity = java.util.UUID.randomUUID();
        var world = getServer().getWorlds().getFirst();
        var source = world.dropItem(world.getSpawnLocation().add(0, 5, 0), new ItemStack(Material.QUARTZ, 16));
        source.setPickupDelay(32767); source.setGravity(false);
        var recovery = new net.voidclear.v2.listener.SweepRecovery(plugin);
        var applied = new java.util.concurrent.atomic.AtomicBoolean();
        if (!store.depositEntity(entity, 0, new ItemStack(Material.QUARTZ, 16), (rest, sequence) -> {
            if (rest != null || sequence != 1) throw new AssertionError("receipt remainder");
            applied.set(true);
        }) || !applied.get()) throw new AssertionError("receipt not committed before effect");
        if (store.sweepReceipts(entity).size() != 1) throw new AssertionError("source receipt missing");
        // Source UUID cannot be forged through Bukkit: test the real restored-source path with its own receipt.
        if (!store.depositEntity(source.getUniqueId(), 0, source.getItemStack(), (rest, sequence) -> {}))
            throw new AssertionError("restored source seed rejected");
        if (recovery.reconcile(source) || source.isValid()) throw new AssertionError("committed source survived reconciliation");
        long before = store.itemCount();
        try {
            store.depositEntity(entity, 0, new ItemStack(Material.QUARTZ, 16), (rest, sequence) -> { throw new AssertionError("stale receipt delivered twice"); });
            throw new AssertionError("stale source accepted");
        } catch (IllegalStateException expected) { }
        if (store.itemCount() != before) throw new AssertionError("stale source changed bin");
        try {
            store.depositEntity(source.getUniqueId(), 1, new ItemStack(Material.QUARTZ, 16), (rest, sequence) -> { throw new AssertionError("terminal source delivered twice"); });
            throw new AssertionError("fully consumed source ID reused");
        } catch (IllegalStateException expected) { }
        if (store.itemCount() != before) throw new AssertionError("terminal source changed bin");
        var mismatch = world.dropItem(world.getSpawnLocation().add(0, 5, 0), new ItemStack(Material.QUARTZ, 8));
        mismatch.setPickupDelay(32767); mismatch.setGravity(false);
        if (!store.depositEntity(mismatch.getUniqueId(), 0, new ItemStack(Material.QUARTZ, 16), (rest, sequence) -> {}))
            throw new AssertionError("mismatch source seed rejected");
        if (recovery.reconcile(mismatch) || mismatch.getItemStack().getAmount() != 8
                || mismatch.canPlayerPickup() || mismatch.canMobPickup()) throw new AssertionError("mismatched source not isolated intact");
        mismatch.remove();
        plugin.clearTrash();
        var write = TrashStore.class.getDeclaredMethod("transaction", java.util.function.Supplier.class); write.setAccessible(true);
        byte[] stone = new ItemStack(Material.STONE, 64).serializeAsBytes();
        write.invoke(store, (java.util.function.Supplier<Boolean>) () -> {
            if (!store.collect(new ItemStack(Material.QUARTZ, 60).serializeAsBytes(), () -> {})) throw new AssertionError("partial seed");
            for (int i = 1; i < TrashStore.CAPACITY; i++) if (!store.collect(stone, () -> {})) throw new AssertionError("partial capacity fixture");
            return true;
        });
        var partial = world.dropItem(world.getSpawnLocation().add(0, 5, 0), new ItemStack(Material.QUARTZ, 16));
        partial.setPickupDelay(32767); partial.setGravity(false);
        if (!store.depositEntity(partial.getUniqueId(), 0, partial.getItemStack(), (rest, sequence) -> {
            if (rest == null || rest.getAmount() != 12) throw new AssertionError("partial receipt remainder");
        })) throw new AssertionError("partial deposit rejected");
        if (recovery.reconcile(partial) || partial.getItemStack().getAmount() != 12 || recovery.sequence(partial) != 1)
            throw new AssertionError("partial restored source mismatch");
        if (!recovery.reconcile(partial)) throw new AssertionError("already reconciled remainder blocked");
        plugin.clearTrash();
        if (!store.depositEntity(partial.getUniqueId(), 1, partial.getItemStack(), (rest, sequence) -> {}))
            throw new AssertionError("second source transition rejected");
        partial.setItemStack(new ItemStack(Material.QUARTZ, 16));
        partial.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "sweep_sequence"));
        if (recovery.reconcile(partial) || partial.isValid() || store.itemCount() != 12)
            throw new AssertionError("multi-generation receipt chain replayed or lost items");
    }

    private ItemStack cachedStack(TrashStore store, long id) throws Exception {
        var field = TrashStore.class.getDeclaredField("decoded"); field.setAccessible(true);
        var cache = (java.util.Map<?, ?>) field.get(store);
        Object entry = cache.get(id);
        if (entry == null) throw new AssertionError("Expected cached item missing");
        var stack = entry.getClass().getDeclaredMethod("stack"); stack.setAccessible(true);
        return (ItemStack) stack.invoke(entry);
    }

    private void mysqlCacheCheck() throws Exception {
        String url = "jdbc:mysql://127.0.0.1:33317/voidclear_test?useSSL=false&serverTimezone=UTC";
        try (TrashStore a = TrashStore.mysql(url, "voidclear", "voidclear", "cacheprobe_", getLogger());
             TrashStore b = TrashStore.mysql(url, "voidclear", "voidclear", "cacheprobe_", getLogger());
             var connection = java.sql.DriverManager.getConnection(url, "voidclear", "voidclear");
             var sql = connection.createStatement()) {
            a.clear(a.ids());
            sql.executeUpdate("DROP TRIGGER IF EXISTS cacheprobe_fail");
            sql.executeUpdate("DROP TRIGGER IF EXISTS cacheprobe_update");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS cacheprobe_audit (kind VARCHAR(10) NOT NULL)");
            sql.executeUpdate("CREATE TRIGGER cacheprobe_update AFTER UPDATE ON cacheprobe_trash_items FOR EACH ROW INSERT INTO cacheprobe_audit VALUES ('UPDATE')");
            try {
                synchronized (a) {
                    if (!a.deposit(new ItemStack(Material.STONE, 20), remainder -> {})) throw new AssertionError("mysql seed");
                    if (a.itemCount() != 20) throw new AssertionError("mysql initial cache");
                    long id = a.ids().getFirst();
                    ItemStack old = cachedStack(a, id);
                    sql.executeUpdate("DELETE FROM cacheprobe_audit");
                    if (!a.deposit(new ItemStack(Material.STONE, 30), remainder -> {})) throw new AssertionError("mysql merge");
                    if (old.getAmount() != 20) throw new AssertionError("mysql merge mutated cache");
                    try (var rows = sql.executeQuery("SELECT COUNT(*) FROM cacheprobe_audit")) {
                        if (!rows.next() || rows.getInt(1) != 1 || !a.ids().equals(List.of(id)))
                            throw new AssertionError("Bytes-only merge did not update exactly one retained row");
                    }
                    if (a.itemCount() != 50) throw new AssertionError("mysql merged count");
                    old = cachedStack(a, id);
                    byte[] before = a.page(0, 45).getFirst().bytes();
                    long revision;
                    try (var rows = sql.executeQuery("SELECT revision FROM cacheprobe_trash_items_meta WHERE id=1")) {
                        if (!rows.next()) throw new AssertionError("missing revision");
                        revision = rows.getLong(1);
                    }
                    sql.executeUpdate("CREATE TRIGGER cacheprobe_fail BEFORE UPDATE ON cacheprobe_trash_items FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected cache merge failure'");
                    try {
                        a.deposit(new ItemStack(Material.STONE, 10), remainder -> { throw new AssertionError("Failed merge delivered effect"); });
                        throw new AssertionError("Failed merge accepted");
                    } catch (IllegalStateException expected) { }
                    finally { sql.executeUpdate("DROP TRIGGER IF EXISTS cacheprobe_fail"); }
                    if (old.getAmount() != 50 || a.itemCount() != 50
                            || !java.util.Arrays.equals(before, a.page(0, 45).getFirst().bytes()))
                        throw new AssertionError("Merge rollback corrupted cached quantity");
                    try (var rows = sql.executeQuery("SELECT revision FROM cacheprobe_trash_items_meta WHERE id=1")) {
                        if (!rows.next() || rows.getLong(1) != revision) throw new AssertionError("Failed merge advanced revision");
                    }
                    if (!b.deposit(new ItemStack(Material.STONE, 13), remainder -> {})) throw new AssertionError("remote merge");
                    var refresh = TrashStore.class.getDeclaredMethod("refreshDatabaseIfSafe"); refresh.setAccessible(true);
                    refresh.invoke(a);
                    if (a.itemCount() != 63 || cachedStack(a, id).getAmount() != 63) throw new AssertionError("Remote cache stale");
                    if (!a.deposit(new ItemStack(Material.STONE, 1), remainder -> {})) throw new AssertionError("post refresh merge");
                    if (a.size() != 1 || a.itemCount() != 64) throw new AssertionError("Remote refresh merge limit wrong");
                    b.clear(b.ids());
                    refresh.invoke(a);
                    var cache = TrashStore.class.getDeclaredField("decoded"); cache.setAccessible(true);
                    var bytes = TrashStore.class.getDeclaredField("decodedBytes"); bytes.setAccessible(true);
                    if (a.size() != 0 || !((java.util.Map<?, ?>) cache.get(a)).isEmpty() || bytes.getLong(a) != 0)
                        throw new AssertionError("Remote deletion retained decoded payload");
                }
            } finally {
                sql.executeUpdate("DROP TRIGGER IF EXISTS cacheprobe_fail");
                sql.executeUpdate("DROP TRIGGER IF EXISTS cacheprobe_update");
                a.clear(a.ids());
                sql.executeUpdate("DROP TABLE IF EXISTS cacheprobe_audit");
            }
        }
    }

    private void rejoinDropCheck(VoidClearPlugin plugin, String name, String stage, int expected) throws Exception {
        var player = getServer().getPlayerExact(name);
        if (player == null) throw new AssertionError("Rejoin player missing");
        var transfers = plugin.playerTransfers();
        var sources = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).stream()
                .filter(transfers::managedDrop).filter(item -> item.getItemStack().getType() == Material.DIAMOND).toList();
        if (stage.equals("partial") || stage.equals("fillpartial")) {
            if (sources.size() != 1) throw new AssertionError("Expected one managed source");
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 60));
            if (stage.equals("partial"))
                getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(player, sources.getFirst(), 12));
            else player.saveData();
        } else if (stage.equals("preparefull")) {
            player.getInventory().clear();
            player.saveData();
        } else if (stage.equals("pickup") || stage.equals("pickupfull")) {
            for (var item : sources)
                getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(player, item, 12));
        } else if (stage.equals("consume")) {
            player.getInventory().setItem(35, null);
            for (var item : sources)
                getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(player, item, 0));
        } else if (!stage.equals("inspect") && !stage.equals("verifyfull")) throw new IllegalArgumentException("Unknown rejoin stage");
        int held = java.util.Arrays.stream(player.getInventory().getStorageContents()).filter(java.util.Objects::nonNull)
                .filter(stack -> stack.getType() == Material.DIAMOND).mapToInt(ItemStack::getAmount).sum();
        if (held != expected) throw new AssertionError("Held " + held + " instead of " + expected);
        int ground = sources.stream().filter(org.bukkit.entity.Item::isValid).mapToInt(item -> item.getItemStack().getAmount()).sum();
        int expectedGround = stage.equals("consume") || stage.equals("pickupfull") || stage.equals("verifyfull") ? 0
                : stage.equals("fillpartial") || expected == 0 ? 16 : 12;
        if (ground != expectedGround || plugin.trash().unresolvedPlayerDrops(player.getUniqueId()) != (expectedGround > 0))
            throw new AssertionError("Source quantity or retained parent fence mismatch: " + ground);
        for (var item : sources) if (!plugin.trash().sweepReceipts(item.getUniqueId()).isEmpty()) {
            var last = plugin.trash().sweepReceipts(item.getUniqueId()).getLast();
            if (ground == 0 ? last.remainder() != null : last.remainder() == null
                    || ItemStack.deserializeBytes(last.remainder()).getAmount() != ground)
                throw new AssertionError("Retained remainder bytes mismatch");
        }
        var checkpoint = plugin.trash().playerCheckpoint(player.getUniqueId());
        var file = net.voidclear.v2.store.PlayerDataDirectory.resolve(getServer()).resolve(player.getUniqueId() + ".dat");
        net.voidclear.v2.store.PlayerSaveReceipt.verifyAndForce(file, "voidclear:player_receipt", checkpoint.operation().toString());
        transfers.session(player);
    }

    private void reloadTimers(VoidClearPlugin plugin) throws Exception {
        var file = plugin.getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(file);
        try {
            var config = new org.bukkit.configuration.file.YamlConfiguration();
            config.loadFromString(original);
            config.set("timer.mode", "interval");
            config.set("timer.period", 1234);
            config.save(file.toFile());
            plugin.reload();
            if (Math.abs(plugin.sweepTimer().secondsLeft() - 1234) > 1) throw new AssertionError("Period reload kept old deadline");
            long deadline = plugin.sweepTimer().deadline();
            plugin.reload();
            if (plugin.sweepTimer().deadline() != deadline) throw new AssertionError("Unchanged reload reset deadline");
            String time = java.time.LocalTime.now().plusMinutes(10).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            config.set("timer.mode", "daily");
            config.set("timer.times", List.of(time));
            config.save(file.toFile());
            plugin.reload();
            if (Math.abs(plugin.sweepTimer().secondsLeft() - dailyLeft(time)) > 2) throw new AssertionError("Mode reload kept interval deadline");
            time = java.time.LocalTime.now().plusMinutes(20).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            config.set("timer.times", List.of(time));
            config.save(file.toFile());
            plugin.reload();
            if (Math.abs(plugin.sweepTimer().secondsLeft() - dailyLeft(time)) > 2) throw new AssertionError("Daily time reload kept old deadline");
            deadline = plugin.sweepTimer().deadline();
            config.set("timer.mode", "invalid");
            config.save(file.toFile());
            try { plugin.reload(); throw new AssertionError("Invalid reload accepted"); }
            catch (IllegalStateException expected) { }
            if (plugin.sweepTimer().deadline() != deadline || !plugin.settings().pace().daily())
                throw new AssertionError("Failed reload changed active settings or timer");
        } finally {
            Files.writeString(file, original);
            plugin.reload();
        }
    }

    private long dailyLeft(String time) {
        return net.voidclear.v2.runtime.Timing.secondsUntil(1, true, List.of(time), System.currentTimeMillis(), java.time.ZoneId.systemDefault());
    }

    private void managedDropCheck(VoidClearPlugin plugin) throws Exception {
        var player = getServer().getOnlinePlayers().iterator().next();
        if (!player.getItemOnCursor().isEmpty()) throw new AssertionError("Clear test cursor first");
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 60));
        Class<?> pickupType = Class.forName("net.voidclear.v2.listener.PlayerTransfers$ItemPickup");
        var pickupConstructor = pickupType.getDeclaredConstructor(org.bukkit.entity.Player.class, ItemStack.class);
        pickupConstructor.setAccessible(true);
        Object snapshot = pickupConstructor.newInstance(player, new ItemStack(Material.DIAMOND, 16));
        var beforeField = pickupType.getDeclaredField("before"); beforeField.setAccessible(true);
        var afterField = pickupType.getDeclaredField("after"); afterField.setAccessible(true);
        player.getInventory().getItem(0).setAmount(61);
        if (((ItemStack[]) beforeField.get(snapshot))[0].getAmount() != 60
                || ((ItemStack[]) afterField.get(snapshot))[0].getAmount() != 64)
            throw new AssertionError("Live inventory mutation changed the captured pickup snapshots");
        player.getInventory().clear();
        var transfers = plugin.playerTransfers();
        var operation = java.util.UUID.randomUUID();
        plugin.trash().preparePlayerOnly(player.getUniqueId(), transfers.session(player), operation,
                transfers.previousOperation(player), new byte[]{1});
        var item = player.getWorld().dropItem(player.getLocation().add(0, 3, 0), new ItemStack(Material.DIAMOND, 16),
                entity -> transfers.registerDrop(entity, operation, 0));
        item.setPickupDelay(32767);
        transfers.save(player, operation, "DROPS");
        if (!plugin.trash().unresolvedPlayerDrops(player.getUniqueId())) throw new AssertionError("Drop fence missing");
        var hopper = new org.bukkit.event.inventory.InventoryPickupItemEvent(getServer().createInventory(null, 9), item);
        getServer().getPluginManager().callEvent(hopper);
        if (!hopper.isCancelled()) throw new AssertionError("Hopper bypassed managed source");
        var before = plugin.trash().playerCheckpoint(player.getUniqueId());
        player.setItemOnCursor(new ItemStack(Material.EMERALD, 3));
        try {
            var blockedPickup = new org.bukkit.event.entity.EntityPickupItemEvent(player, item, 0);
            getServer().getPluginManager().callEvent(blockedPickup);
            if (!blockedPickup.isCancelled() || !item.isValid() || item.getItemStack().getAmount() != 16
                    || !before.equals(plugin.trash().playerCheckpoint(player.getUniqueId()))
                    || !plugin.trash().sweepReceipts(item.getUniqueId()).isEmpty()
                    || !player.getInventory().isEmpty() || player.getItemOnCursor().getAmount() != 3)
                throw new AssertionError("Nonempty cursor pickup changed source, player or journal");
        } finally { player.setItemOnCursor(null); }
        var pickup = new org.bukkit.event.entity.EntityPickupItemEvent(player, item, 0);
        getServer().getPluginManager().callEvent(pickup);
        if (!pickup.isCancelled() || item.isValid()) throw new AssertionError("Managed pickup did not consume source");
        int count = java.util.Arrays.stream(player.getInventory().getStorageContents()).filter(java.util.Objects::nonNull)
                .filter(stack -> stack.getType() == Material.DIAMOND).mapToInt(ItemStack::getAmount).sum();
        if (count != 16 || plugin.trash().unresolvedPlayerDrops(player.getUniqueId())
                || !plugin.trash().playerCheckpoint(player.getUniqueId()).state().equals("DONE"))
            throw new AssertionError("Managed pickup lost quantity or retained completed fence");
        if (plugin.trash().sweepReceipts(item.getUniqueId()).getLast().remainder() != null)
            throw new AssertionError("Managed source terminal receipt missing");
        // Only four fit: the remaining twelve must keep their source and unresolved-parent fence.
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 60));
        var partialOperation = java.util.UUID.randomUUID();
        plugin.trash().preparePlayerOnly(player.getUniqueId(), transfers.session(player), partialOperation,
                transfers.previousOperation(player), new byte[]{2});
        var partial = player.getWorld().dropItem(player.getLocation().add(0, 3, 0), new ItemStack(Material.DIAMOND, 16),
                entity -> transfers.registerDrop(entity, partialOperation, 0));
        partial.setPickupDelay(32767);
        transfers.save(player, partialOperation, "DROPS");
        getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(player, partial, 12));
        var receipts = plugin.trash().sweepReceipts(partial.getUniqueId());
        if (player.getInventory().getItem(0).getAmount() != 64 || !partial.isValid()
                || partial.getItemStack().getAmount() != 12 || receipts.size() != 1
                || ItemStack.deserializeBytes(receipts.getFirst().remainder()).getAmount() != 12
                || !plugin.trash().unresolvedPlayerDrops(player.getUniqueId()))
            throw new AssertionError("Partial pickup lost remainder or released its parent fence");
        getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(player, partial, 12));
        if (plugin.trash().sweepReceipts(partial.getUniqueId()).size() != 1 || partial.getItemStack().getAmount() != 12)
            throw new AssertionError("Full inventory advanced the source twice");
        player.getInventory().clear();
        getServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPickupItemEvent(player, partial, 0));
        count = java.util.Arrays.stream(player.getInventory().getStorageContents()).filter(java.util.Objects::nonNull)
                .filter(stack -> stack.getType() == Material.DIAMOND).mapToInt(ItemStack::getAmount).sum();
        receipts = plugin.trash().sweepReceipts(partial.getUniqueId());
        if (count != 12 || partial.isValid() || receipts.size() != 2 || receipts.getLast().remainder() != null
                || plugin.trash().unresolvedPlayerDrops(player.getUniqueId()))
            throw new AssertionError("Final partial pickup lost quantity or retained its parent fence");
        player.getInventory().clear();
    }

    private void deliveryFault(VoidClearPlugin plugin) throws Exception {
        var real = getServer().getOnlinePlayers().iterator().next();
        var listener = plugin.trashListener();
        var configField = listener.getClass().getDeclaredField("config"); configField.setAccessible(true);
        Class<?> menuType = Class.forName("net.voidclear.v2.listener.TrashListener$Menu");
        Class<?> transferType = Class.forName("net.voidclear.v2.listener.TrashListener$Transfer");
        var menuConstructor = menuType.getDeclaredConstructors()[0]; menuConstructor.setAccessible(true);
        var transferConstructor = transferType.getDeclaredConstructors()[0]; transferConstructor.setAccessible(true);
        var deliver = listener.getClass().getDeclaredMethod("deliver", org.bukkit.entity.Player.class, menuType, transferType, boolean.class);
        deliver.setAccessible(true);
        var invalidated = menuType.getDeclaredField("invalidated"); invalidated.setAccessible(true);
        var drops = transferType.getDeclaredField("drops"); drops.setAccessible(true);
        // Fail each delivery boundary, including after one simulated drop already succeeded.
        for (int boundary = 0; boundary < 5; boundary++) {
            int failureAt = boundary;
            int[] writes = {0};
            var inventory = (org.bukkit.inventory.PlayerInventory) java.lang.reflect.Proxy.newProxyInstance(
                    getClassLoader(), new Class[]{org.bukkit.inventory.PlayerInventory.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getStorageContents")) return new ItemStack[36];
                        if (method.getName().equals("getItemInOffHand")) return null;
                        if (method.getName().startsWith("set")) {
                            if (writes[0]++ == failureAt) throw new IllegalStateException("injected delivery boundary " + failureAt);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            var world = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(getClassLoader(), new Class[]{org.bukkit.World.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("dropItem")) throw new UnsupportedOperationException(method.getName());
                        if (writes[0]++ == failureAt) throw new IllegalStateException("injected drop boundary " + failureAt);
                        return null;
                    });
            var player = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(getClassLoader(), new Class[]{org.bukkit.entity.Player.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getInventory")) return inventory;
                        if (method.getName().equals("getWorld")) return world;
                        if (method.getName().equals("sendMessage")) return null;
                        if (method.getName().equals("setItemOnCursor")) {
                            if (writes[0]++ == failureAt) throw new IllegalStateException("injected cursor boundary");
                            return null;
                        }
                        return method.invoke(real, args);
                    });
            Object menu = menuConstructor.newInstance(configField.get(listener), 0, java.util.Map.of());
            Object transfer = transferConstructor.newInstance(listener, player, menu);
            var failureFolder = plugin.getDataFolder().toPath().resolve("delivery-failures");
            java.util.Set<java.nio.file.Path> oldFiles;
            if (Files.isDirectory(failureFolder)) {
                try (var files = Files.list(failureFolder)) { oldFiles = files.collect(java.util.stream.Collectors.toSet()); }
            } else oldFiles = java.util.Set.of();
            @SuppressWarnings("unchecked") var pending = (List<ItemStack>) drops.get(transfer);
            pending.add(new ItemStack(Material.STONE)); pending.add(new ItemStack(Material.STONE));
            if ((boolean) deliver.invoke(listener, player, menu, transfer, true)) throw new AssertionError("Failed delivery reported success");
            if (!invalidated.getBoolean(menu) || writes[0] != failureAt + 1) throw new AssertionError("Delivery retried or left menu unlocked");
            try (var files = Files.list(failureFolder)) {
                var created = files.filter(file -> !oldFiles.contains(file)).toList();
                if (created.size() != 1) throw new AssertionError("Failure evidence missing or repeated");
                java.util.Properties evidence = new java.util.Properties();
                try (var input = Files.newInputStream(created.getFirst())) { evidence.load(input); }
                String expectedStage = new String[]{"storage", "offhand", "cursor", "drop[0]", "drop[1]"}[failureAt];
                if (!expectedStage.equals(evidence.getProperty("failedStage"))) throw new AssertionError("Wrong failure stage");
                if (!"true".equals(evidence.getProperty("storageAccepted"))
                        || !"2".equals(evidence.getProperty("planned.drops.length"))
                        || !"36".equals(evidence.getProperty("before.storage.length"))) throw new AssertionError("Incomplete evidence");
            }
        }
    }
}
