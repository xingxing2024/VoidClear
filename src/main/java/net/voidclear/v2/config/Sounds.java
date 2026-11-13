package net.voidclear.v2.config;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 音效 id 的解析：配置里写 {@code block.note_block.pling} 或
 * {@code minecraft:block.note_block.pling} 都认；认不出的按自定义音效键原样播。
 */
public final class Sounds {

    private static volatile Map<String, Sound> index;

    private Sounds() {
    }

    public static Sound find(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Map<String, Sound> map = index;
        if (map == null) {
            map = new HashMap<>();
            @SuppressWarnings("removal")
            Sound[] all = Sound.values();
            for (Sound sound : all) {
                // 旧式枚举 API 已标记移除，这里只用来建一次 id → Sound 的索引
                @SuppressWarnings("removal")
                String id = sound.getKey().getKey();
                map.put(id, sound);
            }
            index = map;
        }
        String needle = key.trim().toLowerCase(Locale.ROOT);
        if (needle.startsWith("minecraft:")) {
            needle = needle.substring("minecraft:".length());
        }
        return map.get(needle);
    }

    /**
     * 给所有在线玩家放一次。
     *
     * <p>必须在全局调度器（Paper 主线程 / Folia global region）上调用，但播放本身不在这里做：
     * {@code player.playSound} 会读玩家位置，读别人的位置在 Folia 上是跨区域访问。
     * 所以这里只排任务，真正的 {@code getLocation + playSound} 落到每个玩家自己的区域线程。</p>
     */
    public static void play(Plugin plugin, String key, float volume, float pitch) {
        if (key == null || key.isBlank()) {
            return;
        }
        Sound sound = find(key);
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.getScheduler().run(plugin, scheduled -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (sound != null) {
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    } else {
                        player.playSound(player.getLocation(), key, volume, pitch);
                    }
                }, null);
            } catch (RuntimeException gone) {
                // 玩家刚好下线 / 插件正在停用：这次不响，不影响别的玩家
            }
        }
    }
}
