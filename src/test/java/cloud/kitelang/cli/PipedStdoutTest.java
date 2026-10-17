package cloud.kitelang.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static cloud.kitelang.cli.TerminalText.withoutAnsi;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the installed {@code kite} binary the way a shell runs {@code kite ... | cat} from a terminal:
 * stdout is a pipe, while stdin and stderr are the terminal. Output belongs on stdout, so pipes and
 * redirects capture it, and prompts belong on the terminal, so they still reach the user
 * (kitecorp/kite-cli#17).
 */
class PipedStdoutTest {

    /**
     * A new pseudo-terminal has no size, and JLine counts a terminal 0 columns wide as unable to
     * prompt. Real terminals have one, so the child sets it on its stdin before kite starts.
     */
    private static final String WITH_TERMINAL_SIZE = "stty rows 24 cols 80 && exec \"$0\" \"$@\"";

    @TempDir
    private Path temp;

    private KiteBinary binary;

    @BeforeEach
    void createHome() throws IOException {
        binary = new KiteBinary(Files.createDirectories(temp.resolve("home")));
    }

    @Test
    void helpGoesToStdout() throws Exception {
        var run = kite(temp, "--help");

        var shown = withoutAnsi(run.stdout()).lines().toList();
        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(List.of(
                        "Kite is a multi-cloud IaC tool. Write once, provision anywhere. (<version>)",
                        "Learn more about Kite:           https://kitelang.cloud/docs"), firstAndLast(shown), "stdout"),
                () -> assertEquals("", run.screen(), "terminal"));
    }

    @Test
    void versionGoesToStdout() throws Exception {
        var run = kite(temp, "--version");

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals("kite <version>\n", run.stdout().replaceFirst("^kite \\S+", "kite <version>"), "stdout"),
                () -> assertEquals("", run.screen(), "terminal"));
    }

    @ParameterizedTest(name = "kite completion {0}")
    @CsvSource(delimiter = '|', textBlock = """
            bash | #!/usr/bin/env bash
            zsh  | #compdef kite
            fish | # Fish completion for kite
            """)
    void completionScriptGoesToStdout(String shell, String firstLine) throws Exception {
        // `kite completion zsh > ~/.zfunc/_kite` installs the script, so it must reach stdout
        var run = kite(temp, "completion", shell);

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(firstLine, run.stdout().lines().findFirst().orElse(""), "first line on stdout"),
                () -> assertEquals("", run.screen(), "terminal"));
    }

    @Test
    void planPrintsItsProgressOnStdout() throws Exception {
        var run = kite(planProject(), "plan");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("Planning: 1 file(s)\n\nRefreshing state...\n", run.stdout(), "stdout"),
                // Only logging and the error, which belong on stderr
                () -> assertEquals("""
                        INFO Creating execution plan...
                        INFO Environment: dev
                        INFO Provider: all
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.screen()), "terminal"));
    }

    @Test
    void planJsonKeepsItsProgressOffStdout() throws Exception {
        // With --json, plan's own lines go to stderr, so `kite plan --json > plan.json` gets the JSON
        // document from plan and nothing else. This run stops at the database before the document, so
        // stdout stays empty.
        var run = kite(planProject(), "plan", "--json");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals("""
                        INFO Creating execution plan...
                        INFO Environment: dev
                        INFO Provider: all
                        Planning: 1 file(s)

                        Refreshing state...
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.screen()), "terminal"));
    }

    @Test
    void planJsonKeepsItsWarningsOffStdout() throws Exception {
        var run = kite(planProjectWithoutPassword(), "plan", "--json");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals("""
                        INFO Creating execution plan...
                        INFO Environment: dev
                        INFO Provider: all
                        ! No password configured for environment 'dev'
                        Set password via:
                          - Config: kite config state -e dev --password
                          - Env var: export KITE__PIPED_PROJECT__DEV__DB_PASSWORD=<password>

                        Planning: 1 file(s)

                        Refreshing state...
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.screen()), "terminal"));
    }

    @Test
    void applyWithATerminalGoesAheadToAskWithoutYes() throws Exception {
        // Approval needs a terminal, not a terminal on stdout: with one behind stderr, apply goes on to
        // the engine (which stops at the database here) instead of refusing for lack of --yes
        var run = kite(planProject(), "apply");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("Applying: 1 file(s)\n", run.stdout(), "stdout"),
                () -> assertEquals("""
                        INFO Applying infrastructure changes...
                        INFO Environment: dev
                        INFO Provider: all
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.screen()), "terminal"));
    }

    @ParameterizedTest(name = "stderr in {0}")
    @ValueSource(strings = {"UTF-8", "ISO-8859-1"})
    void providerInstallPrintsPlainLinesWhenStdoutIsPiped(String stderrEncoding) throws Exception {
        // The progress bar redraws itself only on a terminal. Piped, it prints each final line once, with
        // no cursor movement for the file or pipe to keep. This install fails at once, offline.
        // The second run gives stderr another encoding, as Windows does for a console beside a
        // redirected stdout: stdout must still be written in its own, or ✗ and … turn into ?.
        var run = kite(temp, Map.of("JAVA_OPTS", binary.javaOptions(stderrEncoding)),
                "providers", "install", "foo@1.0", "--git", "file:///nonexistent/kite-test-repo");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("""
                        Installing provider: foo@v1.0
                          foo  failed ✗ Failed to install foo from git file:///nonexistent/kite-test-r…
                        """, run.stdout(), "stdout"),
                () -> assertEquals("", run.screen(), "terminal"));
    }

    @Test
    void newPromptsOnTheTerminalWhileItsOutputGoesToStdout() throws Exception {
        try (var session = start(temp, "new", "my-app", "--providers", "aws", "--env", "dev", "--skip-checks")) {
            // The state backend question is the first prompt. Ctrl-E moves down the list: twice reaches
            // "Skip for now", and Enter picks it.
            session.awaitScreen("Skip for now");
            session.type("\u0005\u0005\r");
            var run = session.finish();

            var stdout = withoutAnsi(run.stdout()).lines().toList();
            assertAll(
                    () -> assertEquals(0, run.exitCode(), "exit code"),
                    () -> assertEquals(List.of("", "", ""), stdout.subList(0, Math.min(3, stdout.size())),
                            "stdout before the project summary"),
                    () -> assertEquals(List.of(
                            "✓ Project created successfully",
                            "",
                            "Next steps:",
                            "  cd my-app",
                            "  kite config state        # Configure state backend (if not done)",
                            "  kite providers install   # Install providers",
                            "  kite validate            # Check configuration",
                            "  kite plan                # Preview changes",
                            "  kite apply               # Provision resources"),
                            stdout.subList(Math.max(0, stdout.size() - 9), stdout.size()), "stdout after the project summary"),
                    () -> assertTrue(withoutAnsi(run.screen()).contains(
                                    "Skipped state configuration. You can configure it later with: kite config state"),
                            "terminal shows the prompt's answer:\n" + run.screen()),
                    () -> assertTrue(Files.isRegularFile(temp.resolve("my-app/kitefile.yml")), "project created"));
        }
    }

    @Test
    void newAsksOnTheTerminalWhenItCannotDrawPrompts() throws Exception {
        // On a terminal that cannot move the cursor (TERM=dumb), kite new asks with plain lines of text
        // instead. They are prompts too, so they belong on the terminal, not in the piped output.
        try (var session = start(temp, Map.of("TERM", "dumb"), "new", "my-app", "--env", "dev", "--skip-checks")) {
            session.awaitScreen("Providers [aws]: ");
            session.type("aws\r");
            var run = session.finish();

            var stdout = withoutAnsi(run.stdout()).lines().toList();
            assertAll(
                    () -> assertEquals(0, run.exitCode(), "exit code"),
                    // The terminal echoes what was typed, as it does for the user
                    () -> assertEquals("""
                            Kite Project Setup
                            ==================

                            Select cloud providers (comma-separated):
                              1. aws (Amazon Web Services)
                              2. gcp (Google Cloud Platform)
                              3. azure (Microsoft Azure)
                            Providers [aws]: aws
                            """, withoutAnsi(run.screen()), "terminal"),
                    () -> assertEquals(List.of(
                            "",
                            "! Interactive prompt not available",
                            "Run 'kite config state' to configure state backend.",
                            ""), stdout.subList(0, Math.min(4, stdout.size())), "stdout before the project summary"),
                    () -> assertEquals("  kite apply               # Provision resources",
                            stdout.isEmpty() ? "" : stdout.getLast(), "last line on stdout"));
        }
    }

    /** First and last line of the root help, with the build version in its header masked. */
    private static List<String> firstAndLast(List<String> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        return List.of(lines.getFirst().replaceFirst("\\([^()]+\\)$", "(<version>)"), lines.getLast());
    }

    /** A project plan gets through until the engine opens the database, which cannot be reached. */
    private Path planProject() throws IOException {
        return binary.projectWithUnreachableState(temp, KiteBinary.WITH_PASSWORD);
    }

    /** As {@link #planProject()}, with no password for the state backend, which plan warns about. */
    private Path planProjectWithoutPassword() throws IOException {
        return binary.projectWithUnreachableState(temp, KiteBinary.WITHOUT_PASSWORD);
    }

    private Run kite(Path workDir, String... args) throws Exception {
        return kite(workDir, Map.of(), args);
    }

    private Run kite(Path workDir, Map<String, String> environmentOverrides, String... args) throws Exception {
        try (var session = start(workDir, environmentOverrides, args)) {
            return session.finish();
        }
    }

    private Session start(Path workDir, String... args) throws IOException {
        return start(workDir, Map.of(), args);
    }

    private Session start(Path workDir, Map<String, String> environmentOverrides, String... args) throws IOException {
        var command = new ArrayList<>(List.of("/bin/sh", "-c", WITH_TERMINAL_SIZE));
        command.addAll(KiteBinary.kite(args));
        var builder = binary.builder(workDir, environmentOverrides, command);

        var terminal = PseudoTerminal.open();
        try {
            return new Session(terminal, terminal.start(builder));
        } catch (IOException e) {
            terminal.close();
            throw e;
        }
    }

    /** What one run of {@code kite} printed on each stream, and how it exited. */
    private record Run(int exitCode, String stdout, String screen) {
    }

    /** One running {@code kite}: stdout is a pipe, stdin and stderr are the pseudo-terminal. */
    private static final class Session implements AutoCloseable {

        private final PseudoTerminal terminal;
        private final Process process;
        private final CompletableFuture<String> stdout;

        Session(PseudoTerminal terminal, Process process) {
            this.terminal = terminal;
            this.process = process;
            this.stdout = KiteBinary.drain(process.getInputStream());
        }

        void awaitScreen(String text) throws InterruptedException {
            if (!terminal.awaitScreen(text, KiteBinary.RUN_LIMIT)) {
                fail("The terminal never showed '" + text + "'. It showed:\n" + terminal.screen());
            }
        }

        void type(String keys) throws IOException {
            terminal.type(keys);
        }

        Run finish() throws Exception {
            if (!process.waitFor(KiteBinary.RUN_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                fail("kite did not exit within " + KiteBinary.RUN_LIMIT + ". The terminal showed:\n" + terminal.screen());
            }
            if (!terminal.awaitClosed(KiteBinary.RUN_LIMIT)) {
                fail("Something kite started still holds the terminal open. It showed:\n" + terminal.screen());
            }
            return new Run(process.exitValue(), stdout.get(KiteBinary.RUN_LIMIT.toMillis(), TimeUnit.MILLISECONDS), terminal.screen());
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(KiteBinary.RUN_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
                // The reader sees the terminal close a moment after the process ends; until then the
                // terminal refuses to close and its descriptor would stay open
                terminal.awaitClosed(KiteBinary.RUN_LIMIT);
            }
            terminal.close();
        }
    }
}
