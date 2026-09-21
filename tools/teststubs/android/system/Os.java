package android.system;

/** JVM-only Windows implementation of the Android rename primitive. Never included in APK dex. */
public final class Os {
    private Os() {}
    public static void rename(String from, String to) throws java.io.IOException {
        java.nio.file.Files.move(java.nio.file.Paths.get(from),java.nio.file.Paths.get(to),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
