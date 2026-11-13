package net.voidclear.v2.listener;

import net.voidclear.VoidClearPlugin;
import net.voidclear.v2.store.PlayerSaveReceipt;
import net.voidclear.v2.store.TrashStore;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A durable player fence; unresolved operations are evidence, never permission to replay items. */
public final class PlayerTransfers implements Listener {
    private final VoidClearPlugin plugin;
    private final NamespacedKey receipt;
    private final NamespacedKey dropOperation;
    private final NamespacedKey sweepSequence;
    private final java.nio.file.Path playerData;
    private final net.voidclear.v2.store.ReceiptCleanup receiptCleaner;
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Player> joined = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.function.BooleanSupplier> pendingConnections = new ConcurrentHashMap<>();
    private final Map<UUID, TrashStore.LoadedDrop> loadedDrops = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.List<TrashStore.DropChunk>> loginChunks = new ConcurrentHashMap<>();
    private final Map<TrashStore.DropChunk, Integer> chunkUsers = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<TrashStore.DropChunk>> acquiredChunks = new ConcurrentHashMap<>();
    private record RetiredSession(UUID session, String token) { }
    private final Map<UUID, RetiredSession> retired = new ConcurrentHashMap<>();
    private final UUID owner;
    private final NamespacedKey logoutReceipt;
    private volatile boolean stopping;
    // Process-local proof only: a restart must still reject unproven world-source durability.
    private final Map<UUID, TrashStore.PlayerCheckpoint> cleanLogouts = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> blocked = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> applying = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> isolating = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> receiptCleanup = ConcurrentHashMap.newKeySet();
    // Set only by the separately packaged, opt-in integration probe through reflection.
    private volatile java.util.function.Consumer<String> testBoundary;
    private static final Component WARNING = Component.text("VoidClear：存在未确认物品交易，请管理员保留玩家存档、世界和数据库核查；禁止重复补发。");

    public PlayerTransfers(VoidClearPlugin plugin) {
        this.plugin = plugin;
        owner = plugin.serverOwnership().id();
        receipt = new NamespacedKey(plugin, "player_receipt");
        logoutReceipt = new NamespacedKey(plugin, "session_logout");
        dropOperation = new NamespacedKey(plugin, "player_drop_operation");
        sweepSequence = new NamespacedKey(plugin, "sweep_sequence");
        try { playerData = net.voidclear.v2.store.PlayerDataDirectory.resolve(plugin.getServer()); }
        catch (java.io.IOException failed) { throw new IllegalStateException("实际玩家存档位置未确认，拒绝启用交易", failed); }
        plugin.getLogger().info("玩家收据验证目录：" + playerData);
        receiptCleaner = new net.voidclear.v2.store.ReceiptCleanup(playerData,
                plugin.getDataFolder().toPath().resolve("receipt-backups").toAbsolutePath().normalize(), receipt.toString());
    }

    public void markDrop(org.bukkit.entity.Item item, UUID operation) {
        item.getPersistentDataContainer().set(dropOperation, PersistentDataType.STRING, operation.toString());
        // Player pickup is journalled below; mobs/hoppers cannot provide a player-data receipt.
        item.setCanMobPickup(false);
    }

    /** Shares the admission gate: no login may start while an offline file is being checked or replaced. */
    public <T> T withOfflineReceipt(UUID player, java.util.function.Supplier<T> operation) {
        synchronized (this) {
            if (stopping || sessions.containsKey(player) || joined.containsKey(player)
                    || pendingConnections.containsKey(player) || retired.containsKey(player)
                    || blocked.contains(player) || applying.contains(player) || isolating.contains(player)
                    || !receiptCleanup.add(player))
                throw new IllegalStateException("玩家在线、正在登录/退出或仍有本服隔离状态，拒绝清理");
        }
        try { return operation.get(); }
        finally { receiptCleanup.remove(player); }
    }

    public net.voidclear.v2.store.ReceiptCleanup receiptCleaner() { return receiptCleaner; }

    public void registerDrop(org.bukkit.entity.Item item, UUID operation, int ordinal) {
        markDrop(item, operation);
        UUID entity = item.getUniqueId();
        byte[] data = item.getItemStack().serializeAsBytes();
        var chunk = new TrashStore.DropChunk(item.getWorld().getUID(), item.getLocation().getBlockX() >> 4, item.getLocation().getBlockZ() >> 4);
        if (plugin.trash().mysql()) {
            plugin.databaseTasks().submit(() -> { plugin.trash().registerPlayerDrop(entity, operation, ordinal, data, chunk); return true; })
                    .exceptionally(failed -> { plugin.getLogger().log(java.util.logging.Level.SEVERE, "来源登记失败，保持未确认隔离：" + entity, failed); return false; });
        } else plugin.trash().registerPlayerDrop(entity, operation, ordinal, data, chunk);
    }

    public boolean managedDrop(org.bukkit.entity.Item item) {
        return item.getPersistentDataContainer().has(dropOperation, PersistentDataType.STRING);
    }

    public void loadedDrop(org.bukkit.entity.Item item) {
        if (managedDrop(item)) loadedDrops.put(item.getUniqueId(), new TrashStore.LoadedDrop(
                item.getPersistentDataContainer().getOrDefault(sweepSequence, PersistentDataType.LONG, 0L),
                item.getItemStack().serializeAsBytes()));
    }

    @EventHandler
    public void unload(org.bukkit.event.world.EntitiesUnloadEvent event) {
        for (var entity : event.getEntities()) loadedDrops.remove(entity.getUniqueId());
    }

    @EventHandler
    public void removed(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
        loadedDrops.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && unavailable(player)) { event.setCancelled(true); return; }
        var item = event.getItem();
        if (!managedDrop(item)) return;
        event.setCancelled(true);
        if (!(event.getEntity() instanceof Player player)) return;
        // Other containers do not run the bin's cursor finalization; leave this source untouched until empty.
        if (!player.getItemOnCursor().isEmpty()) return;
        if (plugin.trash().mysql()) { pickupAsync(player, item); return; }
        try {
            UUID parent = UUID.fromString(item.getPersistentDataContainer().get(dropOperation, PersistentDataType.STRING));
            if (!plugin.trash().playerDropReady(item.getUniqueId(), parent)) return;
            ItemPickup pickup = new ItemPickup(player, item.getItemStack());
            if (!pickup.changed()) return;
            UUID operation = UUID.randomUUID();
            long applied = item.getPersistentDataContainer().getOrDefault(sweepSequence, PersistentDataType.LONG, 0L);
            if (!plugin.trash().claimPlayerDrop(player.getUniqueId(), transactionSession(player), operation, previousOperation(player),
                    pickup.evidence(item.getUniqueId(), parent, applied + 1),
                    item.getUniqueId(), parent, applied, item.getItemStack(), pickup.remainder)) return;
            boundary("prepared");
            applying(player);
            // Source transition is durable first; an old saved entity can only reconcile forward.
            if (pickup.remainder == null) item.remove();
            else {
                item.setItemStack(pickup.remainder);
                item.getPersistentDataContainer().set(sweepSequence, PersistentDataType.LONG, applied + 1);
            }
            player.getInventory().setStorageContents(pickup.after);
            boundary("storage");
            save(player, operation, "DONE");
        } catch (RuntimeException failed) { isolate(player, failed); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopper(org.bukkit.event.inventory.InventoryPickupItemEvent event) {
        if (managedDrop(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void merge(org.bukkit.event.entity.ItemMergeEvent event) {
        if (managedDrop(event.getEntity()) || managedDrop(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Item item && managedDrop(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void despawn(org.bukkit.event.entity.ItemDespawnEvent event) {
        if (managedDrop(event.getEntity())) event.setCancelled(true);
    }

    public UUID session(Player player) {
        UUID session = transactionSession(player);
        var checkpoint = plugin.trash().sessionCheckpoint(player.getUniqueId(), owner, session, "玩家未持有本服独占会话");
        if (checkpoint != null && (!checkpoint.state().equals("DONE") && !checkpoint.session().equals(session)
                || checkpoint.state().equals("QUARANTINED")))
            throw new IllegalStateException("玩家未确认交易属于其他会话或已经隔离");
        if ((checkpoint == null || checkpoint.state().equals("DONE")) && !java.util.Objects.equals(
                checkpoint == null ? null : checkpoint.operation().toString(),
                player.getPersistentDataContainer().get(receipt, PersistentDataType.STRING)))
            throw new IllegalStateException("当前背包收据与数据库不一致（含数据库记录缺失），禁止交易");
        return session;
    }

    /** Writers recheck SQL ownership and the expected operation inside their transaction. */
    public UUID transactionSession(Player player) {
        if (stopping || blocked.contains(player.getUniqueId())) throw new IllegalStateException("玩家交易已隔离或正在停用");
        UUID session = sessions.get(player.getUniqueId());
        if (session == null || joined.get(player.getUniqueId()) != player)
            throw new IllegalStateException("玩家未持有本服独占会话");
        return session;
    }

    public void applying(Player player) { applying.add(player.getUniqueId()); }
    public boolean pending(Player player) { return applying.contains(player.getUniqueId()); }
    public void finished(Player player) {
        if (joined.get(player.getUniqueId()) == player) applying.remove(player.getUniqueId());
    }
    public boolean live(Player player, UUID session) {
        return !stopping && !blocked.contains(player.getUniqueId()) && joined.get(player.getUniqueId()) == player
                && session.equals(sessions.get(player.getUniqueId())) && player.isOnline();
    }

    public void onPlayer(Player player, UUID session, org.bukkit.Location origin, Runnable task) {
        if (stopping) return;
        Runnable current = () -> {
            if (!stopping && ownsConnection(player, session, sessions, joined)) task.run();
        };
        if (!plugin.scheduler().folia()) {
            try { org.bukkit.Bukkit.getScheduler().runTask(plugin, current); }
            catch (RuntimeException refused) { plugin.getLogger().log(java.util.logging.Level.WARNING, "玩家回调拒收，保留持久隔离", refused); }
            return;
        }
        var delivered = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable once = () -> { if (delivered.compareAndSet(false, true)) current.run(); };
        try {
            player.getScheduler().run(plugin, ignored -> once.run(), () -> { });
            // A dead player's entity scheduler pauses until respawn; the captured region may still own it.
            org.bukkit.Bukkit.getRegionScheduler().run(plugin, origin, ignored -> {
                if (org.bukkit.Bukkit.isOwnedByCurrentRegion(player) && player.isDead()) once.run();
            });
        }
        catch (RuntimeException refused) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "玩家回调已结束，保留持久交易隔离：" + player.getUniqueId(), refused);
        }
    }

    private boolean unavailable(Player player) {
        return stopping || joined.get(player.getUniqueId()) != player
                || blocked.contains(player.getUniqueId()) || applying.contains(player.getUniqueId());
    }

    static boolean ownsConnection(Player player, UUID session, Map<UUID, UUID> sessions, Map<UUID, Player> joined) {
        return session != null && session.equals(sessions.get(player.getUniqueId())) && joined.get(player.getUniqueId()) == player;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void click(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && unavailable(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void drag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && unavailable(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void drop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (unavailable(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void swap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (unavailable(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void interact(org.bukkit.event.player.PlayerInteractEvent event) {
        if (unavailable(event.getPlayer())) event.setCancelled(true);
    }

    public UUID previousOperation(Player player) {
        String token = player.getPersistentDataContainer().get(receipt, PersistentDataType.STRING);
        return token == null ? null : UUID.fromString(token);
    }

    public void isolate(Player player, Throwable failure) {
        if (joined.get(player.getUniqueId()) != player) return;
        cleanLogouts.remove(player.getUniqueId());
        blocked.add(player.getUniqueId());
        if (!isolating.add(player.getUniqueId())) return;
        if (plugin.trash().mysql()) {
            UUID id = player.getUniqueId(), session = sessions.get(id);
            plugin.databaseTasks().submit(() -> {
                var current = plugin.trash().playerCheckpoint(id);
                if (current != null && current.session().equals(session))
                    plugin.trash().checkpointPlayer(id, session, current.operation(), "QUARANTINED");
                return true;
            }).exceptionally(failed -> { plugin.getLogger().log(java.util.logging.Level.SEVERE, "隔离数据库确认失败，保留原凭据：" + id, failed); return false; });
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "玩家物品交易已隔离：" + id + "；禁止补发或重放", failure);
            player.kick(WARNING);
            return;
        }
        try {
            var current = plugin.trash().playerCheckpoint(player.getUniqueId());
            if (current != null && current.session().equals(sessions.get(player.getUniqueId())))
                plugin.trash().checkpointPlayer(player.getUniqueId(), current.session(), current.operation(), "QUARANTINED");
        } catch (RuntimeException unavailable) { failure.addSuppressed(unavailable); }
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "玩家物品交易已隔离：" + player.getUniqueId()
                + "；不得自动回放、补发或恢复旧页面", failure);
        player.kick(WARNING);
    }

    /** Only call after all planned inventory writes, and after vanilla has finalized a drag. */
    public void save(Player player, UUID operation, String state) {
        save(player, operation, state, () -> { });
    }

    public int activeSessions() { return sessions.size(); }
    public int pendingCount() { return applying.size(); }
    public int isolatedCount() { return blocked.size(); }

    public void save(Player player, UUID operation, String state, Runnable confirmed) {
        UUID session = transactionSession(player);
        if (state.equals("DONE") && !player.getItemOnCursor().isEmpty()) throw new IllegalStateException("光标未清空，禁止确认");
        saveReceipt(player, operation);
        boundary("receipt");
        if (plugin.trash().mysql()) {
            UUID id = player.getUniqueId();
            var origin = player.getLocation();
            plugin.databaseTasks().submit(() -> {
                plugin.trash().checkpointPlayer(id, session, operation, state);
                boundary("confirmed");
                return true;
            }).whenComplete((saved, failed) -> onPlayer(player, session, origin, () -> {
                if (failed != null) isolate(player, failed);
                else if (live(player, session)) { finished(player); confirmed.run(); }
            }));
            return;
        }
        plugin.trash().checkpointPlayer(player.getUniqueId(), session, operation, state);
        applying.remove(player.getUniqueId());
        boundary("confirmed");
        confirmed.run();
    }

    private void saveReceipt(Player player, UUID operation) {
        player.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, operation.toString());
        player.saveData();
        // Verify the file used by PlayerDataStorage, regardless of the player's current dimension or world layout.
        var file = playerData.resolve(player.getUniqueId() + ".dat");
        try { PlayerSaveReceipt.verifyAndForce(file, receipt.toString(), operation.toString()); }
        catch (java.io.IOException failed) { throw new IllegalStateException("玩家存档未确认，保持交易隔离", failed); }
    }

    public void boundary(String stage) {
        var probe = testBoundary;
        if (probe != null) probe.accept(stage);
    }

    private void pickupAsync(Player player, org.bukkit.entity.Item item) {
        UUID id = player.getUniqueId(), session = transactionSession(player), previous = previousOperation(player);
        UUID entity = item.getUniqueId(), parent = UUID.fromString(item.getPersistentDataContainer().get(dropOperation, PersistentDataType.STRING));
        long applied = item.getPersistentDataContainer().getOrDefault(sweepSequence, PersistentDataType.LONG, 0L);
        ItemPickup pickup = new ItemPickup(player, item.getItemStack());
        if (!pickup.changed()) return;
        if (!plugin.sweepRecovery().reserve(entity)) return;
        UUID operation = UUID.randomUUID();
        byte[] evidence = pickup.evidence(entity, parent, applied + 1);
        var origin = player.getLocation();
        applying(player);
        plugin.databaseTasks().submit(() -> {
            if (!plugin.trash().playerDropReady(entity, parent)) return false;
            boolean accepted = plugin.trash().claimPlayerDrop(id, session, operation, previous, evidence,
                    entity, parent, applied, pickup.source, pickup.remainder);
            if (accepted) boundary("prepared");
            return accepted;
        }).whenComplete((accepted, failed) -> {
            // No transaction was prepared: release even when the player's connection has already retired.
            if (failed == null && Boolean.FALSE.equals(accepted)) plugin.sweepRecovery().release(entity);
            onPlayer(player, session, origin, () -> {
            if (failed != null) { isolate(player, failed); return; }
            if (!accepted) { finished(player); return; }
            try {
                if (!live(player, session) || player.isDead() || !org.bukkit.Bukkit.isOwnedByCurrentRegion(item) || !item.isValid()
                        || !java.util.Arrays.equals(item.getItemStack().serializeAsBytes(), pickup.source.serializeAsBytes())
                        || !player.getItemOnCursor().isEmpty() || !sameStorage(player.getInventory().getStorageContents(), pickup.before))
                    throw new IllegalStateException("等待领取提交时玩家或来源已变化");
                if (pickup.remainder == null) item.remove();
                else {
                    item.setItemStack(pickup.remainder);
                    item.getPersistentDataContainer().set(sweepSequence, PersistentDataType.LONG, applied + 1);
                }
                player.getInventory().setStorageContents(pickup.after);
                boundary("storage");
                save(player, operation, "DONE");
                plugin.sweepRecovery().release(entity);
            } catch (RuntimeException invalid) { isolate(player, invalid); }
            });
        });
    }

    private static boolean sameStorage(org.bukkit.inventory.ItemStack[] a, org.bukkit.inventory.ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            boolean emptyA = a[i] == null || a[i].isEmpty(), emptyB = b[i] == null || b[i].isEmpty();
            if (emptyA != emptyB || !emptyA && (!a[i].isSimilar(b[i]) || a[i].getAmount() != b[i].getAmount())) return false;
        }
        return true;
    }

    private static final class ItemPickup {
        final org.bukkit.inventory.ItemStack[] before, after;
        final org.bukkit.inventory.ItemStack source;
        org.bukkit.inventory.ItemStack remainder;

        ItemPickup(Player player, org.bukkit.inventory.ItemStack source) {
            this.source = source.clone();
            before = java.util.Arrays.stream(player.getInventory().getStorageContents())
                    .map(stack -> stack == null ? null : stack.clone()).toArray(org.bukkit.inventory.ItemStack[]::new);
            after = java.util.Arrays.stream(before).map(stack -> stack == null ? null : stack.clone())
                    .toArray(org.bukkit.inventory.ItemStack[]::new);
            remainder = source.clone();
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < after.length; i++) {
                var target = after[i];
                boolean empty = target == null || target.isEmpty();
                if (pass == 0 ? empty || !target.isSimilar(remainder) : !empty) continue;
                int amount = Math.min(remainder.getAmount(), empty ? remainder.getMaxStackSize() : target.getMaxStackSize() - target.getAmount());
                if (amount <= 0) continue;
                if (empty) { after[i] = remainder.clone(); after[i].setAmount(amount); }
                else target.setAmount(target.getAmount() + amount);
                remainder.setAmount(remainder.getAmount() - amount);
                if (remainder.isEmpty()) { remainder = null; return; }
            }
        }

        boolean changed() { return remainder == null || remainder.getAmount() != source.getAmount(); }

        byte[] evidence(UUID entity, UUID parent, long sequence) {
            var evidence = new java.util.Properties();
            evidence.setProperty("claim.entity", entity.toString());
            evidence.setProperty("claim.parent", parent.toString());
            evidence.setProperty("claim.sequence", Long.toString(sequence));
            evidence.setProperty("planned.storage.length", "36");
            evidence.setProperty("planned.cursor.length", "1");
            evidence.setProperty("planned.cursor.0", "empty");
            evidence.setProperty("planned.drops.length", "0");
            evidence.setProperty("source", java.util.Base64.getEncoder().encodeToString(source.serializeAsBytes()));
            for (int i = 0; i < before.length; i++) {
                evidence.setProperty("before.storage." + i, encode(before[i]));
                evidence.setProperty("planned.storage." + i, encode(after[i]));
            }
            evidence.setProperty("remainder", encode(remainder));
            try {
                var bytes = new java.io.ByteArrayOutputStream(); evidence.store(bytes, "Managed drop claim; NEVER replay"); return bytes.toByteArray();
            } catch (java.io.IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
        }

        private static String encode(org.bukkit.inventory.ItemStack stack) {
            return stack == null || stack.isEmpty() ? "empty" : java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID player = event.getUniqueId();
        UUID session;
        synchronized (this) {
            if (receiptCleanup.contains(player)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, WARNING);
                return;
            }
            retireDisconnectedLogin(player);
            if (!stopping && sessions.containsKey(player)) {
                var proof = retired.get(player);
                if (proof != null && proof.session().equals(sessions.get(player)) && !joined.containsKey(player)) {
                    try {
                        PlayerSaveReceipt.verifyAndForce(playerData.resolve(player + ".dat"), logoutReceipt.toString(), proof.token());
                        // Retire only the known closed connection locally; durable ownership and transaction fences remain.
                        sessions.remove(player, proof.session());
                        retired.remove(player, proof);
                    } catch (java.io.IOException invalid) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "已退出连接的实际存档证明无效，保留会话", invalid);
                    }
                }
            }
            if (stopping || sessions.containsKey(player)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, WARNING);
                return;
            }
            var resumed = cleanLogouts.get(player);
            session = resumed == null ? UUID.randomUUID() : resumed.session();
            sessions.put(player, session);
        }
        try {
            if (!plugin.trash().acquirePlayerSession(player, owner, session)) {
                sessions.remove(player, session);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, WARNING);
                return;
            }
            TrashStore.PlayerCheckpoint checkpoint = plugin.trash().playerCheckpoint(player);
            loadDropChunks(player);
            if (checkpoint != null && checkpoint.state().equals("DROPS")) {
                session = checkpoint.session();
                if (!plugin.trash().acquirePlayerSession(player, owner, session)) throw new IllegalStateException("续接来源会话归属已变化");
                sessions.put(player, session);
            }
            if (checkpoint != null && checkpoint.state().equals("PREPARED")
                    && plugin.trash().recoverSavedPlayer(event.getUniqueId(), playerData.resolve(event.getUniqueId() + ".dat"), receipt.toString(), loadedDrops))
                checkpoint = plugin.trash().playerCheckpoint(event.getUniqueId());
            var file = playerData.resolve(player + ".dat");
            String diskReceipt = java.nio.file.Files.notExists(file) ? null : PlayerSaveReceipt.read(file, receipt.toString());
            String expectedReceipt = checkpoint == null ? null : checkpoint.operation().toString();
            if (!java.util.Objects.equals(expectedReceipt, diskReceipt))
                throw new IllegalStateException("登录前玩家收据不一致：player=" + player + " disk=" + diskReceipt
                        + " current=" + expectedReceipt + "；可能连接了不同数据库/表或交易记录缺失，禁止自动清除收据");
            if (!canRejoin(event.getUniqueId(), checkpoint)) {
                releaseLoginChunks(player);
                sessions.remove(player, session);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, WARNING);
            } else {
                var connected = connectionStatus(event);
                if (connected != null) {
                    pendingConnections.put(player, connected);
                    if (!loginChunks.getOrDefault(player, java.util.List.of()).isEmpty()) {
                        UUID admitted = session;
                        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
                            synchronized (this) {
                                if (!admitted.equals(sessions.get(player)) || joined.containsKey(player)) { task.cancel(); return; }
                                try {
                                    if (!connected.getAsBoolean()) { retireDisconnectedLogin(player); task.cancel(); }
                                } catch (RuntimeException failed) { task.cancel(); }
                            }
                        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
                    }
                }
            }
        } catch (RuntimeException | java.io.IOException unavailable) {
            releaseLoginChunks(player);
            // This invocation never admitted a connection; keep SQL ownership but allow a later local verification attempt.
            sessions.remove(player, session);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, WARNING);
            plugin.getLogger().log(java.util.logging.Level.WARNING, "登录前交易核对失败，拒绝放行", unavailable);
        }
        // shortcut: old Paper versions without connection identity retain abandoned reservations until restart.
    }

    private void retireDisconnectedLogin(UUID player) {
        try {
            retireDisconnectedLogin(player, sessions, joined, pendingConnections);
            if (!sessions.containsKey(player)) releaseLoginChunks(player);
        } catch (RuntimeException failed) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "无法确认登录连接已关闭，保留会话", failed);
        }
    }

    static void retireDisconnectedLogin(UUID player, Map<UUID, UUID> sessions, Map<UUID, Player> joined,
            Map<UUID, java.util.function.BooleanSupplier> pendingConnections) {
        var connected = pendingConnections.get(player);
        if (connected == null || joined.containsKey(player) || connected.getAsBoolean()) return;
        // The exact pre-login connection is closed and never joined; SQL ownership/evidence remain intact.
        sessions.remove(player);
        pendingConnections.remove(player);
    }

    static java.util.function.BooleanSupplier connectionStatus(Object source) {
        // Reflection preserves compatibility with 1.21.x versions that predate the connection API.
        try {
            Object connection = source.getClass().getMethod("getConnection").invoke(source);
            if (connection == null) return null;
            java.lang.reflect.Method isConnected;
            try { isConnected = Class.forName("io.papermc.paper.connection.PlayerConnection").getMethod("isConnected"); }
            catch (NoSuchMethodException absent) { return nativeConnectionStatus(connection); }
            Object exactConnection = connection;
            var status = isConnected;
            return () -> {
                try { return (boolean) status.invoke(exactConnection); }
                catch (ReflectiveOperationException failed) { throw new IllegalStateException("无法读取登录连接状态", failed); }
            };
        } catch (NoSuchMethodException | ClassNotFoundException absent) { return null; }
        catch (ReflectiveOperationException failed) { throw new IllegalStateException("无法取得登录连接", failed); }
    }

    static java.util.function.BooleanSupplier nativeConnectionStatus(Object wrapper) throws ReflectiveOperationException {
        // Early connection APIs omit isConnected; inspect this exact wrapper's connection, never a UUID event.
        for (Class<?> type = wrapper.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField("connection");
                if (!field.trySetAccessible()) return null;
                Object connection = field.get(wrapper);
                if (connection == null) return null;
                var method = connection.getClass().getMethod("isConnected");
                return () -> {
                    try { return (boolean) method.invoke(connection); }
                    catch (ReflectiveOperationException failed) { throw new IllegalStateException("无法读取旧版登录连接状态", failed); }
                };
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private boolean canRejoin(UUID player, TrashStore.PlayerCheckpoint checkpoint) {
        if (checkpoint != null && checkpoint.equals(cleanLogouts.get(player))) return true;
        return (checkpoint == null || java.util.Set.of("DONE", "DROPS").contains(checkpoint.state()))
                && plugin.trash().verifiedPlayerDrops(player, loadedDrops);
    }

    private void loadDropChunks(UUID player) {
        var loads = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        var chunks = plugin.trash().playerDropChunks(player);
        loginChunks.put(player, chunks);
        for (var chunk : chunks) {
            var world = plugin.getServer().getWorld(chunk.world());
            if (world == null) throw new IllegalStateException("掉落来源世界未加载");
            var ready = new java.util.concurrent.CompletableFuture<Void>();
            loads.add(ready);
            if (!plugin.scheduler().onChunk(world, chunk.x(), chunk.z(), () -> {
                try {
                    var actual = world.getChunkAt(chunk.x(), chunk.z());
                    synchronized (loginChunks) {
                        if (stopping || loginChunks.get(player) != chunks) throw new IllegalStateException("来源加载预留已结束");
                        chunkUsers.compute(chunk, (key, count) -> {
                            if (count == null) actual.addPluginChunkTicket(plugin);
                            return count == null ? 1 : count + 1;
                        });
                        acquiredChunks.computeIfAbsent(player, ignored -> new java.util.HashSet<>()).add(chunk);
                    }
                    for (var entity : actual.getEntities())
                        if (entity instanceof org.bukkit.entity.Item item) loadedDrop(item);
                    ready.complete(null);
                } catch (RuntimeException failed) { ready.completeExceptionally(failed); }
            })) throw new IllegalStateException("无法核对来源区块");
        }
        try { java.util.concurrent.CompletableFuture.allOf(loads.toArray(java.util.concurrent.CompletableFuture[]::new)).get(15, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.lang.InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("来源区块核对被中断", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new IllegalStateException("来源区块尚未确认，拒绝登录", failed);
        }
    }

    private void releaseLoginChunks(UUID player) {
        java.util.Set<TrashStore.DropChunk> chunks;
        synchronized (loginChunks) { loginChunks.remove(player); chunks = acquiredChunks.remove(player); }
        if (chunks == null) return;
        for (var chunk : chunks) {
            var world = plugin.getServer().getWorld(chunk.world());
            if (world != null) plugin.scheduler().onChunk(world, chunk.x(), chunk.z(),
                    () -> chunkUsers.computeIfPresent(chunk, (key, count) -> {
                        if (count > 1) return count - 1;
                        world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
                        return null;
                    }));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var connected = connectionStatus(player);
        if (connected != null && !connected.getAsBoolean()) return;
        try {
            UUID session;
            synchronized (this) {
                blocked.add(player.getUniqueId());
                session = sessions.get(player.getUniqueId());
                if (stopping || session == null || joined.putIfAbsent(player.getUniqueId(), player) != null)
                    throw new IllegalStateException("登录时本服独占会话已变化");
                pendingConnections.remove(player.getUniqueId());
            }
            if (plugin.trash().mysql()) {
                String observed = player.getPersistentDataContainer().get(receipt, PersistentDataType.STRING);
                UUID admitted = session;
                var origin = player.getLocation();
                plugin.databaseTasks().submit(() -> {
                    var checkpoint = plugin.trash().sessionCheckpoint(player.getUniqueId(), owner, admitted, "登录归属已变化");
                    if (!canRejoin(player.getUniqueId(), checkpoint) || !java.util.Objects.equals(observed,
                            checkpoint == null ? null : checkpoint.operation().toString()))
                        throw new IllegalStateException("登录时存档与持久收据不符");
                    return checkpoint;
                }).whenComplete((checkpoint, failed) -> onPlayer(player, admitted, origin, () -> {
                    if (failed != null) isolate(player, failed);
                    else if (!stopping && joined.get(player.getUniqueId()) == player
                            && admitted.equals(sessions.get(player.getUniqueId()))) {
                        cleanLogouts.remove(player.getUniqueId());
                        blocked.remove(player.getUniqueId());
                    }
                    releaseLoginChunks(player.getUniqueId());
                }));
                return;
            }
            TrashStore.PlayerCheckpoint checkpoint = plugin.trash().sessionCheckpoint(player.getUniqueId(), owner, session, "登录时本服独占会话已变化");
            if (!canRejoin(player.getUniqueId(), checkpoint) || !java.util.Objects.equals(checkpoint == null ? null : checkpoint.operation().toString(),
                    player.getPersistentDataContainer().get(receipt, PersistentDataType.STRING)))
                throw new IllegalStateException("玩家存档与数据库收据不符，可能是旧存档或跨服未同步");
            var resumed = cleanLogouts.remove(player.getUniqueId());
            if (resumed != null && resumed.equals(checkpoint) && !session.equals(resumed.session()))
                throw new IllegalStateException("续接会话与登录归属不符");
            blocked.remove(player.getUniqueId());
        } catch (RuntimeException failed) { isolate(player, failed); releaseLoginChunks(player.getUniqueId()); }
        finally { if (!plugin.trash().mysql()) releaseLoginChunks(player.getUniqueId()); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId(), session = sessions.get(id);
        if (joined.get(id) != player || session == null) return;
        if (plugin.trash().mysql()) { quitAsync(player, id, session); return; }
        cleanLogouts.remove(id);
        TrashStore.PlayerCheckpoint releasedCheckpoint = null;
        boolean clean = false;
        try {
            if (!unavailable(player) && player.getItemOnCursor().isEmpty()) {
                var checkpoint = plugin.trash().sessionCheckpoint(id, owner, session, "退出会话归属已变化");
                if (checkpoint != null && java.util.Set.of("DONE", "DROPS").contains(checkpoint.state())
                        && checkpoint.session().equals(sessions.get(id))
                        && checkpoint.operation().equals(previousOperation(player))
                        && plugin.trash().unresolvedPlayerDrops(id)) {
                    saveReceipt(player, checkpoint.operation());
                    if (checkpoint.equals(plugin.trash().playerCheckpoint(id)))
                        cleanLogouts.put(id, checkpoint);
                }
                if (checkpoint == null || checkpoint.state().equals("DONE") || cleanLogouts.containsKey(id)) {
                    releasedCheckpoint = checkpoint;
                    clean = true;
                }
            }
        } catch (RuntimeException failed) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "退出时未取得同进程续接证明，保持交易隔离", failed);
        }
        try {
            String token = UUID.randomUUID().toString();
            // Native quit saves this token; retireAfterQuit verifies the resulting file before release.
            player.getPersistentDataContainer().set(logoutReceipt, PersistentDataType.STRING, token);
            retireWhenDisconnected(id, session, token, releasedCheckpoint, clean);
        } catch (RuntimeException failed) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "退出连接未取得实际存档证明，保留会话归属", failed);
        }
        joined.remove(id, player);
        blocked.remove(id);
        applying.remove(id);
        isolating.remove(id);
    }

    private void quitAsync(Player player, UUID id, UUID session) {
        boolean available = !unavailable(player) && player.getItemOnCursor().isEmpty();
        UUID operation = previousOperation(player);
        String token = UUID.randomUUID().toString();
        cleanLogouts.remove(id);
        try {
            // Native quit saves this token; retireAfterQuit verifies the resulting file before release.
            player.getPersistentDataContainer().set(logoutReceipt, PersistentDataType.STRING, token);
            plugin.databaseTasks().submit(() -> {
                var checkpoint = plugin.trash().sessionCheckpoint(id, owner, session, "退出归属已变化");
                boolean clean = available && (checkpoint == null && operation == null || checkpoint != null
                        && checkpoint.operation().equals(operation) && checkpoint.session().equals(session)
                        && java.util.Set.of("DONE", "DROPS").contains(checkpoint.state()));
                retireWhenDisconnected(id, session, token, checkpoint, clean);
                return true;
            }).exceptionally(failed -> { plugin.getLogger().log(java.util.logging.Level.WARNING, "退出未安全确认，保留会话：" + id, failed); return false; });
        } catch (RuntimeException failed) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "退出存档未确认，保留会话：" + id, failed);
        }
        joined.remove(id, player);
        blocked.remove(id); applying.remove(id); isolating.remove(id);
        releaseLoginChunks(id);
    }

    private void retireWhenDisconnected(UUID id, UUID session, String token, TrashStore.PlayerCheckpoint checkpoint, boolean release) {
        // No connection-close event identified only by UUID may retire another live connection.
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (stopping || !session.equals(sessions.get(id))) { task.cancel(); return; }
            if (plugin.getServer().getPlayer(id) != null || joined.containsKey(id)) return;
            task.cancel();
            if (plugin.trash().mysql()) {
                plugin.databaseTasks().submit(() -> { retireAfterQuit(id, session, token, checkpoint, release); return true; });
            } else {
                plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> retireAfterQuit(id, session, token, checkpoint, release));
            }
        }, 1L, 1L);
    }

    private void retireAfterQuit(UUID player, UUID session, String token, TrashStore.PlayerCheckpoint checkpoint, boolean release) {
        if (stopping || !session.equals(sessions.get(player)) || joined.containsKey(player)) return;
        try {
            // Vanilla may replace the file after the quit event; verify and force only after the connection closes, off-thread.
            PlayerSaveReceipt.verifyAndForce(playerData.resolve(player + ".dat"), logoutReceipt.toString(), token);
            if (!release) { retired.put(player, new RetiredSession(session, token)); return; }
            if (checkpoint != null)
                PlayerSaveReceipt.verifyAndForce(playerData.resolve(player + ".dat"), receipt.toString(), checkpoint.operation().toString());
            if (!java.util.Objects.equals(checkpoint, plugin.trash().playerCheckpoint(player)))
                throw new IllegalStateException("退出后交易凭据已变化");
            if (plugin.trash().unresolvedPlayerDrops(player)) {
                // Vanilla replaces player.dat during quit; only inspect it after the connection has left.
                if (plugin.trash().mysql() && checkpoint != null) cleanLogouts.put(player, checkpoint);
                if (checkpoint == null || !checkpoint.equals(cleanLogouts.get(player)))
                    throw new IllegalStateException("退出后来源续接证明已变化");
            } else plugin.trash().releasePlayerSession(player, owner, session);
            sessions.remove(player, session);
            retired.remove(player);
        } catch (RuntimeException | java.io.IOException failed) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "退出后未确认安全释放，保留会话归属：" + player, failed);
        }
    }

    public synchronized void shutdown() {
        stopping = true;
        synchronized (loginChunks) {
            for (var world : plugin.getServer().getWorlds()) world.removePluginChunkTickets(plugin);
            loginChunks.clear();
            acquiredChunks.clear();
            chunkUsers.clear();
        }
    }

    public boolean hasReservations() { return !sessions.isEmpty(); }

    public synchronized boolean pendingLoginOrQuit(UUID player) {
        return sessions.containsKey(player) && !retired.containsKey(player);
    }
}
