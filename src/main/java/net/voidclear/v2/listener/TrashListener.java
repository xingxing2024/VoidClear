package net.voidclear.v2.listener;

import net.voidclear.VoidClearPlugin;
import net.voidclear.v2.store.TrashStore;
import net.voidclear.v2.store.DeliveryFailureLog;
import net.voidclear.v2.store.PlayerAuditLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/** Configurable vanilla-like paged chest based on the supplied menus/bin.yml. */
public final class TrashListener implements Listener {
    private final VoidClearPlugin plugin;
    private volatile TrashMenuConfig config;
    // Each player owns their Bukkit inventory; only serialized store data is shared across Folia regions.
    private final Map<UUID, Menu> viewers = new ConcurrentHashMap<>();
    private final Map<Integer, PageContents> pageContents = new HashMap<>();
    private record PageContents(List<TrashStore.Entry> entries, List<byte[]> slots) {}
    private Object menuLock() { return plugin.trash().mysql() ? pageContents : plugin.trash(); }

    public TrashListener(VoidClearPlugin plugin) {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "menus/bin.yml");
        if (!file.isFile()) plugin.saveResource("menus/bin.yml", false);
        config = TrashMenuConfig.load(file);
    }

    private record SearchView(TrashFilter filter, long revision, List<TrashStore.Entry> matches) {
        List<TrashStore.Entry> page(int page, int size) {
            int start=(int)Math.min((long)page*size,matches.size());
            return List.copyOf(matches.subList(start,Math.min(start+size,matches.size())));
        }
    }
    private final Map<UUID, UUID> searches=new ConcurrentHashMap<>();
    private final Map<UUID,UUID> requestedSearches=new ConcurrentHashMap<>();
    private final Map<UUID,UUID> openings=new ConcurrentHashMap<>();

    public void open(Player player, TrashFilter filter) {
        if(filter==null) { open(player); return; }
        UUID request=UUID.randomUUID();
        requestedSearches.put(player.getUniqueId(),request);
        plugin.scheduler().player(player,new Runnable() {
            int attempts;
            @Override public void run() {
                if(!request.equals(requestedSearches.get(player.getUniqueId()))) return;
                if(!player.isOnline() || !plugin.scheduler().running()) {requestedSearches.remove(player.getUniqueId(),request);return;}
                if(plugin.playerTransfers().pending(player)) {
                    if(attempts++<100) {
                        if(attempts==1) player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(
                                plugin.settings().msg("trash.waiting","&7正在确认上一笔操作，请稍候…")));
                        plugin.scheduler().playerNextTick(player,this);return;
                    }
                    requestedSearches.remove(player.getUniqueId(),request);
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&e上一笔操作仍在确认，请稍后重试搜索。"));return;
                }
                requestedSearches.remove(player.getUniqueId(),request);
                beginSearch(player,filter,0);
            }
        });
    }

    /** At most100 payloads decoded per tick; a newer query, menu change or disconnect cancels this job. */
    private void beginSearch(Player player, TrashFilter filter, int requestedPage) {
        if (!player.isOnline() || plugin.playerTransfers().pending(player)) return;
        if(!empty(player.getItemOnCursor())) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&e请先放回光标上的物品，再使用分类或搜索。"));
            return;
        }
        UUID request=UUID.randomUUID(); searches.put(player.getUniqueId(),request);
        var snapshot=plugin.trash().snapshot();
        Inventory original=player.getOpenInventory().getTopInventory();
        TrashMenuConfig layout=config;
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(
                plugin.settings().msg("trash.searching","&7正在查找物品…")));
        new Runnable() {
            int offset;
            final List<TrashStore.Entry> matches=new ArrayList<>();
            @Override public void run() {
                if(!request.equals(searches.get(player.getUniqueId()))) return;
                if(!player.isOnline() || !plugin.scheduler().running() || config!=layout
                        || player.getOpenInventory().getTopInventory()!=original) {
                    searches.remove(player.getUniqueId(),request); return;
                }
                try {
                    int until=Math.min(offset+100,snapshot.entries().size());
                    for(;offset<until;offset++) {
                        var entry=snapshot.entries().get(offset);
                        if(filter.matches(TrashFilter.describe(ItemStack.deserializeBytes(entry.bytes())))) matches.add(entry);
                    }
                    if(offset<snapshot.entries().size()) {plugin.scheduler().playerNextTick(player,this);return;}
                    searches.remove(player.getUniqueId(),request);
                    if(plugin.playerTransfers().pending(player) || !empty(player.getItemOnCursor())) return;
                    int page=Math.min(requestedPage,Math.max(0,(matches.size()-1)/layout.pageSize()));
                    openPage(player,page,new SearchView(filter,snapshot.revision(),List.copyOf(matches)));
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                            "&7筛选：&f"+filter.label()+" &8· &f"+matches.size()+" &7条记录；点击领取到背包，存入请用 /vc trash。"));
                } catch(RuntimeException failed) {
                    searches.remove(player.getUniqueId(),request);
                    plugin.getLogger().log(java.util.logging.Level.WARNING,"垃圾箱搜索失败，库存未修改",failed);
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c搜索失败，请检查日志。"));
                }
            }
        }.run();
    }

    public void open(Player player) {
        plugin.scheduler().player(player, () -> {
            if (!player.isOnline()) return;
            searches.remove(player.getUniqueId());
            requestedSearches.remove(player.getUniqueId());
            openPage(player, 0);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opened(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) return;
        UUID player = event.getPlayer().getUniqueId();
        searches.remove(player);
        requestedSearches.remove(player);
        openings.remove(player);
    }

    @EventHandler
    public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
        searches.remove(event.getPlayer().getUniqueId());
        requestedSearches.remove(event.getPlayer().getUniqueId());
        openings.remove(event.getPlayer().getUniqueId());
    }

    /** Parse before publishing any reload; unchanged menus keep viewers and sparse slots intact. */
    public Runnable prepareReload() {
        TrashMenuConfig next = TrashMenuConfig.load(new File(plugin.getDataFolder(), "menus/bin.yml"));
        return () -> {
            synchronized (menuLock()) {
                if (config.sameAs(next)) return;
                invalidateOpenMenus();
                config = next;
            }
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        synchronized (menuLock()) {
            if (menu.invalidated) return;
            int raw = event.getRawSlot();
            String action = raw >= 0 && raw < 54 ? actionAt(menu, raw) : "none";
            if (List.of("category","reset","search").contains(action)) {
                plugin.scheduler().playerNextTick(player,()-> {
                    if(menu.invalidated || player.getOpenInventory().getTopInventory()!=menu.inventory
                            || plugin.playerTransfers().pending(player)) return;
                    if(action.equals("reset")) open(player);
                    else if(action.equals("search")) player.sendMessage(
                            net.kyori.adventure.text.Component.text("点击这里输入搜索词：/vc trash search <名称或材质>")
                                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/vc trash search ")));
                    else {
                        var categories=TrashFilter.Category.values();
                        int current=menu.search==null?0:menu.search.filter().category().ordinal();
                        beginSearch(player,new TrashFilter(categories[(current+1)%categories.length],
                                menu.search==null?"":menu.search.filter().query()),0);
                    }
                });
                return;
            }
            if (!action.equals("none")) {
                int page = switch (action) {
                    case "previous", "prev" -> Math.max(0, menu.page - 1);
                    case "next" -> hasNext(menu) ? menu.page + 1 : menu.page;
                    case "first" -> 0;
                    case "last" -> lastPage(menu);
                    default -> menu.page;
                };
                plugin.scheduler().playerNextTick(player, () -> {
                    if (!menu.invalidated && player.getOpenInventory().getTopInventory() == menu.inventory) {
                        openPage(player, page, menu.search);
                    }
                });
                return;
            }
            if (raw >= 0 && raw < 54 && !config.itemSlots().contains(raw)) return;
            if (!current(menu)) { refresh(player,menu); return; }
            Transfer transfer = new Transfer(player, menu);
            if(menu.search!=null) {
                // Search results are withdrawal-only. No cursor, swap, drag, drop or creative cloning.
                if(raw<0 || raw>=54 || !empty(transfer.cursor)
                        || !(event.getClick()==ClickType.LEFT || event.getClick()==ClickType.RIGHT
                        || event.getClick()==ClickType.SHIFT_LEFT || event.getClick()==ClickType.SHIFT_RIGHT)) return;
                ItemStack source=transfer.get(raw);
                if(empty(source)) return;
                List<Integer> destinations=new ArrayList<>();
                for(int i=89;i>=54;i--) destinations.add(i);
                transfer.insert(source,destinations);
            } else transfer.click(event);
            commit(player, menu, transfer);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu menu
                && event.getWhoClicked() instanceof Player player) {
            event.setCancelled(true);
            if (menu.search!=null || menu.invalidated || event.getRawSlots().stream().anyMatch(slot -> slot < 54
                    && !config.itemSlots().contains(slot))) {
                return;
            }
            synchronized (menuLock()) {
                if (menu.invalidated) return;
                if (!current(menu)) { render(menu); return; }
                Transfer transfer = new Transfer(player, menu);
                // Vanilla temporarily changes the carried stack before firing this event.
                transfer.beforeCursor = copy(event.getOldCursor());
                event.getNewItems().forEach((slot, stack) -> transfer.set(slot, copy(stack)));
                transfer.cursor = copy(event.getCursor());
                // Receipt is deferred until vanilla finalizes the carried cursor.
                transfer.drag = true;
                if (commit(player, menu, transfer) && !plugin.trash().mysql()) event.setCancelled(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            viewers.remove(event.getPlayer().getUniqueId(), menu);
            searches.remove(event.getPlayer().getUniqueId());
            if(event.getReason()!=InventoryCloseEvent.Reason.OPEN_NEW) {
                requestedSearches.remove(event.getPlayer().getUniqueId());
                openings.remove(event.getPlayer().getUniqueId());
            }
            if (event.getPlayer() instanceof Player player && !empty(player.getItemOnCursor())) {
                if (plugin.trash().mysql() && event.getReason() == InventoryCloseEvent.Reason.DEATH) {
                    if (plugin.playerTransfers().pending(player)) quarantineDeathCursor(player, menu);
                    else deathCursorAsync(player, menu);
                    return;
                }
                if (plugin.playerTransfers().pending(player)) {
                    return;
                }
                if (plugin.trash().mysql()) {
                    plugin.playerTransfers().applying(player);
                    plugin.scheduler().playerNextTick(player, () -> {
                        try {
                            Transfer returned = new Transfer(player, menu);
                            if (!empty(returned.cursor)) {
                                var destinations = new ArrayList<Integer>();
                                for (int raw = 54; raw < 90; raw++) destinations.add(raw);
                                returned.insert(returned.cursor, destinations);
                                if (!empty(returned.cursor)) returned.drops.add(returned.cursor.clone());
                                returned.cursor = null;
                            }
                            commitAsync(player, menu, returned, null, true);
                        } catch (RuntimeException failed) { plugin.playerTransfers().isolate(player, failed); }
                    });
                    return;
                }
                try {
                    Transfer transfer = new Transfer(player, menu);
                    // Death has already captured the inventory drops; returning the cursor to storage now loses it.
                    if (event.getReason() != InventoryCloseEvent.Reason.DEATH) {
                        var destinations = new ArrayList<Integer>();
                        for (int raw = 54; raw < 90; raw++) destinations.add(raw);
                        transfer.insert(transfer.cursor, destinations);
                    }
                    if (!empty(transfer.cursor)) transfer.drops.add(transfer.cursor.clone());
                    transfer.cursor = null;
                    transfer.operation = UUID.randomUUID();
                    plugin.trash().preparePlayerOnly(player.getUniqueId(), plugin.playerTransfers().transactionSession(player),
                            transfer.operation, plugin.playerTransfers().previousOperation(player), transfer.evidence());
                    plugin.playerTransfers().applying(player);
                    plugin.playerTransfers().boundary("prepared");
                    if (!deliver(player, menu, transfer, true)) throw new IllegalStateException("关闭菜单交付失败");
                    acknowledge(player, menu, transfer);
                } catch (RuntimeException failed) { plugin.playerTransfers().isolate(player, failed); }
            }
        }
    }

    private boolean current(Menu menu) {
        return !menu.invalidated && (menu.search==null
                ? plugin.trash().isCurrentPage(menu.page,config.pageSize(),menu.entries)
                : plugin.trash().isCurrentSelection(menu.entries));
    }

    private void quarantineDeathCursor(Player player, Menu menu) {
        try {
            ItemStack cursor = player.getItemOnCursor().clone();
            var evidence = new java.util.Properties();
            evidence.setProperty("player", player.getUniqueId().toString());
            evidence.setProperty("reason", "Death during asynchronous transaction; actual cursor preserved, NEVER replay");
            snapshot(evidence, "observed.cursor", new ItemStack[]{cursor});
            DeliveryFailureLog.write(plugin.getDataFolder().toPath().resolve("delivery-failures"), evidence);
            UUID orphan = UUID.randomUUID();
            player.getWorld().dropItem(player.getLocation(), cursor, item -> {
                plugin.playerTransfers().markDrop(item, orphan);
                item.setCanPlayerPickup(false);
                item.setCanMobPickup(false);
            });
            player.setItemOnCursor(null);
            menu.invalidated = true;
            plugin.playerTransfers().isolate(player, new IllegalStateException("异步等待期间死亡，实际光标已保存为隔离来源；禁止补发"));
        } catch (java.io.IOException | RuntimeException failed) {
            plugin.playerTransfers().isolate(player, failed);
        }
    }

    private void deathCursorAsync(Player player, Menu menu) {
        // Preserve the actual cursor now: vanilla has already collected the death inventory.
        UUID id = player.getUniqueId(), session = plugin.playerTransfers().transactionSession(player);
        UUID previous = plugin.playerTransfers().previousOperation(player), operation = UUID.randomUUID();
        ItemStack cursor = player.getItemOnCursor().clone();
        plugin.playerTransfers().applying(player);
        try {
            var item = player.getWorld().dropItem(player.getLocation(), cursor,
                    source -> plugin.playerTransfers().markDrop(source, operation));
            player.setItemOnCursor(null);
            UUID entity = item.getUniqueId();
            byte[] bytes = cursor.serializeAsBytes();
            var chunk = new TrashStore.DropChunk(item.getWorld().getUID(), item.getLocation().getBlockX() >> 4,
                    item.getLocation().getBlockZ() >> 4);
            plugin.scheduler().playerNextTick(player, () -> {
                Transfer transfer = new Transfer(player, menu);
                transfer.operation = operation;
                transfer.drops.add(cursor);
                byte[] evidence = transfer.evidence();
                var origin = player.getLocation();
                plugin.databaseTasks().submit(() -> {
                    plugin.trash().preparePlayerOnly(id, session, operation, previous, evidence);
                    plugin.trash().registerPlayerDrop(entity, operation, 0, bytes, chunk);
                    return true;
                }).whenComplete((accepted, failed) -> plugin.playerTransfers().onPlayer(player, session, origin, () -> {
                    if (failed != null) { plugin.playerTransfers().isolate(player, failed); return; }
                    if (!plugin.playerTransfers().live(player, session) || !transfer.matchesBefore(player)) {
                        plugin.playerTransfers().isolate(player, new IllegalStateException("死亡来源登记期间背包已变化，保留来源证据"));
                        return;
                    }
                    acknowledge(player, menu, transfer);
                }));
            });
        } catch (RuntimeException failed) { plugin.playerTransfers().isolate(player, failed); }
    }

    private boolean commit(Player player, Menu menu, Transfer transfer) {
        List<ItemStack> slots = config.itemSlots().stream().map(slot -> transfer.top[slot]).toList();
        boolean topChanged = false;
        for (int i = 0; i < slots.size(); i++) {
            if (!sameStack(menu.inventory.getItem(config.itemSlots().get(i)), slots.get(i))) {
                topChanged = true;
                break;
            }
        }
        if (!topChanged && !transfer.playerChanged()) return true;
        if (plugin.trash().mysql()) {
            commitAsync(player, menu, transfer, topChanged ? slots : null, false);
            return false; // The cancelled drag is applied explicitly after the asynchronous reservation.
        }
        boolean accepted;
        try {
            transfer.operation = UUID.randomUUID();
            if (topChanged) accepted = menu.search==null
                    ? plugin.trash().replacePlayerPage(player.getUniqueId(), plugin.playerTransfers().transactionSession(player),
                        transfer.operation,plugin.playerTransfers().previousOperation(player),transfer.evidence(),menu.page,config.pageSize(),menu.entries,slots)
                    : plugin.trash().withdrawPlayerSelection(player.getUniqueId(),plugin.playerTransfers().transactionSession(player),
                        transfer.operation,plugin.playerTransfers().previousOperation(player),transfer.evidence(),menu.entries,slots);
            else {
                plugin.trash().preparePlayerOnly(player.getUniqueId(), plugin.playerTransfers().transactionSession(player), transfer.operation,
                        plugin.playerTransfers().previousOperation(player), transfer.evidence());
                accepted = true;
            }
        }
        catch (IllegalStateException failed) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "垃圾箱交易未确认，未发放物品", failed);
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c存储暂不可用，本次操作未确认，请联系管理员。"));
            menu.invalidated = true;
            plugin.playerTransfers().isolate(player, failed);
            return false;
        }
        if (!accepted) {
            refresh(player,menu);
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c页面已变化或容量不足，本次操作未执行，请重试。"));
            return false;
        }
        plugin.playerTransfers().boundary("prepared");
        plugin.playerTransfers().applying(player);
        if (!deliver(player, menu, transfer, true)) {
            plugin.playerTransfers().isolate(player, new IllegalStateException("物品交付部分失败"));
            return false;
        }
        if (transfer.drag) plugin.scheduler().playerNextTick(player, () -> acknowledge(player, menu, transfer));
        else if (!acknowledge(player, menu, transfer)) return false;
        if(menu.search==null) publishTransfer(player, menu, transfer, slots, plugin.trash().page(menu.page, config.pageSize()));
        else menu.invalidated=true;
        return true;
    }

    private void commitAsync(Player player, Menu menu, Transfer transfer, List<ItemStack> slots, boolean closing) {
        UUID id = player.getUniqueId();
        UUID session = plugin.playerTransfers().transactionSession(player);
        UUID previous = plugin.playerTransfers().previousOperation(player);
        transfer.operation = UUID.randomUUID();
        byte[] evidence = transfer.evidence();
        var displayed = List.copyOf(menu.entries);
        int pageSize = menu.config.pageSize();
        var origin = player.getLocation();
        plugin.playerTransfers().applying(player);
        var acceptedEntries = new java.util.concurrent.atomic.AtomicReference<List<TrashStore.Entry>>();
        plugin.databaseTasks().submit(() -> {
            synchronized (plugin.trash()) {
            boolean accepted;
            if (slots == null) {
                plugin.trash().preparePlayerOnly(id, session, transfer.operation, previous, evidence);
                accepted = true;
            } else accepted = menu.search==null
                    ? plugin.trash().replacePlayerPage(id,session,transfer.operation,previous,evidence,menu.page,pageSize,displayed,slots)
                    : plugin.trash().withdrawPlayerSelection(id,session,transfer.operation,previous,evidence,displayed,slots);
            if (accepted) plugin.playerTransfers().boundary("prepared");
            if (accepted) acceptedEntries.set(plugin.trash().page(menu.page, pageSize));
            return accepted;
            }
        }).whenComplete((accepted, failed) -> plugin.playerTransfers().onPlayer(player, session, origin, () -> {
            synchronized (menuLock()) {
                if (failed != null) { menu.invalidated = true; plugin.playerTransfers().isolate(player, failed); return; }
                if (!accepted) {
                    plugin.playerTransfers().finished(player);
                    if (!menu.invalidated && !closing) refresh(player,menu);
                    return;
                }
                if (!plugin.playerTransfers().live(player, session) || player.isDead()
                        || !transfer.matchesBefore(player) || !closing && (menu.invalidated
                        || player.getOpenInventory().getTopInventory() != menu.inventory)) {
                    menu.invalidated = true;
                    plugin.playerTransfers().isolate(player, new IllegalStateException("等待存储时玩家、菜单或背包已变化，保留交易证据"));
                    return;
                }
                transfer.drag = false;
                if (!deliver(player, menu, transfer, true)) {
                    plugin.playerTransfers().isolate(player, new IllegalStateException("异步交易交付失败"));
                    return;
                }
                if (!acknowledge(player, menu, transfer)) return;
                if (!closing && !menu.invalidated) {
                    if(menu.search!=null) { menu.invalidated=true; return; }
                    if (plugin.trash().isCurrentPage(menu.page, pageSize, acceptedEntries.get()))
                        publishTransfer(player, menu, transfer, menu.config.itemSlots().stream().map(slot -> transfer.top[slot]).toList(), acceptedEntries.get());
                    else render(menu);
                }
            }
        }));
    }

    private void publishTransfer(Player player, Menu menu, Transfer transfer, List<ItemStack> slots, List<TrashStore.Entry> entries) {
        config.itemSlots().forEach(slot -> menu.inventory.setItem(slot, transfer.top[slot]));
        menu.entries = entries;
        // Removing from a full page pulls entries forward from the following page.
        if (menu.entries.size() != slots.stream().filter(stack -> !empty(stack)).count()) { render(menu); entries = menu.entries; }
        // Keep the same holes/slot positions in every viewer, not a compacted per-player snapshot.
        List<TrashStore.Entry> displayed = entries;
        List<byte[]> contents = config.itemSlots().stream().map(menu.inventory::getItem)
                .map(stack -> empty(stack) ? null : stack.serializeAsBytes()).toList();
        pageContents.put(menu.page, new PageContents(displayed, contents));
        renderButtons(menu);
        viewers.forEach((id, other) -> {
            if (other == menu) return;
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) return;
            plugin.scheduler().player(viewer, () -> {
                synchronized (menuLock()) {
                    if (other.invalidated) return;
                    if (other.page == menu.page && plugin.trash().isCurrentPage(menu.page, config.pageSize(), displayed)) {
                        refresh(viewer,other);
                    } else if (!current(other)) refresh(viewer,other);
                }
            });
        });
    }

    private boolean acknowledge(Player player, Menu menu, Transfer transfer) {
        try {
            if (!sameStacks(player.getInventory().getStorageContents(), transfer.storage)
                    || !sameStack(player.getInventory().getItemInOffHand(), transfer.offhand)
                    || !sameStack(player.getItemOnCursor(), transfer.cursor))
                throw new IllegalStateException("交付后物品与计划不符");
            String state = !transfer.drops.isEmpty() ? "DROPS" : empty(transfer.cursor) ? "DONE" : "CURSOR";
            List<PlayerAuditLog.Movement> movements;
            try { movements = PlayerAuditLog.movements(transfer.beforeTop, transfer.top, menu.config.itemSlots()); }
            catch (RuntimeException failed) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "存取审计快照失败；交易保护继续执行", failed);
                movements = List.of();
            }
            var changes = movements;
            UUID actor = player.getUniqueId();
            String actorName = player.getName();
            plugin.playerTransfers().save(player, transfer.operation, state, () -> {
                plugin.recordAudit(actor, actorName, transfer.operation, changes);
                if(menu.search!=null) plugin.scheduler().playerNextTick(player,()-> {
                    if(player.isOnline() && player.getOpenInventory().getTopInventory()==menu.inventory)
                        beginSearch(player,menu.search.filter(),menu.page);
                });
                if (plugin.trash().mysql() && !empty(transfer.cursor) && !player.isDead()
                        && player.getOpenInventory().getTopInventory() != menu.inventory) {
                    // Vanilla returned the actual cursor during close while its confirmation was in flight.
                    Transfer returned = new Transfer(player, menu);
                    if (!empty(returned.cursor)) {
                        var destinations = new ArrayList<Integer>();
                        for (int raw = 54; raw < 90; raw++) destinations.add(raw);
                        returned.insert(returned.cursor, destinations);
                        if (!empty(returned.cursor)) returned.drops.add(returned.cursor.clone());
                        returned.cursor = null;
                    }
                    commitAsync(player, menu, returned, null, true);
                }
            });
            return true;
        } catch (RuntimeException failed) {
            menu.invalidated = true;
            plugin.playerTransfers().isolate(player, failed);
            return false;
        }
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        if (empty(a) && empty(b)) return true;
        return !empty(a) && !empty(b) && a.isSimilar(b) && a.getAmount() == b.getAmount();
    }

    private static boolean sameStacks(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!sameStack(a[i], b[i])) return false;
        return true;
    }

    private void applyPlayerTransfer(Player player, Transfer transfer) {
        transfer.deliveryStage = "storage";
        player.getInventory().setStorageContents(transfer.storage);
        if (transfer.operation != null) plugin.playerTransfers().boundary("storage");
        transfer.deliveryStage = "offhand";
        player.getInventory().setItemInOffHand(transfer.offhand);
        if (transfer.operation != null) plugin.playerTransfers().boundary("offhand");
        transfer.deliveryStage = "cursor";
        player.setItemOnCursor(transfer.cursor);
        if (transfer.operation != null) plugin.playerTransfers().boundary("cursor");
        for (int i = 0; i < transfer.drops.size(); i++) {
            transfer.deliveryStage = "drop[" + i + "]";
            if (transfer.operation == null) player.getWorld().dropItem(player.getLocation(), transfer.drops.get(i));
            else {
                final int ordinal = i;
                player.getWorld().dropItem(player.getLocation(), transfer.drops.get(i),
                        item -> plugin.playerTransfers().registerDrop(item, transfer.operation, ordinal));
            }
        }
        transfer.deliveryStage = "complete";
    }

    private boolean deliver(Player player, Menu menu, Transfer transfer, boolean stored) {
        try {
            applyPlayerTransfer(player, transfer);
            return true;
        } catch (RuntimeException failed) {
            menu.invalidated = true;
            pageContents.remove(menu.page);
            try {
                java.util.Properties evidence = new java.util.Properties();
                evidence.setProperty("player", player.getUniqueId().toString());
                evidence.setProperty("page", String.valueOf(menu.page));
                evidence.setProperty("storageAccepted", String.valueOf(stored));
                evidence.setProperty("failedStage", transfer.deliveryStage);
                evidence.setProperty("failure", failed.toString());
                evidence.setProperty("warning", "Snapshot is evidence, not an entitlement. Effects before/within failedStage may already exist. NEVER auto-replay.");
                snapshot(evidence, "before.storage", transfer.beforeStorage);
                snapshot(evidence, "before.top", transfer.beforeTop);
                snapshot(evidence, "before.cursor", new ItemStack[]{transfer.beforeCursor});
                snapshot(evidence, "before.offhand", new ItemStack[]{transfer.beforeOffhand});
                snapshot(evidence, "planned.storage", transfer.storage);
                snapshot(evidence, "planned.top", transfer.top);
                snapshot(evidence, "planned.cursor", new ItemStack[]{transfer.cursor});
                snapshot(evidence, "planned.offhand", new ItemStack[]{transfer.offhand});
                snapshot(evidence, "planned.drops", transfer.drops.toArray(ItemStack[]::new));
                try {
                    snapshot(evidence, "observed.storage", player.getInventory().getStorageContents());
                    snapshot(evidence, "observed.cursor", new ItemStack[]{player.getItemOnCursor()});
                    snapshot(evidence, "observed.offhand", new ItemStack[]{player.getInventory().getItemInOffHand()});
                } catch (RuntimeException unavailable) {
                    evidence.setProperty("observed.error", unavailable.toString());
                }
                for (int i = 0; i < menu.entries.size(); i++) {
                    TrashStore.Entry entry = menu.entries.get(i);
                    evidence.setProperty("displayed." + i + ".id", String.valueOf(entry.id()));
                    evidence.setProperty("displayed." + i + ".data", java.util.Base64.getEncoder().encodeToString(entry.bytes()));
                }
                var file = DeliveryFailureLog.write(plugin.getDataFolder().toPath().resolve("delivery-failures"), evidence);
                plugin.getLogger().severe("垃圾箱交付核查快照已保存：" + file);
            } catch (java.io.IOException | RuntimeException logFailed) {
                failed.addSuppressed(logFailed);
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "交付失败快照未能保存，请保留服务器日志并立即人工核查", logFailed);
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "垃圾箱交付部分失败：player=" + player.getUniqueId()
                    + " page=" + menu.page + " storageAccepted=" + stored
                    + "；已禁止该菜单继续交易，不得自动重试、回滚或补发，请核查背包、光标、掉落物与存储", failed);
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c物品交付异常，菜单已锁定，请联系管理员核查，勿重复操作。"));
            return false;
        }
    }

    public void invalidateOpenMenus() {
        searches.clear();
        requestedSearches.clear();
        openings.clear();
        synchronized (menuLock()) {
            viewers.values().forEach(menu -> menu.invalidated = true);
            viewers.clear();
            pageContents.clear();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try { plugin.scheduler().player(player, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu
                        && menu.invalidated) player.closeInventory();
            }); } catch (RuntimeException refused) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "菜单已失效，但玩家线程拒绝关闭任务：" + player.getUniqueId(), refused);
            }
        }
    }

    public void shutdown() {
        invalidateOpenMenus();
    }

    private void openPage(Player player,int page) { openPage(player,page,null); }

    private void openPage(Player player, int page, SearchView search) {
        if (plugin.trash().mysql()) {
            if (plugin.playerTransfers().pending(player)) {
                player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        plugin.settings().msg("trash.waiting", "&7正在确认上一笔操作，请稍候…")));
                return;
            }
            UUID session = plugin.playerTransfers().transactionSession(player);
            UUID previous = plugin.playerTransfers().previousOperation(player);
            var origin = player.getLocation();
            Inventory previousInventory=player.getOpenInventory().getTopInventory();
            UUID opening=UUID.randomUUID();openings.put(player.getUniqueId(),opening);
            plugin.playerTransfers().applying(player);
            plugin.databaseTasks().submit(() -> plugin.trash().sessionCheckpoint(player.getUniqueId(),
                    plugin.serverOwnership().id(), session, "玩家会话已变化")).whenComplete((checkpoint, failed) ->
                    plugin.playerTransfers().onPlayer(player, session, origin, () -> {
                        if (failed != null || !plugin.playerTransfers().live(player, session)
                                || !java.util.Objects.equals(previous, plugin.playerTransfers().previousOperation(player))
                                || checkpoint != null && (checkpoint.state().equals("QUARANTINED")
                                || !checkpoint.state().equals("DONE") && !checkpoint.session().equals(session))
                                || !java.util.Objects.equals(previous, checkpoint == null ? null : checkpoint.operation())) {
                            plugin.playerTransfers().isolate(player, failed == null ? new IllegalStateException("打开菜单时凭据已变化") : failed);
                            return;
                        }
                        plugin.playerTransfers().finished(player);
                        if(!openings.remove(player.getUniqueId(),opening)
                                || player.getOpenInventory().getTopInventory()!=previousInventory) return;
                        showPage(player, page, search);
                    }));
            return;
        }
        synchronized (menuLock()) {
            try { plugin.playerTransfers().session(player); }
            catch (IllegalStateException failed) { plugin.playerTransfers().isolate(player, failed); return; }
            showPage(player, page, search);
        }
    }

    private void showPage(Player player, int page, SearchView search) {
        synchronized (menuLock()) {
            Menu menu = new Menu(config,page,search,titleValues(page,search));
            render(menu);
            player.openInventory(menu.inventory);
            viewers.put(player.getUniqueId(), menu);
        }
    }

    private void refresh(Player player,Menu menu) {
        if(player.getOpenInventory().getTopInventory()!=menu.inventory || viewers.get(player.getUniqueId())!=menu) return;
        if(menu.search==null) render(menu);
        else if(!searches.containsKey(player.getUniqueId()) && !plugin.playerTransfers().pending(player))
            beginSearch(player,menu.search.filter(),menu.page);
    }

    /** Called by the heartbeat, including after ground-drop collection or database refresh. */
    public void refreshOpenMenus() {
        viewers.forEach((id, menu) -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            plugin.scheduler().player(player, () -> {
                synchronized (menuLock()) {
                    if (!menu.invalidated && player.getOpenInventory().getTopInventory()==menu.inventory
                            && viewers.get(id)==menu) {
                        if (!current(menu) || menu.search!=null && menu.search.revision()!=plugin.trash().revision()) refresh(player,menu);
                        else renderButtons(menu);
                    }
                }
            });
        });
    }

    private static boolean empty(ItemStack stack) { return stack == null || stack.isEmpty(); }
    private static ItemStack copy(ItemStack stack) { return empty(stack) ? null : stack.clone(); }

    private static void snapshot(java.util.Properties evidence, String name, ItemStack[] stacks) {
        evidence.setProperty(name + ".length", String.valueOf(stacks.length));
        for (int i = 0; i < stacks.length; i++) {
            evidence.setProperty(name + "." + i, empty(stacks[i]) ? "empty"
                    : java.util.Base64.getEncoder().encodeToString(stacks[i].serializeAsBytes()));
        }
    }

    /** Work on copies, then publish only after the store accepts the entire transfer. */
    static int storageSlot(int raw) {
        if (raw < 54 || raw >= 90) throw new IllegalArgumentException("Invalid chest storage slot");
        return raw < 81 ? raw - 45 : raw - 81;
    }

    private final class Transfer {
        final ItemStack[] top;
        final ItemStack[] storage;
        final List<ItemStack> drops = new ArrayList<>();
        ItemStack cursor;
        ItemStack offhand;
        String deliveryStage = "not-started";
        UUID operation;
        boolean drag;
        final ItemStack[] beforeStorage;
        final ItemStack[] beforeTop;
        ItemStack beforeCursor;
        final ItemStack beforeOffhand;

        Transfer(Player player, Menu menu) {
            top = java.util.Arrays.stream(menu.inventory.getContents()).map(TrashListener::copy).toArray(ItemStack[]::new);
            storage = java.util.Arrays.stream(player.getInventory().getStorageContents()).map(TrashListener::copy).toArray(ItemStack[]::new);
            cursor = copy(player.getItemOnCursor());
            offhand = copy(player.getInventory().getItemInOffHand());
            beforeStorage = java.util.Arrays.stream(storage).map(TrashListener::copy).toArray(ItemStack[]::new);
            beforeTop = java.util.Arrays.stream(top).map(TrashListener::copy).toArray(ItemStack[]::new);
            beforeCursor = copy(cursor);
            beforeOffhand = copy(offhand);
        }

        boolean playerChanged() {
            return !sameStacks(beforeStorage, storage) || !sameStack(beforeOffhand, offhand)
                    || !sameStack(beforeCursor, cursor) || !drops.isEmpty();
        }

        boolean matchesBefore(Player player) {
            return sameStacks(player.getInventory().getStorageContents(), beforeStorage)
                    && sameStack(player.getInventory().getItemInOffHand(), beforeOffhand)
                    && sameStack(player.getItemOnCursor(), beforeCursor);
        }

        byte[] evidence() {
            var evidence = new java.util.Properties();
            evidence.setProperty("warning", "Evidence only; NEVER replay, compensate or restore old pages");
            snapshot(evidence, "before.storage", beforeStorage); snapshot(evidence, "before.top", beforeTop);
            snapshot(evidence, "before.cursor", new ItemStack[]{beforeCursor});
            snapshot(evidence, "before.offhand", new ItemStack[]{beforeOffhand});
            snapshot(evidence, "planned.storage", storage); snapshot(evidence, "planned.top", top);
            snapshot(evidence, "planned.cursor", new ItemStack[]{cursor});
            snapshot(evidence, "planned.offhand", new ItemStack[]{offhand});
            snapshot(evidence, "planned.drops", drops.toArray(ItemStack[]::new));
            try {
                var output = new java.io.ByteArrayOutputStream(); evidence.store(output, "VoidClear player transaction");
                return output.toByteArray();
            } catch (java.io.IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
        }

        ItemStack get(int raw) { return raw < 54 ? top[raw] : storage[storageSlot(raw)]; }
        void set(int raw, ItemStack stack) {
            if (raw < 54) top[raw] = stack;
            else storage[storageSlot(raw)] = stack;
        }

        void click(InventoryClickEvent event) {
            int raw = event.getRawSlot();
            ItemStack slot = raw < 0 ? null : get(raw);
            switch (event.getAction()) {
                case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE -> {
                    if (empty(slot)) return;
                    int wanted = switch (event.getAction()) {
                        case PICKUP_HALF -> (slot.getAmount() + 1) / 2;
                        case PICKUP_ONE -> 1;
                        default -> slot.getAmount();
                    };
                    int room = empty(cursor) ? slot.getMaxStackSize() : cursor.getMaxStackSize() - cursor.getAmount();
                    int moved = Math.min(wanted, room);
                    if (moved < 1) return;
                    if (empty(cursor)) { cursor = slot.clone(); cursor.setAmount(moved); }
                    else cursor.setAmount(cursor.getAmount() + moved);
                    slot.setAmount(slot.getAmount() - moved);
                }
                case PLACE_ALL, PLACE_SOME, PLACE_ONE -> {
                    if (empty(cursor)) return;
                    int room = empty(slot) ? cursor.getMaxStackSize() : slot.getMaxStackSize() - slot.getAmount();
                    int moved = Math.min(room, event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ONE ? 1 : cursor.getAmount());
                    if (moved < 1) return;
                    if (empty(slot)) { slot = cursor.clone(); slot.setAmount(moved); set(raw, slot); }
                    else slot.setAmount(slot.getAmount() + moved);
                    cursor.setAmount(cursor.getAmount() - moved);
                }
                case SWAP_WITH_CURSOR -> { set(raw, cursor); cursor = slot; }
                case MOVE_TO_OTHER_INVENTORY -> {
                    if (empty(slot)) return;
                    List<Integer> destinations = new ArrayList<>();
                    if (raw < 54) {
                        for (int i = 89; i >= 54; i--) destinations.add(i);
                    } else destinations.addAll(config.itemSlots());
                    insert(slot, destinations);
                }
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                    if (event.getClick() == ClickType.SWAP_OFFHAND) { set(raw, offhand); offhand = slot; }
                    else if (event.getHotbarButton() >= 0) {
                        int hotbar = event.getHotbarButton();
                        set(raw, storage[hotbar]); storage[hotbar] = slot;
                    }
                }
                case COLLECT_TO_CURSOR -> {
                    if (empty(cursor)) return;
                    List<Integer> sources = new ArrayList<>(config.itemSlots());
                    for (int i = 54; i < 90; i++) sources.add(i);
                    for (int pass = 0; pass < 2; pass++) {
                        for (int source : sources) {
                            ItemStack found = get(source);
                            if (empty(found) || !cursor.isSimilar(found) || (pass == 0 && found.getAmount() == found.getMaxStackSize())) continue;
                            int moved = Math.min(found.getAmount(), cursor.getMaxStackSize() - cursor.getAmount());
                            cursor.setAmount(cursor.getAmount() + moved); found.setAmount(found.getAmount() - moved);
                        }
                    }
                }
                case DROP_ALL_SLOT, DROP_ONE_SLOT -> {
                    if (empty(slot)) return;
                    ItemStack dropped = slot.clone();
                    dropped.setAmount(event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT ? 1 : slot.getAmount());
                    slot.setAmount(slot.getAmount() - dropped.getAmount()); drops.add(dropped);
                }
                case DROP_ALL_CURSOR, DROP_ONE_CURSOR -> {
                    if (empty(cursor)) return;
                    ItemStack dropped = cursor.clone();
                    dropped.setAmount(event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ONE_CURSOR ? 1 : cursor.getAmount());
                    cursor.setAmount(cursor.getAmount() - dropped.getAmount()); drops.add(dropped);
                }
                case CLONE_STACK -> {
                    if (!empty(slot)) { cursor = slot.clone(); cursor.setAmount(slot.getMaxStackSize()); }
                }
                default -> { }
            }
        }

        void insert(ItemStack source, List<Integer> destinations) {
            for (int pass = 0; pass < 2; pass++) {
                for (int raw : destinations) {
                    if (empty(source)) return;
                    ItemStack target = get(raw);
                    if (pass == 0 ? empty(target) || !source.isSimilar(target) : !empty(target)) continue;
                    int room = empty(target) ? source.getMaxStackSize() : target.getMaxStackSize() - target.getAmount();
                    int moved = Math.min(room, source.getAmount());
                    if (moved < 1) continue;
                    if (empty(target)) { target = source.clone(); target.setAmount(moved); set(raw, target); }
                    else target.setAmount(target.getAmount() + moved);
                    source.setAmount(source.getAmount() - moved);
                }
            }
        }
    }

    private String actionAt(Menu menu, int slot) {
        if (slot < 0 || slot >= 54) return "none";
        TrashMenuConfig.Button button = config.button(config.layoutAt(slot));
        return button == null ? "none" : button.action().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean hasNext(Menu menu) {
        return (menu.page + 1) * config.pageSize() < (menu.search==null?plugin.trash().size():menu.search.matches().size());
    }

    private int lastPage(Menu menu) {
        return Math.max(0, ((menu.search==null?plugin.trash().size():menu.search.matches().size()) - 1) / config.pageSize());
    }

    private void render(Menu menu) {
        if (menu.invalidated) return;
        try {
        List<TrashStore.Entry> entries = menu.search==null?plugin.trash().page(menu.page, config.pageSize()):menu.search.page(menu.page,config.pageSize());
        PageContents cached = menu.search==null?pageContents.get(menu.page):null;
        if (cached != null && !TrashStore.samePage(entries, cached.entries())) {
            pageContents.remove(menu.page);
            cached = null;
        }
        List<byte[]> contents = cached == null ? entries.stream().map(TrashStore.Entry::bytes).toList() : cached.slots();
        ItemStack[] decoded = decodePage(contents, config.itemSlots());
        menu.inventory.setContents(decoded);
        menu.buttonValues = null;
        renderButtons(menu);
        menu.entries = entries;
        } catch (RuntimeException broken) {
            menu.invalidated = true;
            pageContents.remove(menu.page);
            throw new IllegalStateException("垃圾箱页面渲染失败，菜单已锁定，原存储未改动", broken);
        }
    }

    static ItemStack[] decodePage(List<byte[]> contents, List<Integer> slots) {
        ItemStack[] decoded = new ItemStack[54];
        for (int i = 0; i < contents.size(); i++) {
            byte[] data = contents.get(i);
            if (data != null) decoded[slots.get(i)] = ItemStack.deserializeBytes(data);
        }
        return decoded;
    }

    private void renderButtons(Menu menu) {
        Map<String, String> values = titleValues(menu.page,menu.search);
        if (values.equals(menu.buttonValues)) return;
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                char key = config.layoutAt(row * 9 + col);
                TrashMenuConfig.Button button = config.button(key);
                if (button == null) continue;
                boolean disabled = (button.action().equalsIgnoreCase("previous") && menu.page == 0)
                        || (button.action().equalsIgnoreCase("next") && !hasNext(menu));
                menu.inventory.setItem(row * 9 + col, config.icon(button, values, disabled));
            }
        }
        menu.buttonValues = values;
    }

    private Map<String, String> titleValues(int page,SearchView search) {
        int count=search==null?plugin.trash().size():search.matches().size();
        int max = Math.max(1, (count + config.pageSize() - 1) / config.pageSize());
        Map<String, String> values = new HashMap<>();
        values.put("%FILTER%",search==null?"全部":search.filter().label());
        values.put("%MATCHES%",String.valueOf(count));
        values.put("%PAGE%", String.valueOf(Math.min(page + 1, max)));
        values.put("%MAX_PAGE%", String.valueOf(max));
        values.put("%COUNT%", String.valueOf(plugin.trash().itemCount()));
        boolean clearing = plugin.settings().pace().trashClearEnabled();
        values.put("%CLEAR_TIME%", clearing ? plugin.trashTimer().leftText() : "未开启");
        values.put("%CLEAR_AT%", clearing && plugin.trashTimer().running()
                ? java.time.Instant.ofEpochMilli(plugin.trashTimer().deadline())
                    .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                : "未开启");
        values.put("%EMPTY_SLOT%", String.valueOf(config.pageSize()
                - (search==null?plugin.trash().page(Math.min(page,max-1),config.pageSize()):search.page(Math.min(page,max-1),config.pageSize())).size()));
        return values;
    }

    private static final class Menu implements InventoryHolder {
        final TrashMenuConfig config;
        final Inventory inventory;
        final int page;
        final SearchView search;
        List<TrashStore.Entry> entries = List.of();
        Map<String, String> buttonValues;
        volatile boolean invalidated;
        Menu(TrashMenuConfig config, int page, SearchView search, Map<String, String> values) {
            this.config = config;
            this.page = page;
            this.search=search;
            inventory = Bukkit.createInventory(this, 54,
                    LegacyComponentSerializer.legacyAmpersand().deserialize(search==null?config.title(values):"&b筛选领取 &7· "+search.filter().category().label()+" &7"+(page+1)+"/"+values.get("%MAX_PAGE%")));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
