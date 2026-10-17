package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static cloud.kitelang.cli.KiteCLITest.execute;
import static cloud.kitelang.cli.KiteCLITest.lines;
import static cloud.kitelang.cli.TerminalText.withoutAnsi;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs {@code kite fmt} through the root command on a file in a temporary directory, and reads what it
 * printed and what it left in the file.
 */
class FmtCommandTest {

    /** Needs one change: the property is neither indented nor spaced around '='. */
    private static final String UNFORMATTED = """
            resource Bucket logs {
            name="logs"
            }""";

    private static final String FORMATTED = """
            resource Bucket logs {
                name = "logs"
            }""";

    @TempDir
    private Path dir;

    @Test
    void diffShowsTheChangeAndWritesNothing() throws IOException {
        var file = kiteFile(UNFORMATTED);

        var run = execute(KiteCLI.commandLine(), "fmt", "--diff", dir.toString());

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(lines(
                        "Found 1 .kite file(s)",
                        "--- " + file,
                        "+++ " + file + " (formatted)",
                        "@@ -2 +2 @@",
                        "-name=\"logs\"",
                        "+    name = \"logs\"",
                        "",
                        "  Would format: " + file,
                        "",
                        "Would format: 1, Unchanged: 0, Failed: 0"), run.out(), "stdout"),
                () -> assertEquals("", run.err(), "stderr"),
                () -> assertEquals(UNFORMATTED, Files.readString(file), "file"));
    }

    @Test
    void noWriteCountsTheFilesThatWouldChange() throws IOException {
        var file = kiteFile(UNFORMATTED);

        var run = execute(KiteCLI.commandLine(), "fmt", "--no-write", dir.toString());

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(lines(
                        "Found 1 .kite file(s)",
                        "  Would format: " + file,
                        "",
                        "Would format: 1, Unchanged: 0, Failed: 0"), run.out(), "stdout"),
                () -> assertEquals("", run.err(), "stderr"),
                () -> assertEquals(UNFORMATTED, Files.readString(file), "file"));
    }

    @Test
    void writingFormatsTheFileAndCountsItFormatted() throws IOException {
        var file = kiteFile(UNFORMATTED);

        var run = execute(KiteCLI.commandLine(), "fmt", dir.toString());

        assertAll(
                () -> assertEquals(0, run.exitCode(), "exit code"),
                () -> assertEquals(lines(
                        "Found 1 .kite file(s)",
                        "  Formatted: " + file,
                        "",
                        "Formatted: 1, Unchanged: 0, Failed: 0"), run.out(), "stdout"),
                () -> assertEquals("", run.err(), "stderr"),
                () -> assertEquals(FORMATTED, Files.readString(file), "file"));
    }

    @Test
    void helpExamplesLineUp() {
        var run = execute(KiteCLI.commandLine(), "fmt", "--help");

        var examples = withoutAnsi(run.out()).lines().dropWhile(line -> !line.equals("Examples:")).toList();
        assertEquals(List.of(
                "Examples:",
                "  kite fmt                             Format all .kite files (recursive)",
                "  kite fmt resources/database.kite     Format a specific file",
                "  kite fmt components/                 Format a specific directory",
                "  kite fmt --check                     Check formatting (CI mode)",
                "  kite fmt --diff                      Preview changes without writing",
                "  kite fmt --indent 2                  Use 2-space indentation",
                "  kite fmt -r=false modules/           Format single directory only"
        ), examples);
    }

    /** Writes the content to a .kite file in the temporary directory. */
    private Path kiteFile(String content) throws IOException {
        return Files.writeString(dir.resolve("main.kite"), content);
    }
}
