package net.voidclear.v2.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PlayerDataDirectoryCheck {
    public record Craft(Server getServer) { }
    public record Server(Players getPlayerList) { }
    public static final class Players {
        public final Storage playerIo;
        public Players(Path directory) { playerIo = new Storage(directory.toFile()); }
    }
    public record Storage(File getPlayerDir) { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "player-directory-");
        Path legacy = Files.createDirectories(root.resolve("world/playerdata"));
        Path modern = Files.createDirectories(root.resolve("world/players/data"));
        Files.createDirectories(root.resolve("world/dimensions/minecraft/overworld/playerdata"));
        assert PlayerDataDirectory.resolve(new Craft(new Server(new Players(legacy)))).equals(legacy.toRealPath());
        assert PlayerDataDirectory.resolve(new Craft(new Server(new Players(modern)))).equals(modern.toRealPath());
        try { PlayerDataDirectory.resolve(new Craft(new Server(new Players(root.resolve("missing"))))); throw new AssertionError("Guessed legacy fallback"); }
        catch (IOException expected) { }
        try { PlayerDataDirectory.resolve(new Object()); throw new AssertionError("Unsupported server accepted"); }
        catch (IOException expected) { }
        System.out.println("PlayerDataDirectoryCheck: ALL OK");
    }
}
