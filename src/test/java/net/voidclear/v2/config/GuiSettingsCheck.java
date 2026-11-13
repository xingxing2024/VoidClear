package net.voidclear.v2.config;

import java.nio.file.*;
import java.io.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class GuiSettingsCheck {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory(Path.of("target"),"gui-settings-");
        Path config=dir.resolve("config.yml"),messages=dir.resolve("messages.yml");
        Files.writeString(config,"# preserve this comment\ncustom: keep\nsweep:\n  adaptive:\n    enable: false\n");
        Files.writeString(messages,"custom-message: test\n");
        var snapshot=ConfigMigration.edit(dir, name->{try{return Files.newInputStream(Path.of("src/main/resources",name));}catch(IOException e){throw new UncheckedIOException(e);}},GuiSetting.ADAPTIVE);
        assert snapshot.config().getBoolean("sweep.adaptive.enable");
        assert Files.readString(config).contains("preserve this comment");
        assert snapshot.config().getString("custom").equals("keep");
        assert Files.exists(dir.resolve("config.yml.pre-voidclear.bak"));
        GuiSetting.SLICE.apply(snapshot.config());assert snapshot.config().getInt("sweep.slice.entities")==256;
        GuiSetting.DROP_AGE.apply(snapshot.config());assert snapshot.config().getInt("drop.protect-seconds")==120;
        byte[] before=Files.readAllBytes(config);Files.writeString(messages,"broken: [\n");
        try{ConfigMigration.edit(dir,n->new ByteArrayInputStream(new byte[0]),GuiSetting.ENCHANTED);throw new AssertionError("malformed messages accepted");}
        catch(org.bukkit.configuration.InvalidConfigurationException expected){}
        assert java.util.Arrays.equals(before,Files.readAllBytes(config));
        System.out.println("GuiSettingsCheck: ALL OK");
    }
}
