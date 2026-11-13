package net.voidclear.probe;

import net.voidclear.VoidClearPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated Canvas acceptance only; never bundled in VoidClear. */
public final class CanvasProbe extends JavaPlugin implements Listener {
    private volatile long delayQuit;

    @Override public void onEnable() {
        if (!Boolean.getBoolean("voidclear.probe")) throw new IllegalStateException("Explicit test flag required");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("vccanvas").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) return false;
            try {
                if ((args.length == 1 || args.length == 2) && args[0].equals("delayquit")) {
                    long ticks = args.length == 1 ? 5L : Long.parseLong(args[1]);
                    if (ticks < 5L || ticks > 40L) throw new IllegalArgumentException("Quit delay must be 5..40 ticks");
                    delayQuit = ticks;
                    sender.sendMessage("VC_CANVAS_DELAY_ARMED");
                } else if (args.length == 2 && args[0].equals("session")) {
                    var id = java.util.UUID.fromString(args[1]);
                    var transfers = ((VoidClearPlugin) getServer().getPluginManager().getPlugin("VoidClear")).playerTransfers();
                    StringBuilder state = new StringBuilder("VC_CANVAS_SESSION ").append(id);
                    for (String name : new String[]{"sessions", "joined", "retired"}) {
                        var field = transfers.getClass().getDeclaredField(name); field.setAccessible(true);
                        state.append(' ').append(name).append('=').append(((java.util.Map<?, ?>) field.get(transfers)).containsKey(id));
                    }
                    var sessions = transfers.getClass().getDeclaredField("sessions"); sessions.setAccessible(true);
                    state.append(" session=").append(((java.util.Map<?, ?>) sessions.get(transfers)).get(id));
                    state.append(" online=").append(getServer().getPlayer(id) != null);
                    sender.sendMessage(state.toString());
                } else return false;
            } catch (Exception failed) {
                getLogger().log(java.util.logging.Level.SEVERE, "VC_CANVAS_PROBE_FAILED", failed);
            }
            return true;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        long ticks = delayQuit;
        if (ticks == 0L) return;
        delayQuit = 0L;
        var id = event.getPlayer().getUniqueId();
        var release = new java.util.concurrent.CountDownLatch(1);
        getLogger().info("VC_CANVAS_DELAY_ENTER online=" + (getServer().getPlayer(id) != null));
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            getLogger().info("VC_CANVAS_DELAY_RELEASE online=" + (getServer().getPlayer(id) != null));
            release.countDown();
        }, ticks);
        try {
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Delayed quit test timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }
}
