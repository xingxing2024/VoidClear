package net.voidclear.v2.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only operational information; no credentials, item evidence or recovery actions. */
public final class DiagnosticReport {
    private DiagnosticReport() { }

    public record Local(Instant time, UUID server, boolean mysql, int queued, int running,
                        int sessions, int pending, int isolated) { }

    public static List<String> lines(Local local, TrashStore.Diagnostics database) {
        var lines = new ArrayList<String>();
        lines.add("时间 " + local.time() + " · 本服 " + local.server());
        lines.add("后端 " + (local.mysql() ? "MySQL" : "SQLite") + " · 后台任务排队 " + local.queued() + " / 执行 " + local.running());
        lines.add("本服会话 " + local.sessions() + " · 待确认玩家 " + local.pending() + " · 已拦截玩家 " + local.isolated());
        if (database == null) {
            lines.add("数据库读取失败；状态未知，请检查服务端日志。未解除隔离或修改物品。");
        } else {
            lines.add("数据库读取正常 · 版本 " + database.revision() + " · 物品组 " + database.itemGroups()
                    + " · 全库会话 " + database.sessions());
            lines.add("未完成操作 " + database.unfinished().values().stream().mapToLong(Long::longValue).sum()
                    + " · PREPARED=" + database.unfinished().getOrDefault("PREPARED", 0L)
                    + " CURSOR=" + database.unfinished().getOrDefault("CURSOR", 0L)
                    + " DROPS=" + database.unfinished().getOrDefault("DROPS", 0L)
                    + " QUARANTINED=" + database.unfinished().getOrDefault("QUARANTINED", 0L));
            for (var operation : database.recent()) {
                lines.add("玩家 " + operation.player() + " · " + safe(operation.state()) + "（" + meaning(operation.state())
                        + "） · 时间 " + Instant.ofEpochMilli(operation.createdAt()) + " · 操作 " + operation.operation()
                        + " · 会话 " + operation.session() + " · 归属 " + safe(operation.owner() == null ? "无匹配会话" : operation.owner()));
            }
        }
        lines.add("只读瞬时采样；未完成操作不等于异常，最多展示10条、隔离优先。具体失败原因请按玩家/操作ID查日志。");
        return List.copyOf(lines);
    }

    public static String meaning(String state) {
        return switch (state) {
            case "PREPARED" -> "已准备，尚未确认交付";
            case "CURSOR" -> "仍有光标物品待核验";
            case "DROPS" -> "保留地面来源凭据";
            case "QUARANTINED" -> "已隔离，须核对存档与日志";
            default -> "未知状态，须核查数据库";
        };
    }

    public static String safe(String text) {
        return text.replaceAll("[\\p{Cc}\\p{Cf}&\u00a7]", "?");
    }

    public static Path export(Path directory, List<String> lines) {
        Path target = directory.resolve("diagnostics.txt"), temporary = directory.resolve("diagnostics.txt.tmp");
        try {
            Files.createDirectories(directory);
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException failed) { throw new IllegalStateException("无法导出诊断报告", failed); }
    }
}
