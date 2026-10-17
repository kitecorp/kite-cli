package cloud.kitelang.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static cloud.kitelang.cli.TerminalText.withoutAnsi;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code kite destroy} of an environment that kitefile.yml marks {@code protected: true}
 * (kitecorp/kite-cli#28). With a terminal it asks for the environment's typed name; that prompt needs
 * resources in state, so {@code ApprovalTest} covers it. Here the installed binary shows that the mark
 * is read, and that {@code --yes} and the no-terminal stop of kitecorp/kite-cli#23 still apply.
 *
 * <p>As in {@link NoTerminalApprovalTest}, the state backend cannot be reached, so the engine's
 * "Database:" line shows that a run went ahead.</p>
 */
class ProtectedEnvironmentTest {

    @TempDir
    private Path temp;

    private KiteBinary binary;
    private Path project;

    @BeforeEach
    void createProject() throws IOException {
        binary = new KiteBinary(Files.createDirectories(temp.resolve("home")));
        project = binary.projectWithUnreachableState(temp, KiteBinary.WITH_PASSWORD);
        Files.writeString(project.resolve("kitefile.yml"), """
                environments:
                  - name: dev
                    protected: true
                """);
    }

    @Test
    void yesDestroysAProtectedEnvironmentWithoutAsking() throws Exception {
        // No warning about kitefile.yml: kite reads the protected mark instead of ignoring the whole file
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
    void protectedEnvironmentWithoutATerminalStopsBeforeTheEngine() throws Exception {
        var run = binary.runWithoutTerminal(project, "destroy", "-e", "dev");

        assertAll(
                () -> assertEquals(1, run.exitCode(), "exit code"),
                () -> assertEquals("", run.stdout(), "stdout"),
                () -> assertEquals(
                        "✗ No terminal to ask whether to destroy, so nothing was changed. Add --yes to destroy without asking.\n",
                        withoutAnsi(run.stderr()), "stderr"));
    }
}
