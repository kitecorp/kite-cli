package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine.Model.OptionSpec;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boolean flags that are on unless the user turns them off. picocli sets a boolean option named
 * without a value to the opposite of its default, so such a flag turned itself off when named, and
 * with {@code negatable} alone its {@code --no-} form turned it on. Arguments are parsed through the
 * root command, configured as the shipped binary is; no command runs.
 */
class TrueDefaultFlagsTest {

    @ParameterizedTest(name = "kite {0}")
    @MethodSource("spellings")
    void eachSpellingLeavesTheSwitchesAsTheHelpSays(List<String> args, Map<String, Object> expected) {
        assertEquals(expected, onByDefaultSwitches(args));
    }

    /**
     * Each command with on-by-default switches: no flag, each spelling of each switch, and its
     * {@code --no-} form. {@code -r=false} is the fmt help's own example for one directory.
     */
    static Stream<Arguments> spellings() {
        return Stream.of(
                Arguments.of(List.of("fmt"), Map.of("--write", true, "--recursive", true)),
                Arguments.of(List.of("fmt", "--write"), Map.of("--write", true, "--recursive", true)),
                Arguments.of(List.of("fmt", "-w"), Map.of("--write", true, "--recursive", true)),
                Arguments.of(List.of("fmt", "--recursive"), Map.of("--write", true, "--recursive", true)),
                Arguments.of(List.of("fmt", "-r"), Map.of("--write", true, "--recursive", true)),
                Arguments.of(List.of("fmt", "--no-write"), Map.of("--write", false, "--recursive", true)),
                Arguments.of(List.of("fmt", "--no-recursive"), Map.of("--write", true, "--recursive", false)),
                Arguments.of(List.of("fmt", "-r=false"), Map.of("--write", true, "--recursive", false)),
                Arguments.of(List.of("validate"), Map.of("--recursive", true)),
                Arguments.of(List.of("validate", "--recursive"), Map.of("--recursive", true)),
                Arguments.of(List.of("validate", "-r"), Map.of("--recursive", true)),
                Arguments.of(List.of("validate", "--no-recursive"), Map.of("--recursive", false)),
                Arguments.of(List.of("plan"), Map.of("--refresh", true)),
                Arguments.of(List.of("plan", "--refresh"), Map.of("--refresh", true)),
                Arguments.of(List.of("plan", "--no-refresh"), Map.of("--refresh", false)));
    }

    @Test
    void everyOnByDefaultSwitchStaysOnWhenNamedAndHasANoForm() {
        // picocli's documented form for a negatable option that is true by default: negatable, with
        // defaultValue and fallbackValue both "true". Walking the tree covers a command added later.
        var root = KiteCLI.commandLine();

        var wrong = KiteCLITest.commandPaths()
                .flatMap(path -> path.in(root).getCommandSpec().options().stream()
                        .filter(TrueDefaultFlagsTest::isOnByDefault)
                        .filter(option -> !option.negatable() || !"true".equals(option.fallbackValue()))
                        .map(option -> path + " " + option.longestName()))
                .toList();

        assertEquals(List.of(), wrong);
    }

    /**
     * The on-by-default switches of the command the arguments reach, by longest name, with the value
     * each holds after parsing.
     */
    private static Map<String, Object> onByDefaultSwitches(List<String> args) {
        var parsed = KiteCLI.commandLine().parseArgs(args.toArray(String[]::new));
        var command = parsed.asCommandLineList().getLast().getCommandSpec();

        return command.options().stream()
                .filter(TrueDefaultFlagsTest::isOnByDefault)
                .collect(Collectors.toMap(OptionSpec::longestName, OptionSpec::getValue));
    }

    /** A boolean option that is true when the user does not name it. */
    private static boolean isOnByDefault(OptionSpec option) {
        return option.typeInfo().isBoolean()
                && ("true".equalsIgnoreCase(option.defaultValue()) || Boolean.TRUE.equals(option.initialValue()));
    }
}
