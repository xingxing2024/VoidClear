package net.voidclear.v2.runtime;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 调度适配层：Bukkit 主线程与 Folia 区域线程。
 *
 * <p>Folia 上 {@code BukkitScheduler} 会直接抛异常，跨区域读写实体同样会抛；
 * 所以所有碰世界对象的地方都必须经这几个接口进入正确线程。</p>
 *
 * <p>Paper 上：主线程调用原地执行；别的线程调用一律排回主线程
 * （Paper 的 Bukkit 对象都绑定在主线程上，从异步线程摸会抛异常或留下脏状态）。</p>
 */
public interface Scheduler {

    /** 可取消的周期任务。 */
    interface Beat {
        void cancel();
    }

    /** 真调度器：Bukkit / Folia。 */
    static Scheduler create(Plugin plugin) {
        return new BukkitScheduler(plugin);
    }

    /**
     * 全局状态（倒计时、配置）只在全局调度器上碰：
     * Paper 主线程 / Folia global region。Folia 上命令和点击都在各自的区域线程，
     * 直接改这些共享对象会和其他区域打架。
     */
    static void global(Plugin plugin, Runnable task) {
        if (onMain()) {
            task.run();
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    /**
     * 是否拥有全局状态；Folia 区域 tick 线程也可能通过 isPrimaryThread，不能用它判定。
     * try 只包判定，不能把任务异常当作调度失败后再跑一次。
     */
    private static boolean onMain() {
        try {
            return Bukkit.isGlobalTickThread();
        } catch (RuntimeException folia) {
            return false;
        }
    }

    boolean folia();

    boolean running();

    /** 停用：关掉之后排的任务全部丢弃，免得和已经关掉的调度器打架。 */
    void shutdown();

    /** 全局节拍（Folia 的 global region 或 Paper 主线程）。 */
    Beat every(long periodTicks, Runnable task);

    /**
     * 全局跑一次（续派下一批区块用：写在区块任务里会等自己的区域）。
     * Paper 上在主线程原地执行，否则排到主线程。
     *
     * @return false 表示没排上（已停用或调度器不收），调用方要自己补上原本该做的事
     */
    boolean once(Runnable task);

    /**
     * 全局跑一次、至少等多少 tick。排下一批区块用它：不能内联，否则一批在一拍里跑完就违背了分批的初衷。
     *
     * @return false 表示没排上（已停用），调用方要自己补上原本该做的事
     */
    boolean later(long ticks, Runnable task);

    /**
     * 在区块所属区域执行；Paper 见 {@link #once}。
     *
     * @return false 表示没排上，任务不会跑，调用方不能等它的回调
     */
    boolean onChunk(org.bukkit.World world, int chunkX, int chunkZ, Runnable task);

    /** Continue a chunk job on its owning region after at least one tick. */
    boolean onChunkLater(org.bukkit.World world, int chunkX, int chunkZ, long ticks, Runnable task);

    /** 在实体所属区域执行；Paper 见 {@link #once}。 */
    void near(org.bukkit.entity.Entity entity, Runnable task);

    /** 玩家自己的线程：Folia 上发送玩家消息必须在这里。Paper 见 {@link #once}。 */
    void player(Player player, Runnable task);

    /**
     * 玩家自己的线程、至少下一拍再跑。
     * 需要延后一拍执行的玩家任务使用这个入口。
     */
    void playerNextTick(Player player, Runnable task);

    /** Bukkit / Folia 下的真实现。 */
    final class BukkitScheduler implements Scheduler {

        private final Plugin plugin;
        private final boolean folia;
        private volatile boolean stopped;

        public BukkitScheduler(Plugin plugin) {
            this.plugin = plugin;
            this.folia = detect();
        }

        private static boolean detect() {
            // 不能靠类存不存在判断：io.papermc.paper.threadedregions.scheduler.* 在 Paper API 里也有。
            // Folia 的日志/插件管理器类只有 Folia 才带
            return present("io.papermc.paper.threadedregions.RegionizedServer");
        }

        private static boolean present(String name) {
            try {
                Class.forName(name, false, BukkitScheduler.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError paper) {
                return false;
            }
        }

        @Override
        public boolean folia() {
            return folia;
        }

        @Override
        public boolean running() {
            return !stopped;
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        /** Paper：是否在主线程（没有主线程的地方返回 false）。 */
        private static boolean onMain() {
            return Scheduler.onMain();
        }

        @Override
        public Beat every(long periodTicks, Runnable task) {
            if (stopped) {
                return () -> {
                };
            }
            long period = Math.max(1L, periodTicks);
            if (folia) {
                var handle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, region -> task.run(), period, period);
                return handle::cancel;
            }
            var handle = Bukkit.getScheduler().runTaskTimer(plugin, task, period, period);
            return handle::cancel;
        }

        @Override
        public boolean once(Runnable task) {
            if (stopped) {
                return false;
            }
            if (folia) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            } else if (onMain()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
            return true;
        }

        @Override
        public boolean later(long ticks, Runnable task) {
            if (stopped) {
                return false;
            }
            long delay = Math.max(1L, ticks);
            if (folia) {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), delay);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
            return true;
        }

        @Override
        public boolean onChunk(org.bukkit.World world, int chunkX, int chunkZ, Runnable task) {
            if (stopped) {
                return false;
            }
            if (folia) {
                Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
                return true;
            }
            return once(task);
        }

        @Override
        public boolean onChunkLater(org.bukkit.World world, int x, int z, long ticks, Runnable task) {
            if (stopped) return false;
            Runnable current = () -> { if (!stopped) task.run(); };
            if (folia) Bukkit.getRegionScheduler().runDelayed(plugin, world, x, z, ignored -> current.run(), Math.max(1, ticks));
            else Bukkit.getScheduler().runTaskLater(plugin, current, Math.max(1, ticks));
            return true;
        }

        @Override
        public void near(org.bukkit.entity.Entity entity, Runnable task) {
            if (stopped) {
                return;
            }
            if (folia) {
                entity.getScheduler().execute(plugin, task, null, 0L);
            } else {
                once(task);
            }
        }

        @Override
        public void player(Player player, Runnable task) {
            if (stopped) {
                return;
            }
            if (folia) {
                player.getScheduler().execute(plugin, task, null, 0L);
            } else {
                once(task);
            }
        }

        @Override
        public void playerNextTick(Player player, Runnable task) {
            if (stopped) {
                return;
            }
            if (!folia) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!stopped) task.run(); }, 1L);
                return;
            }
            if (player.isDead()) {
                var location = player.getLocation();
                Bukkit.getRegionScheduler().runDelayed(plugin, location, scheduled -> {
                    if (!stopped && Bukkit.isOwnedByCurrentRegion(player)) task.run();
                }, 1L);
                return;
            }
            // 已排任务执行前也复查停用状态，不能只依赖服务端取消队列。
            player.getScheduler().run(plugin, scheduled -> {
                if (!stopped) {
                    task.run();
                }
            }, null);
        }

    }
}
