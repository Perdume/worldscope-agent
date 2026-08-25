package com.injectbukkit.worldscope;

/**
 * Minimal stdout/stderr logger. Runs before the server's own logging framework
 * is guaranteed to be on the classpath (premain fires before main()), so this
 * intentionally avoids any logging dependency.
 */
public final class Log {

    private static final String PREFIX = "[WorldScope] ";
    private static final boolean VERBOSE = Boolean.parseBoolean(System.getProperty("worldscope.verbose", "false"));

    private Log() {
    }

    public static void info(String message) {
        System.out.println(PREFIX + message);
    }

    public static void debug(String message) {
        if (VERBOSE) {
            System.out.println(PREFIX + "[debug] " + message);
        }
    }

    public static void warn(String message) {
        System.out.println(PREFIX + "[warn] " + message);
    }

    public static void error(String message, Throwable cause) {
        System.err.println(PREFIX + "[error] " + message + (cause != null ? ": " + cause : ""));
        if (VERBOSE && cause != null) {
            cause.printStackTrace(System.err);
        }
    }
}
