package net.voidclear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.voidclear.v2.config.Settings;

import java.util.ArrayList;
import java.util.List;

/** Compact chat guide; command rows suggest text, only page navigation executes. */
final class CommandHelp {
    private record Entry(String command, String description, String usage) { }
    private static final List<List<Entry>> PAGES = List.of(
            List.of(new Entry("trash", "打开公共回收箱", "trash"),
                    new Entry("sweep", "立即开始一轮清扫", "sweep"),
                    new Entry("status", "查看倒计时与上轮结果", "status"),
                    new Entry("check", "查看实体清扫规则", "check <实体> [世界]"),
                    new Entry("keep", "保护指定实体", "keep [名字|id|all]"),
                    new Entry("unkeep", "取消实体保护", "unkeep [名字|id|all]")),
            List.of(new Entry("audit", "查询本服物品存取", "audit [1-20] [type <DEPOSIT|WITHDRAW>|player <名字或UUID>]"),
                    new Entry("history", "检索管理操作记录", "history [1-20] [type <操作>|player <玩家>]"),
                    new Entry("trend", "查看每日清扫趋势", "trend [1-365天]"),
                    new Entry("export", "导出清扫统计 CSV", "export [1-365天]"),
                    new Entry("diagnose", "只读诊断与报告导出", "diagnose [export]")),
            List.of(new Entry("reload", "重载配置与提示文案", "reload"),
                    new Entry("trash clear", "永久清空回收箱 · 需确认", "trash clear confirm"),
                    new Entry("receipt", "清理失效收据 · 仅控制台", "receipt <玩家UUID> [confirm <预览确认码>]")));

    static List<Component> lines(Settings messages, boolean admin, int page) {
        if (page < 1 || page > (admin ? PAGES.size() : 1)) throw new IllegalArgumentException("Invalid help page");
        var lines = new ArrayList<Component>();
        lines.add(Component.empty());
        lines.add(text(messages.msg("help.header", " &b&l虚空清理 &8/ &f使用指南")));
        String section = admin ? messages.msg("help.sections." + page, List.of("日常管理", "记录与诊断", "维护与安全").get(page - 1))
                : messages.msg("help.public", "公共回收");
        lines.add(text(" &8" + page + " / " + (admin ? PAGES.size() : 1) + " &8— &7" + section));
        lines.add(Component.empty());
        for (Entry entry : admin ? PAGES.get(page - 1) : List.of(PAGES.getFirst().getFirst())) {
            String description = messages.msg("help.descriptions." + entry.command.replace(' ', '-'), entry.description);
            lines.add(text(" &8│ &f/vc " + entry.command + " &8· &7" + description)
                    .hoverEvent(HoverEvent.showText(text("&f/vc " + entry.usage + "\n&7" + description + "\n&8点击填入，不会直接执行")))
                    .clickEvent(ClickEvent.suggestCommand("/vc " + entry.command + " ")));
        }
        lines.add(Component.empty());
        Component footer = text(messages.msg("help.footer", " &8悬停看用法 · 点击填入"));
        if (admin) {
            if (page > 1) footer = footer.append(navigation("  &7‹ 上一页", page - 1));
            if (page < PAGES.size()) footer = footer.append(navigation("  &b下一页 ›", page + 1));
        }
        lines.add(footer);
        return lines;
    }

    private static Component navigation(String label, int page) {
        return text(label).hoverEvent(HoverEvent.showText(text("&7/vc help " + page)))
                .clickEvent(ClickEvent.runCommand("/vc help " + page));
    }

    private static Component text(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('\u00a7', '&'));
    }
}
