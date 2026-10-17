package cloud.kitelang.cli;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine.Model.CommandSpec;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Expands groups of short flags against the command specs {@code kite} runs with, so each case reads
 * the flags a real command declares.
 */
class ShortFlagGroupsTest {

    @ParameterizedTest(name = "kite {0} {1}")
    @MethodSource("groups")
    void groupBecomesItsFlagsEachValueFlagTakingTheNextWord(String command, List<String> words, List<String> expected) {
        assertEquals(expected, ShortFlagGroups.expand(words, spec(command)));
    }

    static Stream<Arguments> groups() {
        return Stream.of(
                Arguments.of("fmt", List.of("-rw"), List.of("-r", "-w")),
                Arguments.of("apply", List.of("-ye", "dev"), List.of("-y", "-e", "dev")),
                Arguments.of("apply", List.of("-ey", "dev"), List.of("-e", "dev", "-y")),
                Arguments.of("apply", List.of("-es", "dev", "Backend"), List.of("-e", "dev", "-s", "Backend")),
                Arguments.of("apply", List.of("-yes", "dev", "Backend"), List.of("-y", "-e", "dev", "-s", "Backend")),
                Arguments.of("apply", List.of("--dry-run", "-ye", "dev", "-p", "aws"),
                        List.of("--dry-run", "-y", "-e", "dev", "-p", "aws")),
                Arguments.of("config state", List.of("-pe", "shop", "prod"), List.of("-p", "shop", "-e", "prod")));
    }

    @ParameterizedTest(name = "kite {0} {1}")
    @MethodSource("wordsLeftAsTyped")
    void wordThatIsNoGroupOfTheCommandsFlagsIsLeftAsTyped(String command, List<String> words) {
        assertEquals(words, ShortFlagGroups.expand(words, spec(command)));
    }

    static Stream<Arguments> wordsLeftAsTyped() {
        return Stream.of(
                // Letters that are no flag of plan (n, r), so picocli reports the word as typed
                Arguments.of("plan", List.of("-environment", "prod")),
                Arguments.of("plan", List.of("-eprod")),
                // A single flag, a value after '=', and long options need nothing
                Arguments.of("plan", List.of("-e", "prod", "-e=prod", "--environment=prod", "--json")),
                // The value of a value option is never read as flags
                Arguments.of("apply", List.of("--stack", "-ye")),
                // After "--" every word is a positional parameter
                Arguments.of("validate", List.of("--", "-rq")),
                // The root's own words end at the subcommand, which expands its words itself, even a
                // group of letters that are flags of the root as well
                Arguments.of("", List.of("apply", "-hv")));
    }

    @ParameterizedTest(name = "kite apply {0}")
    @MethodSource("groupsShortOfWords")
    void groupWithoutAWordForEachValueFlagIsRejectedNamingTheGroup(List<String> words, String expectedMessage) {
        var error = assertThrows(ShortFlagGroups.MissingValueException.class,
                () -> ShortFlagGroups.expand(words, spec("apply")));

        assertAll(
                () -> assertEquals(expectedMessage, error.getMessage(), "message"),
                () -> assertEquals("-yes", error.group(), "group"));
    }

    static Stream<Arguments> groupsShortOfWords() {
        return Stream.of(
                Arguments.of(List.of("-yes"), "Missing required parameter for option '-e' (env) in '-yes'"),
                Arguments.of(List.of("-yes", "dev"), "Missing required parameter for option '-s' (stack) in '-yes'"));
    }

    /**
     * The spec of a command in the tree {@code kite} runs with.
     *
     * @param path the subcommand names after {@code kite}, separated by spaces; empty for the root
     */
    private static CommandSpec spec(String path) {
        var command = KiteCLI.commandLine();
        for (var name : path.split(" ")) {
            if (!name.isEmpty()) {
                command = command.getSubcommands().get(name);
            }
        }
        return command.getCommandSpec();
    }
}
