package net.voidclear.v2.runtime;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class ChatNoticeCheck {
    public static void main(String[] args) {
        var plain = PlainTextComponentSerializer.plainText();
        for (String colored : new String[]{"§7清扫将在 §b10 §7秒后开始", "§a清扫完成：§b12 §7件", "普通文本", ""}) {
            Component notice = ChatNotice.of(colored);
            // 当前版本永久删除掉落物：清扫提示不能再带旧版回收箱入口。
            assert notice.clickEvent() == null : "no dead click target";
            assert notice.hoverEvent() == null : "no dead hover target";
            assert plain.serialize(notice).equals(plain.serialize(LegacyComponentSerializer.legacySection().deserialize(colored)))
                    : "plain text must be unchanged";
            assert notice.equals(LegacyComponentSerializer.legacySection().deserialize(colored))
                    : "colors and formatting must remain unchanged";
        }
        System.out.println("ChatNoticeCheck PASS");
    }
}
