package cloud.kitelang.cli.interactive;

import cloud.kitelang.cli.console.Console;
import lombok.extern.slf4j.Slf4j;
import org.jline.consoleui.prompt.CheckboxResult;
import org.jline.consoleui.prompt.ConsolePrompt;
import org.jline.consoleui.prompt.PromptResultItemIF;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.List;

/**
 * Wrapper around JLine ConsoleUI for interactive terminal prompts.
 * Provides single-select, multi-select, confirmation, and text input capabilities.
 */
@Slf4j
public class InteractivePrompt implements AutoCloseable {

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    private final Terminal terminal;
    private final ConsolePrompt.UiConfig config;

    /**
     * Represents an option in a selection prompt.
     */
    public record Option(String id, String label, String description, boolean defaultSelected) {
        public Option(String id, String label) {
            this(id, label, null, false);
        }

        public Option(String id, String label, String description) {
            this(id, label, description, false);
        }
    }

    private InteractivePrompt(Terminal terminal) {
        this.terminal = terminal;
        // Configure checkbox characters: [✓] for checked, [ ] for unchecked
        this.config = new ConsolePrompt.UiConfig(">", "[ ] ", "[✓] ", "[-] ");
    }

    /**
     * Creates a fresh ConsolePrompt for each operation.
     * This ensures the internal Display state is clean.
     */
    private ConsolePrompt createPrompt() {
        return new ConsolePrompt(terminal, config);
    }

    /**
     * Creates an InteractivePrompt instance using the shared Console terminal.
     * Returns null if terminal is not available (e.g., piped input).
     */
    public static InteractivePrompt create() {
        if (!Console.isInteractive()) {
            log.debug("Terminal not interactive, cannot create InteractivePrompt");
            return null;
        }

        var terminal = Console.getTerminal();
        if (terminal == null) {
            return null;
        }

        return new InteractivePrompt(terminal);
    }

    /**
     * Checks if the terminal supports interactive mode.
     */
    public static boolean isInteractive() {
        return Console.isInteractive();
    }

    /**
     * Displays a single-select list prompt with arrow key navigation.
     *
     * @param message The prompt message
     * @param options The list of options to choose from
     * @return The selected option's ID, or null if cancelled
     */
    public String selectOne(String message, List<Option> options) throws IOException {
        var prompt = createPrompt();
        var builder = prompt.getPromptBuilder();

        var listBuilder = builder.createListPrompt()
                .name("selection")
                .message(message);

        for (var option : options) {
            var itemBuilder = listBuilder.newItem(option.id()).text(option.label());
            if (option.description() != null) {
                // JLine doesn't have a direct description field, include in label if needed
            }
            itemBuilder.add();
        }

        listBuilder.addPrompt();

        var result = prompt.prompt(builder.build());
        var selection = result.get("selection");

        if (selection instanceof PromptResultItemIF item) {
            return item.getResult();
        }

        return null;
    }

    /**
     * Displays a multi-select checkbox prompt.
     *
     * @param message The prompt message
     * @param options The list of options to choose from
     * @return List of selected option IDs
     */
    public List<String> selectMany(String message, List<Option> options) throws IOException {
        var prompt = createPrompt();
        var builder = prompt.getPromptBuilder();

        var checkboxBuilder = builder.createCheckboxPrompt()
                .name("selections")
                .message(message);

        for (var option : options) {
            var itemBuilder = checkboxBuilder.newItem(option.id()).text(option.label());
            if (option.defaultSelected()) {
                itemBuilder.checked(true);
            }
            itemBuilder.add();
        }

        checkboxBuilder.addPrompt();

        var result = prompt.prompt(builder.build());
        var selections = result.get("selections");

        // CheckboxResult contains selected IDs as a Set
        if (selections instanceof CheckboxResult checkboxResult) {
            var selectedIds = checkboxResult.getSelectedIds();
            return selectedIds != null ? List.copyOf(selectedIds) : List.of();
        }

        return List.of();
    }

    /**
     * Displays a Yes/No confirmation prompt.
     *
     * @param message      The confirmation message
     * @param defaultValue The default value if user just presses Enter
     * @return true for Yes, false for No
     */
    public boolean confirm(String message, boolean defaultValue) throws IOException {
        var prompt = createPrompt();
        var builder = prompt.getPromptBuilder();

        var confirmBuilder = builder.createConfirmPromp()
                .name("confirm")
                .message(message + " (" + (defaultValue ? "Y/n" : "y/N") + ")");

        confirmBuilder.addPrompt();

        var result = prompt.prompt(builder.build());
        var confirm = result.get("confirm");

        if (confirm instanceof PromptResultItemIF item) {
            var answer = item.getResult();
            if (answer == null || answer.isEmpty()) {
                return defaultValue;
            }
            return "YES".equalsIgnoreCase(answer) || "Y".equalsIgnoreCase(answer);
        }

        return defaultValue;
    }

    /**
     * Displays a text input prompt.
     *
     * @param message      The prompt message
     * @param defaultValue The default value (shown in brackets)
     * @return The user's input, or the default value if empty
     */
    public String input(String message, String defaultValue) throws IOException {
        var prompt = createPrompt();
        var builder = prompt.getPromptBuilder();

        var inputBuilder = builder.createInputPrompt()
                .name("input")
                .message(message);

        if (defaultValue != null) {
            inputBuilder.defaultValue(defaultValue);
        }

        inputBuilder.addPrompt();

        var result = prompt.prompt(builder.build());
        var input = result.get("input");

        if (input instanceof PromptResultItemIF item) {
            var value = item.getResult();
            return (value == null || value.isEmpty()) ? defaultValue : value;
        }

        return defaultValue;
    }

    /**
     * Displays a password input prompt (input is masked).
     *
     * @param message The prompt message
     * @return The entered password
     */
    public String password(String message) throws IOException {
        var prompt = createPrompt();
        var builder = prompt.getPromptBuilder();

        builder.createInputPrompt()
                .name("password")
                .message(message)
                .mask('*')
                .addPrompt();

        var result = prompt.prompt(builder.build());
        var input = result.get("password");

        if (input instanceof PromptResultItemIF item) {
            return item.getResult();
        }

        return "";
    }

    /**
     * Prints a styled header.
     */
    public void printHeader(String title) {
        terminal.writer().println();
        terminal.writer().println(BOLD + CYAN + title + RESET);
        terminal.writer().println(CYAN + "=".repeat(title.length()) + RESET);
        terminal.writer().println();
        terminal.flush();
    }

    /**
     * Prints a success message with green checkmark.
     */
    public void printSuccess(String message) {
        terminal.writer().println(GREEN + "✓ " + RESET + message);
        terminal.flush();
    }

    /**
     * Prints an error message with red X mark.
     */
    public void printError(String message) {
        terminal.writer().println(RED + "✗ " + RESET + message);
        terminal.flush();
    }

    /**
     * Prints a warning message with yellow indicator.
     */
    public void printWarning(String message) {
        terminal.writer().println(YELLOW + "! " + RESET + message);
        terminal.flush();
    }

    /**
     * Prints an info message.
     */
    public void printInfo(String message) {
        terminal.writer().println(message);
        terminal.flush();
    }

    /**
     * Prints a separator line to visually separate sections and reset cursor.
     */
    public void printSeparator() {
        terminal.writer().println();
        terminal.flush();
    }

    /**
     * Prints a prompt/question indicator (cyan ?).
     */
    public void printPrompt(String message) {
        terminal.writer().println(CYAN + "? " + RESET + message);
        terminal.flush();
    }

    @Override
    public void close() {
        // Terminal is shared via Console, don't close it here
        // Just flush any pending output
        if (terminal != null) {
            terminal.flush();
        }
    }
}
