package net.voidclear;

public final class PlaceholdersCheck {
    public static void main(String[] args) {
        assert Placeholders.formatTime(-1).equals("0分00秒");
        assert Placeholders.formatTime(0).equals("0分00秒");
        assert Placeholders.formatTime(61).equals("1分01秒");
        assert Placeholders.formatTime(3600).equals("60分00秒");
        assert Placeholders.formatDeadline(0).equals("未开启");
        long time = java.time.Instant.parse("2026-11-07T04:00:00Z").toEpochMilli();
        String expected = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assert Placeholders.formatDeadline(time).equals(expected);
        assert Placeholders.resolve(null, "unknown") == null;
        assert Placeholders.resolve(null, null) == null;
        assert Placeholders.resolve(null, "TRASH_CAPACITY").equals("10000");
        System.out.println("PlaceholdersCheck: ALL OK");
    }
}
