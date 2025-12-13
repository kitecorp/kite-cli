package cloud.kitelang.cli;

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.UnmatchedArgumentException;

import java.io.PrintWriter;
import java.util.List;

/**
 * Custom exception handlers for the Kite CLI.
 * Provides user-friendly error messages with helpful suggestions.
 */
public class KiteExceptionHandler implements IParameterExceptionHandler, IExecutionExceptionHandler {

    /**
     * Handles parameter parsing exceptions (invalid arguments, missing values, etc.)
     */
    @Override
    public int handleParseException(ParameterException ex, String[] args) {
        CommandLine cmd = ex.getCommandLine();
        PrintWriter err = cmd.getErr();

        // Print error with color
        err.println(cmd.getColorScheme().errorText("Error: " + ex.getMessage()));
        err.println();

        // Handle specific error types with suggestions
        if (ex instanceof UnmatchedArgumentException unmatchedEx) {
            handleUnmatchedArgument(unmatchedEx, err, cmd);
        } else {
            // Generic suggestion
            printUsageHint(err, cmd);
        }

        return cmd.getCommandSpec().exitCodeOnInvalidInput();
    }

    /**
     * Handles execution exceptions (errors during command execution)
     */
    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
        PrintWriter err = cmd.getErr();

        // Print error message
        err.println(cmd.getColorScheme().errorText("Error: " + ex.getMessage()));

        // Provide context-specific suggestions
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        if (message.contains("kitefile") || message.contains("kite.yaml")) {
            err.println();
            err.println("Suggestion: Run 'kite init' to create a new project configuration.");
        } else if (message.contains("provider") && message.contains("not found")) {
            err.println();
            err.println("Suggestion: Run 'kite providers install' to install required providers.");
        } else if (message.contains("permission denied")) {
            err.println();
            err.println("Suggestion: Check file permissions or run with elevated privileges.");
        } else if (message.contains("connection") || message.contains("network")) {
            err.println();
            err.println("Suggestion: Check your network connection and try again.");
        }

        // Print stack trace in debug mode
        if (System.getenv("KITE_DEBUG") != null) {
            err.println();
            ex.printStackTrace(err);
        } else {
            err.println();
            err.println("Set KITE_DEBUG=1 for detailed error information.");
        }

        return cmd.getCommandSpec().exitCodeOnExecutionException();
    }

    /**
     * Handles unmatched arguments with suggestions.
     */
    private void handleUnmatchedArgument(UnmatchedArgumentException ex, PrintWriter err, CommandLine cmd) {
        List<String> unmatched = ex.getUnmatched();

        if (!unmatched.isEmpty()) {
            String arg = unmatched.getFirst();

            // Check for common mistakes
            if (arg.startsWith("-") && !arg.startsWith("--")) {
                // Single dash with long option
                err.println("Hint: Use '--" + arg.substring(1) + "' for long options");
            }

            // Picocli already provides "Did you mean?" suggestions
            // Let's also add some common alternatives
            suggestAlternatives(arg, err, cmd);
        }

        printUsageHint(err, cmd);
    }

    /**
     * Suggests alternatives for common typos.
     */
    private void suggestAlternatives(String arg, PrintWriter err, CommandLine cmd) {
        // Common typos and their corrections
        var suggestions = java.util.Map.ofEntries(
            java.util.Map.entry("create", "init"),
            java.util.Map.entry("new", "init"),
            java.util.Map.entry("start", "init"),
            java.util.Map.entry("run", "apply"),
            java.util.Map.entry("deploy", "apply"),
            java.util.Map.entry("remove", "destroy"),
            java.util.Map.entry("delete", "destroy"),
            java.util.Map.entry("teardown", "destroy"),
            java.util.Map.entry("check", "validate"),
            java.util.Map.entry("verify", "validate"),
            java.util.Map.entry("lint", "validate"),
            java.util.Map.entry("status", "plan"),
            java.util.Map.entry("diff", "plan"),
            java.util.Map.entry("preview", "plan"),
            java.util.Map.entry("install", "providers install"),
            java.util.Map.entry("get", "providers install")
        );

        String lowerArg = arg.toLowerCase().replace("-", "");
        if (suggestions.containsKey(lowerArg)) {
            err.println();
            err.println("Did you mean: kite " + suggestions.get(lowerArg) + "?");
        }
    }

    /**
     * Prints usage hint.
     */
    private void printUsageHint(PrintWriter err, CommandLine cmd) {
        err.println();
        err.println("Run 'kite --help' for usage information.");
        err.println("Run 'kite <command> --help' for command-specific help.");
    }
}
