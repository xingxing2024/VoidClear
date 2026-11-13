package net.voidclear.v2.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Use the server's actual player IO directory, not a dimension folder or a guessed legacy path. */
public final class PlayerDataDirectory {
    private PlayerDataDirectory() { }

    public static Path resolve(Object craftServer) throws IOException {
        try {
            Object server = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object players = server.getClass().getMethod("getPlayerList").invoke(server);
            Object storage = players.getClass().getField("playerIo").get(players);
            Object directory = storage.getClass().getMethod("getPlayerDir").invoke(storage);
            if (!(directory instanceof File file) || !Files.isDirectory(file.toPath()))
                throw new IOException("服务端玩家存档目录不存在或类型无效");
            return file.toPath().toRealPath();
        } catch (ReflectiveOperationException | RuntimeException failed) {
            throw new IOException("无法确认服务端实际玩家存档目录，禁止猜测路径后处理交易", failed);
        }
    }
}
