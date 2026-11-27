package ohd.platform

internal actual object PlatformRuntime {
    actual val isJvm: Boolean = true

    actual val classpath: String? get() = System.getProperty("java.class.path")

    actual val javaHome: String? get() = System.getProperty("java.home")

    actual val version: String get() = System.getProperty("java.version", "?")

    actual fun executablePath(): OhdPath? {
        val home = System.getProperty("java.home") ?: return null
        return OhdPath.of(home, "bin", "java" + Env.exeSuffix)
    }

    actual fun isStdoutTty(): Boolean = System.console() != null

    actual fun writeStdout(text: String) {
        System.out.print(text)
        System.out.flush()
    }

    actual fun writeStderr(text: String) {
        System.err.print(text)
        System.err.flush()
    }
}
