package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class SweepReportCheck {
    public static void main(String[] args) throws Exception {
        for (boolean defaults : new boolean[]{false, true}) {
            Settings settings = Settings.blank();
            if (defaults) {
                var messages = new YamlConfiguration();
                messages.loadFromString(Files.readString(Path.of("src/main/resources/messages.yml")));
                settings.installMessages(messages);
            }
            String empty = SweepReport.result(settings, 0, 0, 0, 0, 200);
            assert empty.contains("未处理目标") && empty.contains("已加载区块") && empty.contains("0.20");
            assert !empty.contains("/vc trash") && !empty.contains("清扫完成");
            String entities = SweepReport.result(settings, 7, 0, 0, 0, 1234);
            assert entities.contains("7") && entities.contains("已处理") && !entities.contains("/vc trash");
            String collected = SweepReport.result(settings, 3, 2, 2, 0, 1234);
            assert !collected.contains("组") : "Drop entities must not be presented as stacks";
            assert collected.contains("/vc trash") && collected.contains("确认回收") && !collected.contains("领取");
            assert SweepReport.result(settings, 1, 1, 13, 0, 20).contains("13");
            assert SweepReport.result(settings, 2, 2, 80, 0, 20).contains("80");
            for (int targets : new int[]{0, 5}) {
                String failure = SweepReport.result(settings, targets, 0, 0, 2, 500);
                assert failure.contains("未完整执行") && failure.contains("失败任务") && !failure.contains("/vc trash");
                assert !failure.contains("无符合规则") && !failure.contains("清扫完成");
            }
            String partial = SweepReport.result(settings, 3, 1, 4, 1, 500);
            assert partial.contains("未完整执行") && partial.contains("/vc trash");
            assert !partial.contains("%COUNT%") && !partial.contains("%FAILED%") && !partial.contains("%SECONDS%");
        }
        var settings = Settings.blank();
        var custom = new YamlConfiguration(); custom.set("prefix", "");
        custom.set("sweep.done", "custom %COUNT%/%COLLECTED%/%ITEMS%/%FAILED%/%SECONDS%");
        custom.set("sweep.claim", " CLAIM"); settings.installMessages(custom);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assert SweepReport.result(settings, 4, 2, 80, 0, 1250).equals("custom 4/2/80/0/1.25 CLAIM");
        } finally { Locale.setDefault(previous); }
        System.out.println("SweepReportCheck: ALL OK");
    }
}
