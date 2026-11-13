package net.voidclear;

public final class CommandCheck {
    public static void main(String[] args) {
        assert CommandHandler.historyQuery(new String[]{"history"}).count() == 10;
        assert CommandHandler.historyQuery(new String[]{"history", "20"}).count() == 20;
        var typed = CommandHandler.historyQuery(new String[]{"history", "5", "type", "SWEEP"});
        assert typed.count() == 5 && typed.type().equals("SWEEP") && typed.player() == null;
        var player = CommandHandler.historyQuery(new String[]{"history", "player", "Alex"});
        assert player.count() == 10 && player.player().equals("Alex");
        assert CommandHandler.historyQuery(new String[]{"history", "type", "SWEEP"}).type().equals("SWEEP");
        assert CommandHandler.historyQuery(new String[]{"history", "1", "player", "Alex"}).count() == 1;
        for (String tail : new String[]{"0", "21", "-1", "invalid", "type", "5 type", "type SWEEP extra", "5 unknown value"}) {
            try {
                CommandHandler.historyQuery(("history " + tail).split(" "));
                throw new AssertionError("Accepted invalid arguments: " + tail);
            } catch (IllegalArgumentException expected) {
                // Invalid input must return usage instead of touching history.
            }
        }
        assert CommandHandler.auditQuery(new String[]{"audit"}).count() == 10;
        assert CommandHandler.auditQuery(new String[]{"audit", "5", "type", "deposit"}).type().equals("DEPOSIT");
        assert CommandHandler.auditQuery(new String[]{"audit", "type", "WITHDRAW"}).type().equals("WITHDRAW");
        assert CommandHandler.auditQuery(new String[]{"audit", "player", "Alex"}).player().equals("Alex");
        for (String tail : new String[]{"0", "21", "type SWEEP", "player", "type DEPOSIT extra", "player Alex type WITHDRAW"}) {
            try { CommandHandler.auditQuery(("audit " + tail).split(" ")); throw new AssertionError("Invalid audit accepted: " + tail); }
            catch (IllegalArgumentException expected) { }
        }
        try { CommandHandler.auditQuery(new String[]{"audit", "player", "Alex\nOther"}); throw new AssertionError("Control character accepted"); }
        catch (IllegalArgumentException expected) { }
        assert !CommandHandler.diagnoseExport(new String[]{"diagnose"});
        assert CommandHandler.diagnoseExport(new String[]{"diagnose", "EXPORT"});
        for (String[] invalid : new String[][]{{"diagnose", "unlock"}, {"diagnose", "export", "../other"}}) {
            try { CommandHandler.diagnoseExport(invalid); throw new AssertionError("Invalid diagnostic accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        var handler = new CommandHandler(null);
        var admin = sender(true);
        var playerSender = sender(false);
        assert handler.onTabComplete(playerSender,null,"vc",new String[]{"trash","s"}).equals(java.util.List.of("search"));
        assert handler.onTabComplete(playerSender,null,"vc",new String[]{"trash","category",""}).containsAll(java.util.List.of("blocks","food","resources"));
        assert handler.onTabComplete(playerSender,null,"vc",new String[]{"trash","cl"}).isEmpty();
        assert handler.onTabComplete(admin,null,"vc",new String[]{"pr"}).equals(java.util.List.of("preview"));
        assert handler.onTabComplete(admin, null, "vc", new String[]{""}).containsAll(java.util.List.of("diagnose", "audit"));
        assert handler.onTabComplete(playerSender, null, "vc", new String[]{""}).equals(java.util.List.of("trash", "help"));
        assert handler.onTabComplete(playerSender, null, "vc", new String[]{"audit", ""}).isEmpty();
        assert handler.onTabComplete(admin, null, "vc", new String[]{"diagnose", "ex"}).equals(java.util.List.of("export"));
        assert handler.onTabComplete(admin, null, "vc", new String[]{"audit", "type", "w"}).equals(java.util.List.of("WITHDRAW"));
        assert handler.onTabComplete(admin, null, "vc", new String[]{"audit", "5", "type", "d"}).equals(java.util.List.of("DEPOSIT"));
        String id = java.util.UUID.randomUUID().toString(), token = java.util.UUID.randomUUID().toString();
        assert CommandHandler.receiptQuery(new String[]{"receipt", id}).player().toString().equals(id);
        assert CommandHandler.receiptQuery(new String[]{"receipt", id}).confirmation() == null;
        assert CommandHandler.receiptQuery(new String[]{"receipt", id, "CONFIRM", token.toUpperCase(java.util.Locale.ROOT)})
                .confirmation().equals(token);
        for (String[] invalid : new String[][]{{"receipt"}, {"receipt", "Alex"}, {"receipt", "1-1-1-1-1"},
                {"receipt", id, "confirm"}, {"receipt", id, "unlock", token}, {"receipt", id, "confirm", "1-1-1-1-1"},
                {"receipt", id, "confirm", token, "extra"}}) {
            try { CommandHandler.receiptQuery(invalid); throw new AssertionError("Unsafe receipt grammar accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        assert CommandHandler.helpPage(new String[]{}, false) == 1;
        assert CommandHandler.helpPage(new String[]{"help"}, true) == 1;
        for (int page = 1; page <= 3; page++) assert CommandHandler.helpPage(new String[]{"help", "" + page}, true) == page;
        for (String[] invalid : new String[][]{{"help", "0"}, {"help", "4"}, {"help", "no"}, {"help", "1", "extra"}}) {
            try { CommandHandler.helpPage(invalid, true); throw new AssertionError("Invalid help page accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { CommandHandler.helpPage(new String[]{"help", "2"}, false); throw new AssertionError("Public admin page accepted"); }
        catch (IllegalArgumentException expected) { }
        assert handler.onTabComplete(admin, null, "vc", new String[]{"help", ""}).equals(java.util.List.of("1", "2", "3"));
        assert handler.onTabComplete(playerSender, null, "vc", new String[]{"help", ""}).equals(java.util.List.of("1"));
        assert handler.onTabComplete(admin, null, "vc", new String[]{"receipt", id, "c"}).equals(java.util.List.of("confirm"));
        assert handler.onTabComplete(playerSender, null, "vc", new String[]{"receipt", id, "c"}).isEmpty();
        System.out.println("CommandCheck: ALL OK");
    }

    private static org.bukkit.command.CommandSender sender(boolean admin) {
        return (org.bukkit.command.CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandCheck.class.getClassLoader(),
                new Class<?>[]{org.bukkit.command.CommandSender.class}, (proxy, method, values) -> {
                    if (method.getName().equals("hasPermission")) return admin && values[0].equals("voidclear.admin");
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
