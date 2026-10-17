package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static cloud.kitelang.cli.TerminalText.withoutAnsi;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the bun-style usage help renderer.
 *
 * <p>Each test compares one whole block of the page, as the user sees it, with literal text. A
 * {@code contains()} check on a short string cannot fail: "env" is inside "environment", "new" inside
 * "Create a new Kite project". Blocks are found by their place on the page, so a block that moves
 * fails the test for the block now in its place.</p>
 */
class KiteUsageHelpTest {

    // Where each block sits on the page, counting from 0
    private static final int HEADER = 0;
    private static final int USAGE = 1;
    private static final int INFRASTRUCTURE = 2;
    private static final int CONFIGURATION = 3;
    private static final int UTILITY = 4;
    private static final int HELP_HINT = 5;
    private static final int FLAGS = 6;
    private static final int FOOTER = 7;

    @Test
    void headerNamesKiteWithItsTaglineAndVersion() {
        var header = blocks().get(HEADER).stream().map(TerminalText::withVersionMasked).toList();

        assertEquals(List.of(
                "Kite is a multi-cloud IaC tool. Write once, provision anywhere. (<version>)"
        ), header);
    }

    @Test
    void usageLineShowsWhereFlagsAndArgumentsGo() {
        assertEquals(List.of(
                "Usage: kite <command> [...flags] [...args]"
        ), blocks().get(USAGE));
    }

    @Test
    void infrastructureSectionListsEachCommandWithAnExample() {
        assertEquals(List.of(
                "Infrastructure:",
                "  new         my-project   Create a new Kite project",
                "  validate    ./src        Validate .kite files for syntax and schema errors",
                "  plan        -e dev       Preview infrastructure changes without applying",
                "  apply       -e prod      Apply infrastructure changes to provision resources",
                "  destroy     -e dev       Destroy provisioned infrastructure",
                "  output      my-stack     Display output values from deployed infrastructure"
        ), blocks().get(INFRASTRUCTURE));
    }

    @Test
    void configurationSectionListsEachCommandWithAnExample() {
        assertEquals(List.of(
                "Configuration:",
                "  fmt         ./src        Format .kite files to canonical style",
                "  env         dev          Show or switch the current environment",
                "  config      get <key>    Manage Kite CLI configuration",
                "  providers   install      Manage Kite providers"
        ), blocks().get(CONFIGURATION));
    }

    @Test
    void utilitySectionKeepsTheDescriptionColumnForCommandsWithoutAnExample() {
        // doctor and upgrade have no example, so their description must still start in its column
        assertEquals(List.of(
                "Utility:",
                "  doctor                   Check your Kite installation and project setup",
                "  version     list         Manage Kite CLI versions",
                "  upgrade                  Upgrade Kite CLI to the latest version",
                "  completion  bash         Generate shell completion scripts"
        ), blocks().get(UTILITY));
    }

    @Test
    void helpHintPointsAtEachCommandsOwnHelp() {
        assertEquals(List.of(
                "  <command>   --help       Print help text for command."
        ), blocks().get(HELP_HINT));
    }

    @Test
    void flagsSectionListsEachFlagWithItsShortForm() {
        // Whole rows, not substrings: "--version" contains "-v" and "--help" contains "-h",
        // so a contains() check cannot tell whether the short forms are listed at all.
        assertEquals(List.of(
                "Flags:",
                "  -h, --help                     Show this help message and exit",
                "  -v, --version                  Print version information and exit"
        ), blocks().get(FLAGS));
    }

    @Test
    void footerLinksToTheDocsAndEndsThePage() {
        var blocks = blocks();

        // Everything from the footer on, so a block added after it fails too
        assertEquals(List.of(List.of(
                "Learn more about Kite:           https://kitelang.cloud/docs"
        )), blocks.subList(FOOTER, blocks.size()));
    }

    /**
     * The page as the user sees it, split at its blank lines into blocks of lines, in order. The split
     * keeps empty pieces, so a doubled blank line shows up as a blank line at the start of a block.
     */
    private static List<List<String>> blocks() {
        return Arrays.stream(withoutAnsi(renderHelp()).split("\\R\\R", -1))
                .map(block -> block.lines().toList())
                .toList();
    }

    /**
     * Renders help to a string for assertion.
     */
    private static String renderHelp() {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        KiteUsageHelp.render(pw);
        return sw.toString();
    }
}
