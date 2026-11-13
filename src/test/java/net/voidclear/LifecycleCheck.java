package net.voidclear;

import java.util.logging.Logger;

public final class LifecycleCheck {
    public static void main(String[] args) {
        Logger logger = Logger.getLogger("LifecycleCheck");
        int[] finished = {0};
        VoidClearPlugin.cleanup(logger, "injected scheduling rejection", () -> {
            throw new java.util.concurrent.RejectedExecutionException("expected");
        });
        VoidClearPlugin.cleanup(logger, "storage close", () -> finished[0]++);
        VoidClearPlugin.cleanup(logger, "timer save", () -> finished[0]++);
        assert finished[0] == 2 : "A rejected menu task skipped later cleanup";
        System.out.println("LifecycleCheck: ALL OK");
    }
}
