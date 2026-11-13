package net.voidclear.v2.runtime;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/** 清扫聊天提示。 */
public final class ChatNotice {
    private ChatNotice() {
    }

    public static Component of(String colored) {
        return LegacyComponentSerializer.legacySection().deserialize(colored);
    }

    /** 由调用方在玩家所属线程执行。 */
    public static void send(Player player, String colored) {
        player.sendMessage(of(colored));
    }
}
