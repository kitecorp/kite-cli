package cloud.kitelang.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.MutuallyExclusiveArgsException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static cloud.kitelang.cli.TerminalText.withoutAnsi;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code KITE_AUTO_APPROVE} approves {@code kite apply} and {@code kite destroy} for CI, where there is no
 * terminal to ask on (kitecorp/kite-cli#27).
 *
 * <p>As in {@link NoTerminalApprovalTest}, the project's state backend cannot be reached, so the engine's
 * "Database:" line and the connection error show that a run went ahead, and their absence shows that it
 * stopped before the engine.</p>
 */
class AutoApproveVariableTest {

    private static final String NO_TERMINAL =
            "✗ No terminal to ask whether to apply, so nothing was changed. Add --yes to apply without asking.\n";

    @TempDir
    private Path temp;

    private KiteBinary binary;
    private Path project;

    @BeforeEach
    void createProject() throws IOException {
        binary = new KiteBinary(Files.createDirectories(temp.resolve("home")));
        project = binary.projectWithUnreachableState(temp, KiteBinary.WITH_PASSWORD);
    }

    @Test
    void oneApprovesApplyAndSaysWhereTheApprovalCameFrom() throws Exception {
        var run = binary.runWithoutTerminal(project, Map.of("KITE_AUTO_APPROVE", "1"), "apply");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("Applying: 1 file(s)\n", run.stdout(), "stdout"),
                () -> assertEquals("""
                        KITE_AUTO_APPROVE is set to 1, so apply goes ahead without asking.
                        INFO Applying infrastructure changes...
                        INFO Environment: dev
                        INFO Provider: all
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void trueApprovesDestroyAndSaysWhereTheApprovalCameFrom() throws Exception {
        var run = binary.runWithoutTerminal(project, Map.of("KITE_AUTO_APPROVE", "true"), "destroy", "-e", "dev");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals("""
                        KITE_AUTO_APPROVE is set to true, so destroy goes ahead without asking.
                        INFO Database: jdbc:postgresql://127.0.0.1:1/postgres
                        ✗ Cannot connect to database. Is PostgreSQL running?
                        """, withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void noAutoApproveTurnsTheVariableOffForOneRun() throws Exception {
        var run = binary.runWithoutTerminal(project, Map.of("KITE_AUTO_APPROVE", "1"), "apply", "--no-auto-approve");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals(NO_TERMINAL, withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void anyOtherValueLeavesApprovalOff() throws Exception {
        var run = binary.runWithoutTerminal(project, Map.of("KITE_AUTO_APPROVE", "yes"), "apply");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals(NO_TERMINAL, withoutAnsi(run.stderr()), "stderr"));
    }

    @Test
    void yesAndNoAutoApproveTogetherAreRejected() {
        var cli = KiteCLI.commandLine();

        var error = assertThrows(MutuallyExclusiveArgsException.class,
                () -> cli.parseArgs("apply", "--yes", "--no-auto-approve"));

        assertEquals("Error: --auto-approve, --no-auto-approve are mutually exclusive (specify only one)",
                error.getMessage());
    }
}
