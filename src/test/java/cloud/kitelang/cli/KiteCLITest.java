package cloud.kitelang.cli;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest(name = "kite {0}")
    @ValueSource(strings = {"-v", "--version"})
    void versionFlagPrintsTheVersionAndExitsZero(String flag) {
        var out = new StringWriter();
        var err = new StringWriter();
        var cli = KiteCLI.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        cli.getCommandSpec().versionProvider(() -> new String[]{VERSION_LINE});

        var exitCode = cli.execute(flag);

        assertAll(
                () -> assertEquals(0, exitCode, "exit code"),
                () -> assertEquals(VERSION_LINE + System.lineSeparator(), out.toString(), "stdout"),
                () -> assertEquals("", err.toString(), "stderr"));
    }
}
