package net.voidclear.v2.runtime;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class CountdownCheck {
    public static void main(String[] args) throws Exception {
        assert !Countdown.visibleAt(61, 60);
        assert Countdown.visibleAt(60, 60);
        assert Countdown.visibleAt(1, 60);
        assert !Countdown.visibleAt(0, 60);
        assert Countdown.visibleAt(3600, 0);
        assert net.voidclear.Placeholders.formatTime(2349).equals("39分09秒");
        assert net.voidclear.Placeholders.formatTime(0).equals("0分00秒");
        assert net.voidclear.Placeholders.formatTime(-1).equals("0分00秒");
        actionBar();
        soundReload();
        System.out.println("CountdownCheck PASS");
    }

    private static void soundReload() throws Exception {
        var timer = new Countdown(null, () -> {});
        var tick = Countdown.class.getDeclaredField("tickKey"); tick.setAccessible(true);
        var finish = Countdown.class.getDeclaredField("finishKey"); finish.setAccessible(true);
        timer.sound("custom:tick", "custom:finish", 1, 1);
        assert tick.get(timer).equals("custom:tick") && finish.get(timer).equals("custom:finish");
        timer.sound("", "  ", 1, 1);
        assert tick.get(timer).equals("") && finish.get(timer).equals("  ") : "Reload retained the old sounds";
        timer.finish(); // Blank sounds must return before consulting Bukkit or scheduling player work.
        timer.sound(null, null, 1, 1);
        assert tick.get(timer).equals("") && finish.get(timer).equals("");
        timer.finish();
        timer.sound("custom:new_tick", "custom:new_finish", 1, 1);
        assert tick.get(timer).equals("custom:new_tick") && finish.get(timer).equals("custom:new_finish");
    }

    private static void actionBar() throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previous = serverField.get(null);
        var queued = new ArrayDeque<Runnable>();
        var sent = new ArrayList<String>();
        var entityScheduler = (EntityScheduler) Proxy.newProxyInstance(CountdownCheck.class.getClassLoader(),
                new Class<?>[]{EntityScheduler.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("run")) throw new AssertionError(method.getName());
                    @SuppressWarnings("unchecked")
                    var callback = (Consumer<Object>) args[1];
                    queued.add(() -> callback.accept(null));
                    return null;
                });
        var player = (Player) Proxy.newProxyInstance(CountdownCheck.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getScheduler" -> entityScheduler;
                    case "isOnline" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "sendActionBar" -> {
                        sent.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    default -> throw new AssertionError("unexpected player call: " + method.getName());
                });
        var server = (Server) Proxy.newProxyInstance(CountdownCheck.class.getClassLoader(),
                new Class<?>[]{Server.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getOnlinePlayers")) return List.of(player);
                    throw new AssertionError("unexpected server call: " + method.getName());
                });
        try {
            serverField.set(null, server);
            int[] completed = {0};
            var timer = new Countdown(null, () -> completed[0]++);
            timer.look(false, "RED", "boss disabled");
            timer.showBefore(45);
            timer.actionBar(true, "&b清扫 %TIME% 秒");
            timer.beginAt(System.currentTimeMillis() + 59_500, 60);
            timer.tick("");
            assert queued.isEmpty() : "must follow configured threshold, not hardcoded 30";
            timer.beginAt(System.currentTimeMillis() + 44_500, 60);
            timer.tick("");
            assert sent.isEmpty() && queued.size() == 1 : "send must use player scheduler";
            drain(queued);
            assert sent.equals(List.of("清扫 45 秒")) : "ActionBar works with Boss disabled";
            timer.tick("");
            assert queued.isEmpty() : "same second must not repeat";
            var deadline = Countdown.class.getDeclaredField("deadline");
            deadline.setAccessible(true);
            deadline.setLong(timer, System.currentTimeMillis() + 43_500);
            timer.tick("");
            drain(queued);
            assert sent.getLast().equals("清扫 44 秒") : "next second must update without chat";
            timer.stop();
            drain(queued);
            assert sent.equals(List.of("清扫 45 秒", "清扫 44 秒", "")) : "stop clears once";
            timer.stop();
            timer.tick("");
            drain(queued);
            assert sent.size() == 3 : "inactive countdown must not clear other plugins";

            timer.showBefore(0);
            timer.beginAt(System.currentTimeMillis() + 59_500, 60);
            timer.tick("");
            timer.stop();
            drain(queued);
            assert sent.size() == 3 : "queued old prompt must not revive after stop";
            timer.beginAt(System.currentTimeMillis() + 59_500, 60);
            timer.tick("");
            drain(queued);
            assert sent.getLast().equals("清扫 60 秒") : "zero threshold shows whole countdown";
            timer.actionBar(false, "disabled");
            drain(queued);
            int count = sent.size();
            timer.tick("");
            drain(queued);
            assert sent.size() == count && sent.getLast().isEmpty() : "disable clears once";

            timer.actionBar(true, "清扫 %TIME% 秒");
            timer.tick("");
            drain(queued);
            deadline.setLong(timer, System.currentTimeMillis() - 1);
            assert timer.tick("") && completed[0] == 1;
            drain(queued);
            assert sent.getLast().isEmpty() : "expiry clears prompt";
        } finally {
            serverField.set(null, previous);
        }
    }

    private static void drain(ArrayDeque<Runnable> queued) {
        while (!queued.isEmpty()) queued.remove().run();
    }
}
