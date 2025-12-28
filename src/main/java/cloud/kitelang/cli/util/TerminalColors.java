package cloud.kitelang.cli.util;

/**
 * ANSI terminal color utilities for consistent CLI output styling.
 */
public final class TerminalColors {

    // ANSI escape codes
    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";

    // Colors
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    // Bright colors
    public static final String BRIGHT_GREEN = "\u001B[92m";
    public static final String BRIGHT_RED = "\u001B[91m";
    public static final String BRIGHT_YELLOW = "\u001B[93m";
    public static final String BRIGHT_CYAN = "\u001B[96m";

    private TerminalColors() {
        // Utility class
    }

    /**
     * Wraps text in green color (for success messages).
     */
    public static String green(String text) {
        return GREEN + text + RESET;
    }

    /**
     * Wraps text in red color (for error messages).
     */
    public static String red(String text) {
        return RED + text + RESET;
    }

    /**
     * Wraps text in yellow color (for warnings).
     */
    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }

    /**
     * Wraps text in cyan color (for prompts/info).
     */
    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    /**
     * Wraps text in bold.
     */
    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    /**
     * Success checkmark in green.
     */
    public static String success(String message) {
        return GREEN + "\u2713 " + RESET + message;
    }

    /**
     * Error X mark in red.
     */
    public static String error(String message) {
        return RED + "\u2717 " + RESET + message;
    }

    /**
     * Warning with yellow indicator.
     */
    public static String warning(String message) {
        return YELLOW + "! " + RESET + message;
    }

    /**
     * Prompt/question with cyan indicator.
     */
    public static String prompt(String message) {
        return CYAN + "? " + RESET + message;
    }
}
