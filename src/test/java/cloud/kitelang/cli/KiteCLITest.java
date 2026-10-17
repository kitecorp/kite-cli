package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs arguments through the root {@code kite} command, configured exactly as the shipped binary is.
 */
class KiteCLITest {

    /**
     * Stands in for the build-generated version. The real one changes with every release, so the
     * expected output can only stay a literal if the test supplies the version itself.
     */
    private static final String VERSION_LINE = "kite 9.9.9-test";

    /** picocli's exit code for invalid input. */
    private static final int USAGE_EXIT_CODE = 2;

    @ParameterizedTest(name = "kite {0}")
    @ValueSource(strings = {"-v", "--version"})
    void versionFlagPrintsTheVersionAndExitsZero(String flag) {
        var cli = KiteCLI.commandLine();
        cli.getCommandSpec().versionProvider(() -> new String[]{VERSION_LINE});

        var run = execute(cli, flag);

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(VERSION_LINE + System.lineSeparator(), run.out(), "stdout"),
                () -> assertEquals("", run.err(), "stderr"));
    }

    @Test
    void unknownShortOptionPrintsNoLongOptionHint() {
        var run = execute(KiteCLI.commandLine(), "-x");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-x'",
                        "",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void singleDashLongOptionSplitIntoShortOptionsHintsAtTheTokenTyped() {
        // picocli reads -recursive as validate's short option -r followed by "ecursive", and reports
        // only the part it could not match. The hint must still name the token the user typed.
        var run = execute(KiteCLI.commandLine(), "validate", "-recursive");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-ecursive' (while processing option: '-recursive')",
                        "",
                        "Hint: Use '--recursive' for long options",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void longOptionHintChecksTheSubcommandThatFailed() {
        // plan has --json and the root command does not, so only "kite plan -json" earns the hint.
        var underPlan = execute(KiteCLI.commandLine(), "plan", "-json");
        var underRoot = execute(KiteCLI.commandLine(), "-json");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, underPlan.exitCode(), "plan exit code"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-json'",
                        "",
                        "Hint: Use '--json' for long options",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), underPlan.err(), "plan stderr"),
                () -> assertEquals(USAGE_EXIT_CODE, underRoot.exitCode(), "root exit code"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-json'",
                        "",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), underRoot.err(), "root stderr"));
    }

    @Test
    void singleDashNegatedOptionHintsAtItsLongForm() {
        // --no-refresh exists only as the negated form of plan's --refresh.
        var run = execute(KiteCLI.commandLine(), "plan", "-no-refresh");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-no-refresh'",
                        "",
                        "Hint: Use '--no-refresh' for long options",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void longOptionHintFollowsTheCaseInsensitiveParser() {
        // kite matches options case-insensitively, so --JSON works and is a fair hint for -JSON.
        var run = execute(KiteCLI.commandLine(), "plan", "-JSON");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-JSON'",
                        "",
                        "Hint: Use '--JSON' for long options",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    /** What one run of {@code kite} printed and returned. */
    private record Run(int exitCode, String out, String err) {
    }

    /** Executes the arguments with stdout and stderr captured instead of printed. */
    private static Run execute(CommandLine cli, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        cli.setOut(new PrintWriter(out)).setErr(new PrintWriter(err));

        var exitCode = cli.execute(args);

        return new Run(exitCode, out.toString(), err.toString());
    }

    /** The text {@link PrintWriter#println} writes for these lines. */
    private static String lines(String... lines) {
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }
}
