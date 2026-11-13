package net.voidclear.v2.config;

import java.util.List;
import java.util.Locale;

/** 纯函数工具：时间匹配、颜色码。 */
public final class Values {

    private Values() {
    }

    /** 当前这一分钟是否是配置里的 {@code HH:mm}。 */
    public static boolean hitsMinute(List<String> times, long millis) {
        if (times == null || times.isEmpty()) {
            return false;
        }
        java.time.ZonedDateTime now = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault());
        String target = String.format(Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute());
        for (String raw : times) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            if (value.length() == 4 && value.charAt(1) == ':') {
                value = "0" + value;
            }
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 下一个命中配置的时刻（毫秒）；没有可用时刻返回 -1。 */
    public static long nextTime(List<String> times, long millis, java.time.ZoneId zone) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone);
        java.time.ZonedDateTime best = null;
        for (String raw : times) {
            java.time.LocalTime at = parseTime(raw);
            if (at == null) {
                continue;
            }
            java.time.ZonedDateTime candidate = now.toLocalDate().atTime(at).atZone(zone);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
            if (best == null || candidate.isBefore(best)) {
                best = candidate;
            }
        }
        return best == null ? -1 : best.toInstant().toEpochMilli();
    }

    /** {@code "4:00"} / {@code "04:00"}；坏值返回 null。 */
    public static java.time.LocalTime parseTime(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() == 4 && value.charAt(1) == ':') {
            value = "0" + value;
        }
        try {
            return java.time.LocalTime.parse(value);
        } catch (java.time.format.DateTimeParseException bad) {
            return null;
        }
    }

    /** {@code "&a"} 风格的着色码转 {@code §a}；已经带 {@code §} 的按原样处理。 */
    private static final java.util.regex.Pattern CODES =
            java.util.regex.Pattern.compile("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-or])");

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        return CODES.matcher(text.replace('§', '&')).replaceAll(match ->
                "\u00a7" + match.group().substring(1));
    }
}
