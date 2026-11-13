package net.voidclear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.voidclear.v2.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.ArrayList;
import java.util.List;

public final class CommandHelpCheck {
    public static void main(String[] args) {
        Settings settings = Settings.blank();
        for (int page = 1; page <= 3; page++) {
            List<Component> lines = CommandHelp.lines(settings, true, page);
            List<ClickEvent> clicks = new ArrayList<>();
            for (Component line : lines) inspect(line, clicks);
            assert clicks.stream().filter(click -> click.action() == ClickEvent.Action.SUGGEST_COMMAND).count()
                    == new int[]{6, 6, 4}[page - 1];
            assert lines.size() <= 12 : "Help reverted to a long flat list";
            for (ClickEvent click : clicks) {
                if (click.action() == ClickEvent.Action.RUN_COMMAND) assert click.value().matches("/vc help [1-3]");
                else assert click.action() == ClickEvent.Action.SUGGEST_COMMAND;
                assert !click.value().contains("confirm") : "A dangerous action bypassed manual confirmation";
            }
            assert text(lines).contains(page + " / 3");
        }
        assert text(CommandHelp.lines(settings,true,3)).contains("/vc admin");
        List<Component> publicHelp = CommandHelp.lines(settings, false, 1);
        String publicText = text(publicHelp);
        assert publicText.contains("/vc trash") && !publicText.contains("/vc sweep") && !publicText.contains("receipt");
        List<ClickEvent> clicks = new ArrayList<>(); publicHelp.forEach(line -> inspect(line, clicks));
        assert clicks.size() == 1 && clicks.getFirst().action() == ClickEvent.Action.SUGGEST_COMMAND;
        var custom = new YamlConfiguration(); custom.set("help.header", "&fCUSTOM");
        custom.set("help.descriptions.trash", "MY BIN"); settings.installMessages(custom);
        assert text(CommandHelp.lines(settings, false, 1)).contains("CUSTOM");
        assert text(CommandHelp.lines(settings, false, 1)).contains("MY BIN");
        var defaults = new YamlConfiguration(); defaults.set("help.lines", List.of("default")); custom.setDefaults(defaults);
        assert !settings.hasMessage("help.lines") : "Builtin fallback must not suppress the new help";
        custom.set("help.lines", List.of()); assert settings.hasMessage("help.lines");
        assert settings.msgLines("help.lines", List.of("fallback")).isEmpty();
        System.out.println("CommandHelpCheck: ALL OK");
    }

    private static void inspect(Component component, List<ClickEvent> clicks) {
        if (component.clickEvent() != null) {
            clicks.add(component.clickEvent());
            assert component.hoverEvent() != null : "Clickable text needs usage/safety guidance";
        }
        component.children().forEach(child -> inspect(child, clicks));
    }
    private static String text(List<Component> lines) {
        return String.join("\n", lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }
}
