package cloud.kitelang.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Colour codes reach only a terminal, and none at all when {@code NO_COLOR} is set (no-color.org):
 * {@code kite --help > help.txt} must hold plain text (kitecorp/kite-cli#20). Runs the installed binary,
 * on pipes or on a pseudo-terminal.
 */
class ColorTest {

    /** The root help as it reads without colour, the build version masked. */
    private static final String ROOT_HELP = """
            Kite is a multi-cloud IaC tool. Write once, provision anywhere. (<version>)

            Usage: kite <command> [...flags] [...args]

            Infrastructure:
              new         my-project   Create a new Kite project
              validate    ./src        Validate .kite files for syntax and schema errors
              plan        -e dev       Preview infrastructure changes without applying
              apply       -e prod      Apply infrastructure changes to provision resources
              destroy     -e dev       Destroy provisioned infrastructure
              output      my-stack     Display output values from deployed infrastructure

            Configuration:
              fmt         ./src        Format .kite files to canonical style
              env         dev          Show or switch the current environment
              config      get <key>    Manage Kite CLI configuration
              providers   install      Manage Kite providers

            Utility:
              doctor                   Check your Kite installation and project setup
              version     list         Manage Kite CLI versions
              upgrade                  Upgrade Kite CLI to the latest version
              completion  bash         Generate shell completion scripts

              <command>   --help       Print help text for command.

            Flags:
              -h, --help                     Show this help message and exit
              -v, --version                  Print version information and exit

            Learn more about Kite:           https://kitelang.cloud/docs
            """;

    /**
     * {@code kite plan --help} as it reads without colour, the build version masked. picocli measures
     * the "Usage: " heading to indent the wrapped synopsis; without the heading's colour codes that
     * indent is the width of "Usage: kite plan ", where on a terminal the codes widen it.
     */
    private static final String PLAN_HELP = """
            kite plan (<version>)

            Preview infrastructure changes without applying them

            Compares your .kite files (desired state) with the current state
            stored in the state backend. Shows what would be created, updated,
            or destroyed if you run 'kite apply'.

            Resource changes are shown as:
              + create    New resource to be created
              ~ update    Existing resource to be modified
              - destroy   Existing resource to be removed
              ~ replace   Resource must be destroyed and recreated
            Usage: kite plan [--compact] [-h] [--json] [--[no-]refresh] [-v] [-e=env]
                             [-f=file] [-o=file] [-p=provider] [-s=stack] [--target=name[,
                             name...]]...

            Flags:
                  --compact             Show compact diff output
              -e, --environment=env     Target environment (dev, staging, prod)
              -f, --file=file           Override: plan a specific .kite file instead of
                                          environment stacks
              -h, --help                Show this help message and exit
                  --json                Output plan as JSON
              -o, --out=file            Save plan to file for later apply
              -p, --provider=provider   Target cloud provider (aws, gcp, azure, or 'all')
                  --[no-]refresh        Refresh state before planning
              -s, --stack=stack         Specific stack to plan (e.g., Backend, Frontend).
                                          If omitted, plans all stacks in the environment.
                  --target=name[,name...]
                                        Plan only for specific resource(s)
              -v, --version             Print version information and exit

            Examples:
              kite plan                             Plan changes for dev environment
              kite plan -e prod                     Plan changes for production
              kite plan -e dev -s Backend           Plan only the Backend stack
              kite plan -e dev -p aws               Plan only AWS resources
              kite plan -e dev --json               Output plan as JSON
              kite plan -e dev -o plan.json         Save plan to file for later apply
              kite plan -e dev --compact            Show compact diff output
            """;

    @TempDir
    private Path temp;

    private KiteBinary binary;

    @BeforeEach
    void createHome() throws IOException {
        binary = new KiteBinary(Files.createDirectories(temp.resolve("home")));
    }

    /** Each help page and its text without colour. */
    static Stream<Arguments> helpPages() {
        return Stream.of(
                Arguments.of(List.of("--help"), ROOT_HELP),
                Arguments.of(List.of("plan", "--help"), PLAN_HELP));
    }

    @ParameterizedTest(name = "kite {0}")
    @MethodSource("helpPages")
    void helpHasNoColorWhenNotWrittenToATerminal(List<String> args, String plainText) throws Exception {
        var run = binary.runWithoutTerminal(temp, args.toArray(String[]::new));

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(plainText, withVersionMasked(run.stdout()), "stdout"));
    }

    @ParameterizedTest(name = "kite {0}")
    @MethodSource("helpPages")
    void helpHasNoColorOnATerminalWhenNoColorIsSet(List<String> args, String plainText) throws Exception {
        var screen = onTerminal(Map.of("NO_COLOR", "1"), args.toArray(String[]::new));

        assertEquals(plainText, withVersionMasked(screen), "terminal");
    }

    /** Lines of each help page that carry colour on a terminal, as they did before colour was gated. */
    static Stream<Arguments> coloredLines() {
        return Stream.of(
                Arguments.of(List.of("--help"), Map.of(), Map.of(
                        0, "\u001B[1mKite\u001B[0m is a multi-cloud IaC tool. Write once, provision anywhere. \u001B[2m(<version>)\u001B[0m",
                        4, "\u001B[1m\u001B[35mInfrastructure:\u001B[0m",
                        27, "  \u001B[36m-h\u001B[0m, \u001B[36m--help\u001B[0m                     Show this help message and exit")),
                // NO_COLOR counts only when it holds something (no-color.org)
                Arguments.of(List.of("--help"), Map.of("NO_COLOR", ""), Map.of(
                        0, "\u001B[1mKite\u001B[0m is a multi-cloud IaC tool. Write once, provision anywhere. \u001B[2m(<version>)\u001B[0m")),
                Arguments.of(List.of("plan", "--help"), Map.of(), Map.of(
                        0, "\u001B[1m\u001B[35mkite plan\u001B[0m \u001B[2m(<version>)\u001B[0m",
                        17, "\u001B[1m\u001B[36mFlags:\u001B[0m",
                        // picocli's own colours for options and their values
                        19, "  \u001B[36m-e\u001B[39m\u001B[0m, \u001B[36m--environment\u001B[39m\u001B[0m=\u001B[3m\u001B[33menv\u001B[39m\u001B[23m\u001B[0m     Target environment (dev, staging, prod)")));
    }

    @ParameterizedTest(name = "kite {0} with {1}")
    @MethodSource("coloredLines")
    void helpKeepsItsColorOnATerminal(List<String> args, Map<String, String> environment,
                                      Map<Integer, String> expectedLines) throws Exception {
        var lines = withVersionMasked(onTerminal(environment, args.toArray(String[]::new))).lines().toList();

        assertAll(expectedLines.entrySet().stream().map(expected -> () -> assertEquals(expected.getValue(),
                expected.getKey() < lines.size() ? lines.get(expected.getKey()) : "", "line " + expected.getKey())));
    }

    @Test
    void consoleMessagesHaveNoColorWhenNotWrittenToATerminal() throws Exception {
        // Console's ✗, ✓ and ! marks are coloured on a terminal. Here plan stops at once: this project
        // has no state backend.
        var project = Files.createDirectories(temp.resolve("bare-project"));
        var run = binary.runWithoutTerminal(project, "plan");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("""
                        ✗ No state configuration found for project 'bare-project' environment 'dev'

                        Configure state backend by running:
                          kite config state

                        Or use non-interactive mode:
                          kite config state -e dev --url jdbc:postgresql://localhost:5432/postgres?currentSchema=dev
                        """, run.stdout(), "stdout"));
    }

    /** The help headers end with the build version, which changes every release. */
    private static String withVersionMasked(String text) {
        return text.replaceAll("\\((\\d+\\.\\d+\\.\\d+[^()\\s]*)\\)", "(<version>)");
    }

    /** Runs kite with stdin, stdout and stderr all on one terminal, and returns what it showed there. */
    private String onTerminal(Map<String, String> environment, String... args) throws Exception {
        // The pseudo-terminal is not kite's controlling terminal, so /dev/tty does not exist for it. On
        // Linux picocli measures the help width with `stty -F /dev/tty`, which then fails and prints its
        // error on the terminal. The terminal is 80 columns, so the width is given instead.
        var withWidth = new HashMap<>(environment);
        withWidth.put("JAVA_OPTS", binary.javaOptions("UTF-8") + " -Dpicocli.usage.width=80");
        var builder = binary.builder(temp, withWidth, PseudoTerminal.sized(KiteBinary.kite(args)));
        try (var terminal = PseudoTerminal.open()) {
            var process = terminal.startOnTerminal(builder);
            try {
                if (!process.waitFor(KiteBinary.RUN_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
                        || !terminal.awaitClosed(KiteBinary.RUN_LIMIT)) {
                    fail("kite did not finish within " + KiteBinary.RUN_LIMIT + ". The terminal showed:\n" + terminal.screen());
                }
                assertEquals(0, process.exitValue(), "exit code");
                return terminal.screen();
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
