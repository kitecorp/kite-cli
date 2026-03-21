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

        // Get the root cause for user-facing exceptions
        Throwable cause = getRootCause(ex);

        // Check if this is a user-facing error (no stack trace needed)
        if (isUserFacingError(cause)) {
            String friendlyMessage = getUserFriendlyMessage(cause);
            err.println(cmd.getColorScheme().errorText("\u2717 " + friendlyMessage));

            // Show stack trace in debug mode even for user-facing errors
            if (isDebugEnabled()) {
                err.println();
                ex.printStackTrace(err);
            }
            return cmd.getCommandSpec().exitCodeOnExecutionException();
        }

        // Print error message
        err.println(cmd.getColorScheme().errorText("\u2717 " + ex.getMessage()));

        // Provide context-specific suggestions
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        if (message.contains("kitefile") || message.contains("kite.yaml")) {
            err.println();
            err.println("Suggestion: Run 'kite new' to create a new project.");
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
        if (isDebugEnabled()) {
            err.println();
            ex.printStackTrace(err);
        } else {
            err.println();
            err.println("Set KITE_LOGGING=DEBUG for detailed error information.");
        }

        return cmd.getCommandSpec().exitCodeOnExecutionException();
    }

    /**
     * Check if exception is a user-facing error that doesn't need a stack trace.
     * These are errors caused by user input, not bugs in the code.
     */
    private boolean isUserFacingError(Throwable ex) {
        if (ex == null) return false;

        String className = ex.getClass().getName();

        // Kite language/execution exceptions are user-facing
        if (className.startsWith("cloud.kitelang.execution.exceptions.")) {
            return true;
        }

        // Kite analysis exceptions are user-facing
        if (className.startsWith("cloud.kitelang.analysis.")) {
            return true;
        }

        // Semantic errors are user-facing
        if (className.startsWith("cloud.kitelang.semantics.")) {
            return true;
        }

        // Database connection errors are user-facing
        if (className.contains("Flyway") || className.contains("PSQLException") ||
            className.contains("SQLException") || className.contains("ConnectException")) {
            return true;
        }

        return false;
    }

    /**
     * Get a user-friendly message for common errors.
     */
    private String getUserFriendlyMessage(Throwable ex) {
        if (ex == null) return null;

        String message = ex.getMessage();
        if (message == null) return null;

        // Database connection refused
        if (message.contains("Connection") && message.contains("refused")) {
            // Extract host:port from message like "Connection to localhost:5432 refused"
            var matcher = java.util.regex.Pattern.compile("Connection to ([^\\s]+) refused").matcher(message);
            if (matcher.find()) {
                return "Cannot connect to database at " + matcher.group(1) + ". Is PostgreSQL running?";
            }
            return "Cannot connect to database. Is PostgreSQL running?";
        }

        // Authentication failure
        if (message.contains("password authentication failed") || message.contains("authentication failed")) {
            return "Database authentication failed. Check your credentials in kitefile.yml.";
        }

        // Database doesn't exist
        if (message.contains("does not exist") && message.contains("database")) {
            return "Database does not exist. Create it first or check the database name in kitefile.yml.";
        }

        return message;
    }

    /**
     * Check if debug logging is enabled.
     * Respects both KITE_LOGGING env var and kitefile.yml logs.verbosity
     * (the resolved level is applied to the JUL logger by KiteCLI.configureLogging).
     */
    private boolean isDebugEnabled() {
        // First check env var directly (fast path, always authoritative)
        var envLevel = System.getenv("KITE_LOGGING");
        if (envLevel != null) {
            return envLevel.equalsIgnoreCase("DEBUG") || envLevel.equalsIgnoreCase("TRACE");
        }

        // Fall back to the resolved logger level (set from kitefile.yml by configureLogging)
        var loggerLevel = java.util.logging.Logger.getLogger("cloud.kitelang").getLevel();
        return loggerLevel != null && loggerLevel.intValue() <= java.util.logging.Level.FINE.intValue();
    }

    /**
     * Get the root cause of an exception chain.
     */
    private Throwable getRootCause(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
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
            java.util.Map.entry("init", "new"),
            java.util.Map.entry("start", "new"),
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
