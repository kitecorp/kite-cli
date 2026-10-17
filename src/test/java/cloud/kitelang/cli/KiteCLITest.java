package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static cloud.kitelang.cli.TerminalText.withoutAnsi;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    /** What {@link #withoutRunning} returns in place of running a command, so it differs from any real exit code. */
    private static final int PARSED_EXIT_CODE = 99;

    /**
     * Whole-line patterns for the help and version rows, worded as the root help prints them. Each
     * command's help pads the flag names to its own option column (6 spaces after {@code --help} for
     * {@code config get}, 16 for {@code apply}, 21 for the root), so only that run of spaces is open.
     */
    private static final List<String> FLAG_ROWS = List.of(
            "  -h, --help +Show this help message and exit",
            "  -v, --version +Print version information and exit");

    /** A help or version row: an optional short form, then {@code --help} or {@code --version}. */
    private static final Pattern FLAG_ROW = Pattern.compile("\\s+(-\\S, )?--(help|version)\\b.*");

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("versionRequests")
    void versionFlagPrintsTheVersionOnEveryCommand(CommandPath command, String flag) {
        var cli = withStandInVersion(KiteCLI.commandLine());

        var run = execute(cli, command.argsWith(flag));

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(VERSION_LINE + System.lineSeparator(), run.out(), "stdout"),
                () -> assertEquals("", run.err(), "stderr"),
                () -> assertDidNotRun(command, cli, flag));
    }

    /**
     * Every command with each spelling of the version flag. {@code -V} is not a flag of its own: kite
     * matches options without regard to case, so it reaches {@code -v}.
     */
    static Stream<Arguments> versionRequests() {
        return commandPaths().flatMap(command -> Stream.of("-v", "--version", "-V")
                .map(flag -> Arguments.of(command, flag)));
    }

    @ParameterizedTest(name = "{0} -h and --help")
    @MethodSource("commandPaths")
    void helpFlagsPrintTheCommandsOwnHelpOnEveryCommand(CommandPath command) {
        var shortCli = KiteCLI.commandLine();
        var longCli = KiteCLI.commandLine();

        var shortFlag = execute(shortCli, command.argsWith("-h"));
        var longFlag = execute(longCli, command.argsWith("--help"));

        var flagRows = withoutAnsi(longFlag.out()).lines().filter(FLAG_ROW.asMatchPredicate()).toList();
        assertAll(
                () -> assertEquals(0, shortFlag.exitCode(), "-h exit code"),
                () -> assertEquals(0, longFlag.exitCode(), "--help exit code"),
                () -> assertEquals("", shortFlag.err(), "-h stderr"),
                () -> assertEquals("", longFlag.err(), "--help stderr"),
                () -> assertEquals(longFlag.out(), shortFlag.out(), "-h stdout is --help stdout"),
                () -> assertLinesMatch(FLAG_ROWS, flagRows, "help and version rows"),
                () -> assertDidNotRun(command, shortCli, "-h"),
                () -> assertDidNotRun(command, longCli, "--help"));
    }

    /**
     * Asserts that the flag answered before the command ran. Commands print through Console, not the
     * writer these tests capture, so stdout alone cannot show it. picocli keeps what a Callable's
     * call() returned, which stays null when a help or version flag answered first. A Runnable leaves
     * no such trace, so only the root, whose run() just prints the help, may be one.
     */
    private static void assertDidNotRun(CommandPath command, CommandLine root, String flag) {
        var commandLine = command.in(root);
        if (!command.equals(CommandPath.ROOT)) {
            assertInstanceOf(Callable.class, commandLine.getCommand(), command + " would leave no trace if it ran");
        }
        assertNull(commandLine.getExecutionResult(), command + " " + flag + " ran the command itself");
    }

    /**
     * Every command in the tree, found by walking it from the root, so a new command is covered
     * without editing this test. An alias maps to the same command object, so each is visited once.
     */
    static Stream<CommandPath> commandPaths() {
        return commandPaths(KiteCLI.commandLine(), CommandPath.ROOT);
    }

    private static Stream<CommandPath> commandPaths(CommandLine command, CommandPath path) {
        var below = command.getSubcommands().values().stream().distinct()
                .flatMap(sub -> commandPaths(sub, path.then(sub.getCommandName())));
        return Stream.concat(Stream.of(path), below);
    }

    /**
     * Puts the stand-in version wherever the CLI installed the build-generated one. A command the CLI
     * left without it keeps its own version text, or none, so the test still sees the gap.
     */
    private static CommandLine withStandInVersion(CommandLine root) {
        commandPaths(root, CommandPath.ROOT)
                .map(command -> command.in(root).getCommandSpec())
                .filter(spec -> spec.versionProvider() instanceof VersionProvider)
                .forEach(spec -> spec.versionProvider(() -> new String[]{VERSION_LINE}));
        return root;
    }

    /**
     * A command as the user reaches it: the subcommand names typed after {@code kite}.
     *
     * @param names subcommand names from the root down; empty for {@code kite} itself
     */
    record CommandPath(List<String> names) {

        /** {@code kite} itself. */
        static final CommandPath ROOT = new CommandPath(List.of());

        /** The subcommand of this command with the given name. */
        CommandPath then(String name) {
            return new CommandPath(Stream.concat(names.stream(), Stream.of(name)).toList());
        }

        /** The arguments that run this command with one flag. */
        String[] argsWith(String flag) {
            return then(flag).names().toArray(String[]::new);
        }

        /** This command's {@code CommandLine} within the given tree. */
        CommandLine in(CommandLine root) {
            var command = root;
            for (var name : names) {
                command = command.getSubcommands().get(name);
            }
            return command;
        }

        @Override
        public String toString() {
            return ("kite " + String.join(" ", names)).strip();
        }
    }

    @ParameterizedTest(name = "kite {0}")
    @MethodSource("rootHelpArguments")
    void rootHelpPrintsThroughTheCommandsOutput(List<String> args) {
        var run = execute(KiteCLI.commandLine(), args.toArray(String[]::new));

        var shown = withoutAnsi(run.out()).lines().toList();
        var firstAndLast = shown.isEmpty() ? List.of() : List.of(withVersionMasked(shown.getFirst()), shown.getLast());
        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(List.of(
                        "Kite is a multi-cloud IaC tool. Write once, provision anywhere. (<version>)",
                        "Learn more about Kite:           https://kitelang.cloud/docs"), firstAndLast, "stdout"),
                () -> assertEquals("", run.err(), "stderr"));
    }

    /** Every way to reach the root help: no arguments, and the root's own help flags. */
    static Stream<List<String>> rootHelpArguments() {
        return Stream.of(List.of(), List.of("--help"), List.of("-h"));
    }

    /**
     * The root help header ends with the build version, which changes every release. Masking it keeps
     * the rest of the header a literal.
     */
    private static String withVersionMasked(String header) {
        return header.replaceFirst("\\([^()]+\\)$", "(<version>)");
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
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void mistypedCommandSuggestsTheCommandMeant() {
        var run = execute(withoutRunning(KiteCLI.commandLine()), "deploy");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Unmatched argument at index 0: 'deploy'",
                        "",
                        "Did you mean: kite apply?",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void subcommandMissingARequiredOptionPrintsTheUsageHint() {
        // Not an unmatched argument, so the error takes the handler's other path
        var run = execute(withoutRunning(KiteCLI.commandLine()), "destroy");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Missing required option: '--environment=env'",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void singleDashLongOptionStartingWithAShortOptionIsReportedAsTyped() {
        // e is no flag of validate, so -recursive is no group. picocli's own clustering read it as -r
        // and reported the rest, "-ecursive". Both the error and the hint name the word typed.
        var run = execute(KiteCLI.commandLine(), "validate", "-recursive");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-recursive'",
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

    @ParameterizedTest(name = "kite {0}")
    @MethodSource("autoApproveSpellings")
    void everyAutoApproveSpellingTurnsOnAutoApproveAndNothingElse(List<String> args, Map<String, Object> expected) {
        var parsed = KiteCLI.commandLine().parseArgs(args.toArray(String[]::new)).subcommand();

        assertEquals(expected, matchedOptionValues(parsed));
    }

    /**
     * Each auto-approve spelling on apply and on destroy, with the options it must set. destroy requires
     * an environment, so its runs name one and expect it back unchanged.
     */
    static Stream<Arguments> autoApproveSpellings() {
        return Stream.of("-y", "--yes", "--auto", "--approve", "--auto-approve").flatMap(spelling -> Stream.of(
                Arguments.of(List.of("apply", spelling), Map.of("--auto-approve", true)),
                Arguments.of(List.of("destroy", "--environment", "dev", spelling),
                        Map.of("--environment", "dev", "--auto-approve", true))));
    }

    @ParameterizedTest(name = "kite {0} --help")
    @ValueSource(strings = {"apply", "destroy"})
    void helpListsEveryAutoApproveSpelling(String command) {
        var run = execute(KiteCLI.commandLine(), command, "--help");

        // The option row, not the footer examples that also mention --auto-approve
        var autoApproveRow = withoutAnsi(run.out()).lines().filter(line -> line.strip().startsWith("-y,")).toList();
        assertEquals(List.of("  -y, --yes, --auto, --approve, --auto-approve"), autoApproveRow);
    }

    @Test
    void singleDashYesIsTheGroupYesThatNeedsAWordForEachValueFlag() {
        // -yes is -y -e -s, and -e and -s take their values from the next words. It once was -y with -e
        // taking "s": an apply to environment "s" that nobody approved.
        var run = execute(withoutRunning(KiteCLI.commandLine()), "apply", "-yes");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Missing required parameter for option '-e' (env) in '-yes'",
                        "",
                        "Hint: Use '--yes' for long options",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void singleDashEnvironmentIsRejectedInsteadOfNamingAnEnvironment() {
        // n is no flag of plan, so -environment is no group. It once was -e taking "nvironment".
        var run = execute(withoutRunning(KiteCLI.commandLine()), "plan", "-environment", "prod");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Unknown options: '-environment', 'prod'",
                        "",
                        "Hint: Use '--environment' for long options",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @Test
    void valueAttachedToAShortFlagIsRejectedAsTyped() {
        // A short flag takes its value from the next word, and r is no flag of plan, so -eprod is no group
        var run = execute(withoutRunning(KiteCLI.commandLine()), "plan", "-eprod");

        assertAll(
                () -> assertEquals(USAGE_EXIT_CODE, run.exitCode(), "exit code"),
                () -> assertEquals("", run.out(), "stdout"),
                () -> assertEquals(lines(
                        "Error: Unknown option: '-eprod'",
                        "",
                        "Run 'kite --help' for usage information.",
                        "Run 'kite <command> --help' for command-specific help."), run.err(), "stderr"));
    }

    @ParameterizedTest(name = "kite {0}")
    @MethodSource("environmentSpellings")
    void everyCommandWithAnEnvironmentTakesItInEverySpelling(List<String> args, Map<String, Object> expected) {
        var parsed = KiteCLI.commandLine().parseArgs(args.toArray(String[]::new));

        assertEquals(expected, matchedOptionValues(lastSubcommand(parsed)));
    }

    /**
     * Each command that takes an environment, with every way to name it. {@code --env} is apply's and
     * new's own name, and an accepted abbreviation of {@code --environment} everywhere else.
     */
    static Stream<Arguments> environmentSpellings() {
        Map<List<String>, Object> commands = Map.of(
                List.of("apply"), "prod",
                List.of("destroy"), "prod",
                List.of("plan"), "prod",
                List.of("output"), "prod",
                List.of("doctor"), "prod",
                List.of("config", "state"), "prod",
                List.of("new"), List.of("prod"));
        var spellings = List.of(
                List.of("-e", "prod"), List.of("-e=prod"),
                List.of("--environment", "prod"), List.of("--environment=prod"),
                List.of("--env", "prod"));
        return commands.entrySet().stream().flatMap(command -> spellings.stream().map(spelling -> Arguments.of(
                Stream.concat(command.getKey().stream(), spelling.stream()).toList(),
                Map.of("--environment", command.getValue()))));
    }

    @ParameterizedTest(name = "kite {0}")
    @MethodSource("shortFlagGroups")
    void groupOfShortFlagsParsesLikeItsFlagsTypedSeparately(List<String> args, Map<String, Object> expected) {
        var parsed = KiteCLI.commandLine().parseArgs(args.toArray(String[]::new));

        assertEquals(expected, matchedOptionValues(lastSubcommand(parsed)));
    }

    /** Groups of short flags, where each flag that takes a value takes the next word, in order. */
    static Stream<Arguments> shortFlagGroups() {
        return Stream.of(
                Arguments.of(List.of("apply", "-ye", "dev"), Map.of("--auto-approve", true, "--environment", "dev")),
                Arguments.of(List.of("apply", "-ey", "dev"), Map.of("--auto-approve", true, "--environment", "dev")),
                Arguments.of(List.of("apply", "-es", "dev", "Backend"), Map.of("--environment", "dev", "--stack", "Backend")),
                Arguments.of(List.of("destroy", "-ye", "dev"), Map.of("--auto-approve", true, "--environment", "dev")),
                Arguments.of(List.of("new", "-fy"), Map.of("--force", true, "--yes", true)));
    }

    /** The command a parse ran down to: the last subcommand matched, or the root. */
    private static ParseResult lastSubcommand(ParseResult parsed) {
        var last = parsed;
        while (last.hasSubcommand()) {
            last = last.subcommand();
        }
        return last;
    }

    /**
     * The options a parse matched, by longest name, with the value each was given. An array value
     * becomes a list, so it compares by its elements.
     */
    private static Map<String, Object> matchedOptionValues(ParseResult parsed) {
        return parsed.matchedOptions().stream().collect(Collectors.toMap(OptionSpec::longestName,
                option -> option.getValue() instanceof Object[] values ? List.of(values) : option.getValue()));
    }

    /**
     * Stops a command whose arguments parsed from running, and returns {@link #PARSED_EXIT_CODE} instead.
     * A parse-error test on apply must never reach the engine, even while the parser wrongly accepts the input.
     */
    private static CommandLine withoutRunning(CommandLine cli) {
        return cli.setExecutionStrategy(parseResult -> PARSED_EXIT_CODE);
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
