package cloud.kitelang.cli.console;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalExt;
import org.jline.terminal.spi.TerminalProvider;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;

import static cloud.kitelang.cli.util.TerminalColors.BOLD;
import static cloud.kitelang.cli.util.TerminalColors.CYAN;
import static cloud.kitelang.cli.util.TerminalColors.GREEN;
import static cloud.kitelang.cli.util.TerminalColors.RED;
import static cloud.kitelang.cli.util.TerminalColors.RESET;
import static cloud.kitelang.cli.util.TerminalColors.YELLOW;

/**
 * Central console output utility for consistent terminal rendering.
 *
 * <p>Output goes to stdout, so pipes and redirects capture it. Prompts use the JLine terminal, which is
 * stdout when stdout is a terminal and stderr when only stderr is, so prompts keep working while the
 * output is piped. Output is coloured only when it reaches a terminal ({@link #isColorEnabled()}).</p>
 */
public final class Console {

    /** True when the terminal is stdout itself, so output and prompts share the screen. */
    private static final boolean outputOnTerminal;
    /** Whether output carries colour codes; see {@link #isColorEnabled()}. */
    private static final boolean colorEnabled;
    private static final PrintWriter writer;

    static {
        // Suppress JLine warnings about dumb terminals
        Logger.getLogger("org.jline").setLevel(Level.SEVERE);

        // Only a stdout that is a terminal needs the terminal built now. Otherwise it is built when first
        // asked for, which `kite --version` or `kite completion zsh > _kite` never do.
        outputOnTerminal = mayBeTerminal(SystemStream.Output) && isOnStdout(getTerminal());
        // NO_COLOR counts when it holds anything, whatever the value (no-color.org)
        var noColor = System.getenv("NO_COLOR");
        colorEnabled = outputOnTerminal && (noColor == null || noColor.isEmpty());
        writer = outputOnTerminal ? getTerminal().writer() : stdoutWriter();
    }

    /** The JLine terminal, built on first use: building it costs more than the rest of a short command. */
    private static final class SystemTerminal {
        private static final Terminal INSTANCE = createTerminal();
    }

    /**
     * Asks JLine's FFM provider whether the stream is a terminal: one isatty call, where building the
     * terminal also starts {@code test -t} processes for every stream that is not one. Where FFM is not
     * available (some native images) the answer is "maybe", and the built terminal decides.
     */
    private static boolean mayBeTerminal(SystemStream stream) {
        try {
            return TerminalProvider.load("ffm").isSystemStream(stream);
        } catch (Exception | LinkageError e) {
            return true;
        }
    }

    /**
     * JLine's system terminal is stdout when stdout is a terminal, and stderr when only stderr is
     * ({@code kite plan | less}). Only in the first case may output go through the terminal's writer.
     */
    private static boolean isOnStdout(Terminal terminal) {
        return terminal instanceof TerminalExt system && system.getSystemStream() == SystemStream.Output;
    }

    /**
     * Writes to file descriptor 1 directly, as the terminal's own writer does when it is stdout. It uses
     * stdout.encoding, the encoding the JDK writes standard output in, so Console lines and the lines
     * the engine prints itself match in one file even where stdout and stderr have different encodings
     * (Windows code pages).
     */
    private static PrintWriter stdoutWriter() {
        var name = System.getProperty("stdout.encoding");
        var encoding = name != null ? Charset.forName(name, Charset.defaultCharset()) : Charset.defaultCharset();
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), encoding), true);
    }

    /**
     * Creates terminal with fallback strategies for native images.
     */
    private static Terminal createTerminal() {
        // Try FFM/JNA first (preferred for full functionality)
        try {
            var term = TerminalBuilder.builder()
                    .system(true)
                    .nativeSignals(true)
                    .build();
            if (term != null && !Terminal.TYPE_DUMB.equals(term.getType())) {
                return term;
            }
        } catch (Exception e) {
            // Fall through to exec provider
        }

        // Try exec provider (uses stty command, works in native images)
        try {
            var term = TerminalBuilder.builder()
                    .system(true)
                    .exec(true)
                    .nativeSignals(false)
                    .build();
            if (term != null && !Terminal.TYPE_DUMB.equals(term.getType())) {
                return term;
            }
        } catch (Exception e) {
            // Fall through to dumb terminal
        }

        // Last resort: accept whatever JLine makes, dumb included. dumb(true) allows a dumb terminal
        // but does not force one, so this is still a system terminal whenever JLine can make one
        try {
            return TerminalBuilder.builder()
                    .dumb(true)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private Console() {
        // Utility class
    }

    /**
     * The writer every Console method prints through, for code that takes a {@link PrintWriter}
     * instead, such as picocli's help and version output.
     */
    public static PrintWriter out() {
        return writer;
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
        println(errorLine(message));
    }

    /**
     * Returns the line {@link #error} prints, for printing it to another writer.
     */
    public static String errorLine(String message) {
        return RED + "\u2717 " + RESET + message;
    }

    /**
     * Prints a warning message with yellow indicator.
     */
    public static void warning(String message) {
        println(warningLine(message));
    }

    /**
     * Returns the line {@link #warning} prints, for printing it to another writer.
     */
    public static String warningLine(String message) {
        return YELLOW + "! " + RESET + message;
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
     * Returns the shared JLine terminal instance.
     * Use this to avoid creating multiple terminals.
     */
    public static Terminal getTerminal() {
        return SystemTerminal.INSTANCE;
    }

    /**
     * Prints the text of a plain-line prompt as is, on the terminal rather than with the output, so it
     * stays in front of the user while stdout is piped. Arrow-key prompts use {@code InteractivePrompt}.
     */
    public static void prompt(String text) {
        var terminal = getTerminal();
        var promptWriter = terminal != null ? terminal.writer() : writer;
        promptWriter.print(text);
        promptWriter.flush();
    }

    /**
     * Checks if the terminal supports interactive mode, which prompts need. It still does while stdout
     * is piped, through the terminal on stderr.
     */
    public static boolean isInteractive() {
        var terminal = getTerminal();
        return terminal != null
                && terminal.getType() != null
                && !terminal.getType().equals(Terminal.TYPE_DUMB)
                && terminal.getWidth() > 0;
    }

    /**
     * Checks if Console output reaches an interactive terminal, so it can move the cursor to redraw
     * lines. False while stdout is piped or redirected: cursor movement would only litter the file.
     */
    public static boolean isOutputInteractive() {
        return outputOnTerminal && isInteractive();
    }

    /**
     * Checks if output may carry colour codes: only when it is written to a terminal, and never while
     * {@code NO_COLOR} is set to anything (no-color.org). {@code kite --help > help.txt} stays plain text.
     */
    public static boolean isColorEnabled() {
        return colorEnabled;
    }

    /** Terminal columns, or 80 when there is no terminal to ask. */
    public static int width() {
        var terminal = getTerminal();
        return terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 80;
    }

    /** Terminal rows, or 24 when there is no terminal to ask. */
    public static int height() {
        var terminal = getTerminal();
        return terminal != null && terminal.getHeight() > 0 ? terminal.getHeight() : 24;
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
