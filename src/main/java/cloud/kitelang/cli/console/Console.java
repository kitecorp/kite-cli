package cloud.kitelang.cli.console;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central console output utility for consistent terminal rendering.
 * Uses JLine terminal when available, falls back to System.out.
 */
public final class Console {

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";

    private static Terminal terminal;
    private static PrintWriter writer;

    static {
        // Suppress JLine warnings about dumb terminals
        Logger.getLogger("org.jline").setLevel(Level.SEVERE);

        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
            writer = terminal.writer();
        } catch (IOException e) {
            // Fallback to System.out
            terminal = null;
            writer = new PrintWriter(System.out, true);
        }
    }

    private Console() {
        // Utility class
    }

    /**
     * Prints a line to the console.
     */
    public static void println(String message) {
        writer.println(message);
        writer.flush();
    }

    /**
     * Prints an empty line.
     */
    public static void println() {
        writer.println();
        writer.flush();
    }

    /**
     * Prints without newline.
     */
    public static void print(String message) {
        writer.print(message);
        writer.flush();
    }

    /**
     * Prints formatted string.
     */
    public static void printf(String format, Object... args) {
        writer.printf(format, args);
        writer.flush();
    }

    /**
     * Prints a success message with green checkmark.
     */
    public static void success(String message) {
        println(GREEN + "\u2713 " + RESET + message);
    }

    /**
     * Prints an error message with red X.
     */
    public static void error(String message) {
        println(RED + "\u2717 " + RESET + message);
    }

    /**
     * Prints a warning message with yellow indicator.
     */
    public static void warning(String message) {
        println(YELLOW + "! " + RESET + message);
    }

    /**
     * Prints an info message.
     */
    public static void info(String message) {
        println(message);
    }

    /**
     * Prints a header with underline.
     */
    public static void header(String title) {
        println();
        println(BOLD + CYAN + title + RESET);
        println(CYAN + "=".repeat(title.length()) + RESET);
        println();
    }

    /**
     * Returns text wrapped in bold.
     */
    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    /**
     * Returns text wrapped in green.
     */
    public static String green(String text) {
        return GREEN + text + RESET;
    }

    /**
     * Returns text wrapped in red.
     */
    public static String red(String text) {
        return RED + text + RESET;
    }

    /**
     * Returns text wrapped in yellow.
     */
    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }

    /**
     * Returns text wrapped in cyan.
     */
    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    /**
     * Moves cursor up and clears the line.
     */
    public static void clearPreviousLine() {
        print("\033[1A\033[2K");
    }

    /**
     * Returns the shared JLine terminal instance.
     * Use this to avoid creating multiple terminals.
     */
    public static Terminal getTerminal() {
        return terminal;
    }

    /**
     * Checks if the terminal supports interactive mode.
     */
    public static boolean isInteractive() {
        return terminal != null
                && terminal.getType() != null
                && !terminal.getType().equals(Terminal.TYPE_DUMB)
                && terminal.getWidth() > 0;
    }

    /**
     * Prints lines surrounded by a box using Unicode box-drawing characters.
     *
     * @param lines The lines to display inside the box
     */
    public static void box(String... lines) {
        if (lines == null || lines.length == 0) {
            return;
        }

        // Find the longest line
        int maxLen = 0;
        for (var line : lines) {
            if (line.length() > maxLen) {
                maxLen = line.length();
            }
        }

        // Add padding
        int boxWidth = maxLen + 2;

        // Top border: ┌───┐
        var top = new StringBuilder();
        top.append("┌");
        top.append("─".repeat(boxWidth));
        top.append("┐");
        println(top.toString());

        // Content lines: │ text │
        for (var line : lines) {
            var padded = String.format(" %-" + maxLen + "s ", line);
            println("│" + padded + "│");
        }

        // Bottom border: └───┘
        var bottom = new StringBuilder();
        bottom.append("└");
        bottom.append("─".repeat(boxWidth));
        bottom.append("┘");
        println(bottom.toString());
    }
}
