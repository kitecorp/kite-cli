package cloud.kitelang.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static cloud.kitelang.cli.TerminalText.withoutAnsi;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code kite apply} and {@code kite destroy} with no terminal at all, as in CI or a cron job: there is
 * no one to ask for approval, so without {@code --yes} they stop before they change anything
 * (kitecorp/kite-cli#23).
 *
 * <p>The project's state backend cannot be reached, and opening it is the first thing the engine does.
 * So the engine's "Database:" line and the connection error on stderr show that a run reached the
 * engine, and their absence shows that it did not, with no database or cloud involved.</p>
 */
class NoTerminalApprovalTest {

    @TempDir
    private Path temp;

    private KiteBinary binary;
    private Path project;

    @BeforeEach
    void createProject() throws IOException {
        binary = new KiteBinary(Files.createDirectories(temp.resolve("home")));
        project = binary.projectWithUnreachableState(temp, KiteBinary.WITH_PASSWORD);
    }

    @ParameterizedTest(name = "kite {0}")
    @CsvSource(delimiter = '|', textBlock = """
            apply         | apply
            destroy -e dev | destroy
            """)
    void stopsBeforeTheEngineWithoutYes(String command, String verb) throws Exception {
        var run = binary.runWithoutTerminal(project, command.split(" "));

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals("✗ No terminal to ask whether to %s, so nothing was changed. Add --yes to %s without asking.\n"
                        .formatted(verb, verb), withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void applyWithYesGoesAheadToTheEngine() throws Exception {
        var run = binary.runWithoutTerminal(project, "apply", "--yes");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("Applying: 1 file(s)\n", run.stdout(), "stdout"),
                () -> assertEquals("""
                        INFO Applying infrastructure changes...
                        INFO Environment: dev
                        INFO Provider: all
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void destroyWithYesGoesAheadToTheEngine() throws Exception {
        var run = binary.runWithoutTerminal(project, "destroy", "-e", "dev", "--yes");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals("""
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void destroyDryRunGoesAheadWithoutYes() throws Exception {
        // A dry run changes nothing, so it needs no approval: it reads state through the engine
        var run = binary.runWithoutTerminal(project, "destroy", "-e", "dev", "--dry-run");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals("""
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void applyDryRunGoesAheadWithoutYes() throws Exception {
        // apply's dry run stops before the engine on its own, and needs no approval either
        var run = binary.runWithoutTerminal(project, "apply", "--dry-run");

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals("""
                        Applying: 1 file(s)

                        DRY RUN: No changes will be made
                        Run 'kite plan --env dev' to see detailed changes
                        """, run.stdout(), "stdout"),
                () -> assertEquals("""
                        INFO Applying infrastructure changes...
                        INFO Environment: dev
                        INFO Provider: all
                        """, withoutAnsi(run.stderr()), "stderr"));
    }
}
