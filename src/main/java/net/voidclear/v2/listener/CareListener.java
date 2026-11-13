package net.voidclear.v2.listener;

import net.voidclear.VoidClearPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Locale;

/**
 * 照料：农田不被踩、指定实体的爆炸不破坏方块。
 */
public final class CareListener implements Listener {

    private final VoidClearPlugin plugin;

    public CareListener(VoidClearPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------- 踩踏 ---------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrample(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        boolean farmland = block.getType() == Material.FARMLAND && event.getTo() == Material.DIRT;
        boolean egg = block.getType() == Material.TURTLE_EGG && event.getTo() == Material.AIR;
        if (!farmland && !egg) {
            return;
        }
        if (!inWorld(block)) {
            return;
        }
        Entity source = event.getEntity();
        if (source instanceof Player) {
            if (plugin.settings().care().playerTrample()) {
                event.setCancelled(true);
            }
        } else if (source instanceof LivingEntity && plugin.settings().care().mobTrample()) {
            event.setCancelled(true);
        }
    }

    /**
     * 单机/本地服的玩家踩踏不会走 {@link EntityChangeBlockEvent}，而是 PHYSICAL 交互；
     * 怪物踩踏仍由 {@link #onTrample(EntityChangeBlockEvent)} 处理：
     * 只在自己的脚下踩、只对没有取消的事件生效（别的插件已经拦了就别抢）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysical(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) {
            return;
        }
        Material type = event.getClickedBlock().getType();
        if (type != Material.FARMLAND && type != Material.TURTLE_EGG) {
            return;
        }
        if (!inWorld(event.getClickedBlock())) {
            return;
        }
        // 只认玩家自己踩；怪物的踩踏走 EntityChangeBlockEvent
        if (plugin.settings().care().playerTrample()) {
            event.setCancelled(true);
        }
    }

    private boolean inWorld(Block block) {
        var worlds = plugin.settings().care().trampleWorlds();
        return worlds.isEmpty() || worlds.contains(block.getWorld().getName());
    }

    /* ---------------- 爆炸 ---------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        String id = event.getEntity().getType().getKey().toString().toLowerCase(Locale.ROOT);
        if (!plugin.settings().care().blastBlocked().contains(id)) {
            return;
        }
        event.blockList().clear();
        event.setYield(0f);
    }
}
