package net.voidclear.v2.listener;

import net.voidclear.VoidClearPlugin;
import net.voidclear.v2.store.TrashStore;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reconcile only a proven source transition, never replay a bin grant or restore an old page. */
public final class SweepRecovery implements Listener {
    private final VoidClearPlugin plugin;
    private final NamespacedKey sequenceKey;
    private final Set<UUID> quarantined = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, TrashStore.LoadedDrop> verified = new ConcurrentHashMap<>();

    public boolean reserve(UUID entity) { return !quarantined.contains(entity) && pending.add(entity); }
    public void release(UUID entity) { pending.remove(entity); verified.remove(entity); }

    public SweepRecovery(VoidClearPlugin plugin) {
        this.plugin = plugin;
        sequenceKey = new NamespacedKey(plugin, "sweep_sequence");
    }

    public long sequence(Item item) {
        return item.getPersistentDataContainer().getOrDefault(sequenceKey, PersistentDataType.LONG, 0L);
    }

    public void applied(Item item, ItemStack remainder, long sequence) {
        if (remainder == null) item.remove();
        else {
            item.setItemStack(remainder);
            item.getPersistentDataContainer().set(sequenceKey, PersistentDataType.LONG, sequence);
        }
    }

    /** Must run on this entity's owner thread, before picking up, merging or sweeping it. */
    public boolean reconcile(Item item) {
        UUID id = item.getUniqueId();
        if (quarantined.contains(id)) return false;
        if (pending.contains(id)) return false;
        try {
            if (plugin.trash().uncertainSweep(id)) throw new IllegalStateException("清扫提交结果未确认");
            long applied = sequence(item);
            // Ordinary drops have never been swept: skip the database, this runs on every pickup and merge.
            if (applied == 0 && !plugin.trash().hasSweepReceipts(id)) return item.isValid();
            if (plugin.trash().mysql()) {
                byte[] data = item.getItemStack().serializeAsBytes();
                var proof = verified.get(id);
                if (proof != null && proof.sequence() == applied && java.util.Arrays.equals(proof.data(), data)) return item.isValid();
                if (!reserve(id)) return false;
                plugin.databaseTasks().submit(() -> plugin.trash().sweepReceipts(id)).whenComplete((receipts, failed) -> {
                    try { item.getScheduler().run(plugin, task -> {
                        try {
                            if (failed != null) throw new IllegalStateException("来源凭据读取失败", failed);
                            if (!item.isValid() || sequence(item) != applied || !java.util.Arrays.equals(data, item.getItemStack().serializeAsBytes()))
                                throw new IllegalStateException("来源等待期间已变化");
                            reconcileReceipts(item, applied, receipts);
                            if (item.isValid()) {
                                verified.put(id, new TrashStore.LoadedDrop(sequence(item), item.getItemStack().serializeAsBytes()));
                                plugin.playerTransfers().loadedDrop(item);
                            }
                        } catch (RuntimeException invalid) { quarantine(item, invalid); }
                        finally { pending.remove(id); }
                    }, () -> pending.remove(id)); }
                    catch (RuntimeException refused) { quarantined.add(id); }
                });
                return false;
            }
            var receipts = plugin.trash().sweepReceipts(id);
            return reconcileReceipts(item, applied, receipts);
        } catch (RuntimeException failed) {
            quarantine(item, failed);
            return false;
        }
    }

    private boolean reconcileReceipts(Item item, long applied, java.util.List<TrashStore.SweepReceipt> receipts) {
            long observedSequence = applied;
            long latest = receipts.isEmpty() ? 0 : receipts.getLast().sequence();
            if (applied < 0 || applied > latest) throw new IllegalStateException("实体凭据超前于数据库，可能回滚或删除了凭据表");
            if (applied > 0 && receipts.get(Math.toIntExact(applied - 1)).remainder() == null)
                throw new IllegalStateException("已全部移除的来源实体仍然存在");
            for (TrashStore.SweepReceipt receipt : receipts) {
                if (receipt.sequence() <= applied) continue;
                if (receipt.sequence() != applied + 1 || !same(item.getItemStack(), ItemStack.deserializeBytes(receipt.before())))
                    throw new IllegalStateException("来源掉落物与已提交凭据不符");
                ItemStack remainder = receipt.remainder() == null ? null : ItemStack.deserializeBytes(receipt.remainder());
                applied(item, remainder, receipt.sequence());
                applied = receipt.sequence();
                if (remainder == null) return false;
            }
            // Vanilla may have captured the old stack before this event; cancel once after changing it.
            return applied == observedSequence && item.isValid();
    }

    private void quarantine(Item item, RuntimeException failed) {
            UUID id = item.getUniqueId();
            quarantined.add(id);
            item.setCanPlayerPickup(false);
            item.setCanMobPickup(false);
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "清扫来源实体已隔离：" + id + "；禁止拾取、合并或重扫，不得手动补发，请保留数据库与世界核查", failed);
    }

    @EventHandler
    public void removed(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
        verified.remove(event.getEntity().getUniqueId());
    }

    public void depositAsync(org.bukkit.Chunk chunk, java.util.List<TrashStore.SweepDeposit> deposits,
                             java.util.function.BiConsumer<Integer, Boolean> completed) {
        var delivered = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.BiConsumer<Integer, Boolean> finish = (accepted, failed) -> {
            if (delivered.compareAndSet(false, true)) completed.accept(accepted, failed);
        };
        // Entity effects stay on this region; the database worker receives copies and result callbacks only.
        var effects = new java.util.ArrayList<Runnable>();
        var reserved = new java.util.ArrayList<TrashStore.SweepDeposit>();
        try {
            for (var deposit : deposits) {
                if (!reserve(deposit.entity())) continue;
                reserved.add(new TrashStore.SweepDeposit(deposit.entity(), deposit.appliedSequence(), deposit.stack(),
                        (remainder, sequence) -> effects.add(() -> deposit.applyRemainder().accept(remainder, sequence))));
            }
            if (reserved.isEmpty()) { finish.accept(0, false); return; }
            plugin.databaseTasks().submit(() -> plugin.trash().depositEntities(reserved)).whenComplete((count, failed) -> {
                try {
                    if (!plugin.scheduler().onChunk(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
                        boolean invalidResult = false;
                        try {
                            if (failed != null) throw new IllegalStateException("异步清扫提交未确认", failed);
                            RuntimeException effectFailure = null;
                            for (Runnable effect : effects) try { effect.run(); }
                            catch (RuntimeException invalid) { if (effectFailure == null) effectFailure = invalid; else effectFailure.addSuppressed(invalid); }
                            if (effectFailure != null) throw effectFailure;
                            reserved.forEach(d -> release(d.entity()));
                        } catch (RuntimeException invalid) {
                            invalidResult = true;
                            reserved.forEach(d -> quarantined.add(d.entity()));
                            plugin.getLogger().log(java.util.logging.Level.SEVERE, "清扫来源保持隔离；禁止补发", invalid);
                        }
                        finish.accept(invalidResult ? 0 : count, invalidResult);
                    })) {
                        reserved.forEach(d -> quarantined.add(d.entity()));
                        plugin.getLogger().severe("清扫结果无法交付区域线程，来源保持隔离；禁止补发");
                        finish.accept(0, true);
                    }
                } catch (RuntimeException refused) {
                    reserved.forEach(d -> quarantined.add(d.entity()));
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "清扫结果交付失败，来源保持隔离；禁止补发", refused);
                    finish.accept(0, true);
                }
            });
        } catch (RuntimeException refused) {
            reserved.forEach(d -> quarantined.add(d.entity()));
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "清扫提交失败，来源保持隔离；禁止补发", refused);
            finish.accept(0, true);
        }
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.isSimilar(b) && a.getAmount() == b.getAmount();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void load(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) if (entity instanceof Item item)
            plugin.scheduler().near(item, () -> {
                plugin.playerTransfers().loadedDrop(item);
                reconcile(item);
            });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (!reconcile(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void hopper(InventoryPickupItemEvent event) {
        if (!reconcile(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void merge(ItemMergeEvent event) {
        if (!reconcile(event.getEntity()) || !reconcile(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && !reconcile(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void despawn(ItemDespawnEvent event) {
        if (!reconcile(event.getEntity())) event.setCancelled(true);
    }
}
