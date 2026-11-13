package net.voidclear.v2.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class DeliveryFailureLogCheck {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory(Path.of("target"), "delivery-failure-check-");
        Properties evidence = new Properties();
        evidence.setProperty("player", "test-player");
        evidence.setProperty("failedStage", "drop[1]");
        evidence.setProperty("before.storage.0", "AQID");
        Path first = DeliveryFailureLog.write(folder, evidence);
        Path second = DeliveryFailureLog.write(folder, evidence);
        assert !first.equals(second) : "Failure records overwrite each other";
        Properties loaded = new Properties();
        try (var input = Files.newInputStream(first)) { loaded.load(input); }
        assert loaded.equals(evidence) : "Persisted evidence differs";
        Path blocked = folder.resolve("not-a-directory");
        Files.writeString(blocked, "preserve");
        try { DeliveryFailureLog.write(blocked, evidence); throw new AssertionError("Write failure swallowed"); }
        catch (java.io.IOException expected) { }
        assert Files.readString(blocked).equals("preserve");
        byte[] original = Files.readAllBytes(first);
        Properties partialFailure = new Properties() {
            @Override public void store(java.io.OutputStream output, String comment) throws java.io.IOException {
                try (var files = Files.list(folder)) {
                    assert files.filter(path -> path.toString().endsWith(".properties")).count() == 2
                            : "Final evidence visible before writing finished";
                }
                output.write("partial=evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                throw new java.io.IOException("injected midway write failure");
            }
        };
        try { DeliveryFailureLog.write(folder, partialFailure); throw new AssertionError("Partial write accepted"); }
        catch (java.io.IOException expected) { assert expected.getMessage().contains("midway"); }
        try (var files = Files.list(folder)) {
            assert files.noneMatch(path -> path.toString().endsWith(".tmp")) : "Failed temporary file not cleaned";
        }
        try (var files = Files.list(folder)) {
            assert files.filter(path -> path.toString().endsWith(".properties")).count() == 2 : "Partial final file published";
        }
        assert java.util.Arrays.equals(original, Files.readAllBytes(first)) : "Existing evidence changed";
        System.out.println("DeliveryFailureLogCheck: ALL OK");
    }
}
