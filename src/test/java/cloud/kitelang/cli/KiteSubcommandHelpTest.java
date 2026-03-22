package cloud.kitelang.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that KiteSubcommandHelp applies styled headings to subcommands.
 */
class KiteSubcommandHelpTest {

    private CommandLine root;

    @BeforeEach
    void setUp() {
        root = new CommandLine(new KiteCLI());
        KiteSubcommandHelp.apply(root);
    }

    @Test
    void subcommandHelpContainsVersionHeader() {
        var help = renderSubcommandHelp("new");

        assertTrue(help.contains("kite new"), "Should show qualified command name");
    }

    @Test
    void subcommandHelpShowsFlagsHeading() {
        var help = renderSubcommandHelp("new");

        assertTrue(help.contains("Flags:"), "Should rename options to Flags:");
    }

    @Test
    void subcommandHelpShowsUsageHeading() {
        var help = renderSubcommandHelp("new");

        assertTrue(help.contains("Usage:"), "Should have Usage: heading");
    }

    @Test
    void parentCommandShowsCommandsHeading() {
        var help = renderSubcommandHelp("providers");

        assertTrue(help.contains("Commands:"), "Should show Commands: heading for parent");
    }

    @Test
    void nestedSubcommandShowsQualifiedName() {
        var help = renderSubcommandHelp("providers", "install");

        assertTrue(help.contains("kite providers install"),
                "Should show fully qualified name for nested subcommand");
    }

    @Test
    void nestedSubcommandHasFlagsHeading() {
        var help = renderSubcommandHelp("providers", "install");

        assertTrue(help.contains("Flags:"), "Nested subcommand should have Flags: heading");
    }

    /**
     * Renders help for a subcommand (or nested subcommand) to a string.
     */
    private String renderSubcommandHelp(String... commandPath) {
        var cmd = root;
        for (var name : commandPath) {
            cmd = cmd.getSubcommands().get(name);
            assertNotNull(cmd, "Subcommand '" + name + "' should exist");
        }
        var sw = new StringWriter();
        cmd.usage(new PrintWriter(sw));
        return sw.toString();
    }
}
