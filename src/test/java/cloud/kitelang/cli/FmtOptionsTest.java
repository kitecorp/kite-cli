package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parses {@code kite fmt} arguments through the root command, configured as the shipped binary is,
 * and reads the values the formatter would run with. Nothing is formatted.
 */
class FmtOptionsTest {

    /**
     * The two {@code kite fmt} switches that are on unless the user turns them off.
     *
     * @param write     whether formatted files are written back
     * @param recursive whether subdirectories are searched
     */
    record Switches(boolean write, boolean recursive) {
    }

    @Test
    void noFlagsWritesAndRecurses() {
        assertEquals(new Switches(true, true), parseFmt());
    }

    @ParameterizedTest(name = "kite fmt {0}")
    @MethodSource("spellings")
    void eachSpellingSetsItsSwitch(String flag, Switches expected) {
        assertEquals(expected, parseFmt(flag));
    }

    /**
     * Every spelling of the two switches. Naming a switch turns it on, as the help says; each has a
     * {@code --no-} form to turn it off. {@code -r=false} is the help's own example for one directory.
     */
    static Stream<Arguments> spellings() {
        return Stream.of(
                Arguments.of("--write", new Switches(true, true)),
                Arguments.of("-w", new Switches(true, true)),
                Arguments.of("--recursive", new Switches(true, true)),
                Arguments.of("-r", new Switches(true, true)),
                Arguments.of("--no-write", new Switches(false, true)),
                Arguments.of("--no-recursive", new Switches(true, false)),
                Arguments.of("-r=false", new Switches(true, false)));
    }

    /** The switch values after parsing {@code kite fmt} with these flags. */
    private static Switches parseFmt(String... flags) {
        var root = KiteCLI.commandLine();
        root.parseArgs(Stream.concat(Stream.of("fmt"), Stream.of(flags)).toArray(String[]::new));

        var fmt = root.getSubcommands().get("fmt").getCommandSpec();
        return new Switches(fmt.findOption("--write").getValue(), fmt.findOption("--recursive").getValue());
    }
}
