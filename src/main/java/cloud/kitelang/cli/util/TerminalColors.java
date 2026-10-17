package cloud.kitelang.cli.util;

import cloud.kitelang.cli.console.Console;

/**
 * ANSI styles for kite's output. Each is its escape code when the output gets colour, and empty when
 * it does not ({@link Console#isColorEnabled()}: output not on a terminal, or {@code NO_COLOR} set), so
 * text built from them reads the same either way, only without the codes.
 *
 * <p>They are not compile-time constants on purpose: the compiler copies a constant into every class
 * that uses it, and whether there is colour is only known once kite runs.</p>
 */
public final class TerminalColors {

    // ANSI escape codes
    public static final String RESET = ansi("\u001B[0m");
    public static final String BOLD = ansi("\u001B[1m");
    /** Faint text. */
    public static final String DIM = ansi("\u001B[2m");

    // Colors
    public static final String RED = ansi("\u001B[31m");
    public static final String GREEN = ansi("\u001B[32m");
    public static final String YELLOW = ansi("\u001B[33m");
    public static final String MAGENTA = ansi("\u001B[35m");
    public static final String CYAN = ansi("\u001B[36m");
    public static final String WHITE = ansi("\u001B[37m");

    private TerminalColors() {
        // Utility class
    }

    private static String ansi(String code) {
        return Console.isColorEnabled() ? code : "";
    }
}
