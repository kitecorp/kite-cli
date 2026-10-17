package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static cloud.kitelang.cli.TerminalText.blocks;
import static cloud.kitelang.cli.TerminalText.withVersionMasked;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that KiteSubcommandHelp gives every subcommand's help its header, its section headings and
 * the description before the usage line.
 *
 * <p>Most tests style a small command tree of their own, so the expected pages hold only what
 * KiteSubcommandHelp adds, not the wording of kite's real commands. Each compares one whole block of
 * the page, without ANSI codes, with literal text; blocks are found by their place on the page.</p>
 */
class KiteSubcommandHelpTest {

    // Where each block of the deploy page sits, counting from 0
    private static final int HEADER = 0;
    private static final int DESCRIPTION_AND_USAGE = 1;
    private static final int ARGUMENTS = 2;
    private static final int FLAGS = 3;
    private static final int COMMANDS = 4;

    @Command(name = "kite", subcommands = Deploy.class)
    static class Root {
    }

    @Command(name = "deploy", description = "Deploy a stack", subcommands = Target.class)
    static class Deploy {
        @Parameters(paramLabel = "stack", description = "Stack to deploy")
        String stack;

        @Option(names = "--dry-run", description = "Show what would change")
        boolean dryRun;
    }

    @Command(name = "target", description = "Deploy one resource of a stack")
    static class Target {
        @Parameters(paramLabel = "resource", description = "Resource to deploy")
        String resource;
    }

    @Test
    void headerNamesTheCommandWithItsVersion() {
        var header = blocks(help("deploy")).get(HEADER).stream().map(TerminalText::withVersionMasked).toList();

        assertEquals(List.of("kite deploy (<version>)"), header);
    }

    @Test
    void descriptionComesBeforeTheUsageLine() {
        assertEquals(List.of(
                "Deploy a stack",
                "Usage: kite deploy [--dry-run] stack [COMMAND]"
        ), blocks(help("deploy")).get(DESCRIPTION_AND_USAGE));
    }

    @Test
    void argumentsSectionHasItsHeading() {
        assertEquals(List.of(
                "Arguments:",
                "      stack       Stack to deploy"
        ), blocks(help("deploy")).get(ARGUMENTS));
    }

    @Test
    void optionsAreListedUnderFlags() {
        assertEquals(List.of(
                "Flags:",
                "      --dry-run   Show what would change"
        ), blocks(help("deploy")).get(FLAGS));
    }

    @Test
    void subcommandsAreListedUnderCommandsAndEndThePage() {
        var blocks = blocks(help("deploy"));

        // Everything from the commands on, so a block added after them fails too
        assertEquals(List.of(List.of(
                "Commands:",
                "  target  Deploy one resource of a stack"
        )), blocks.subList(COMMANDS, blocks.size()));
    }

    @Test
    void nestedSubcommandIsStyledWithItsFullName() {
        var header = blocks(help("deploy", "target")).get(HEADER).stream().map(TerminalText::withVersionMasked).toList();

        assertEquals(List.of("kite deploy target (<version>)"), header);
    }

    @Test
    void everyKiteSubcommandsHelpStartsWithItsFullNameAndVersion() {
        // kite's own tree as the binary builds it, so a command left unstyled shows here
        var root = KiteCLI.commandLine();

        var headers = KiteCLITest.commandPaths()
                .filter(path -> !path.equals(KiteCLITest.CommandPath.ROOT))
                .map(path -> withVersionMasked(blocks(usage(path.in(root))).getFirst().getFirst()))
                .toList();

        assertEquals(List.of(
                "kite new (<version>)",
                "kite validate (<version>)",
                "kite plan (<version>)",
                "kite apply (<version>)",
                "kite destroy (<version>)",
                "kite output (<version>)",
                "kite fmt (<version>)",
                "kite env (<version>)",
                "kite providers (<version>)",
                "kite providers install (<version>)",
                "kite providers list (<version>)",
                "kite providers start (<version>)",
                "kite providers stop (<version>)",
                "kite providers status (<version>)",
                "kite config (<version>)",
                "kite config get (<version>)",
                "kite config set (<version>)",
                "kite config list (<version>)",
                "kite config path (<version>)",
                "kite config state (<version>)",
                "kite doctor (<version>)",
                "kite version (<version>)",
                "kite version list (<version>)",
                "kite version install (<version>)",
                "kite version use (<version>)",
                "kite version remove (<version>)",
                "kite upgrade (<version>)",
                "kite completion (<version>)"
        ), headers);
    }

    /** The help page of a command of the test's own tree, styled by KiteSubcommandHelp. */
    private static String help(String... commandPath) {
        var command = new CommandLine(new Root());
        KiteSubcommandHelp.apply(command);
        for (var name : commandPath) {
            command = command.getSubcommands().get(name);
        }
        return usage(command);
    }

    /** The help page a command prints. */
    private static String usage(CommandLine command) {
        var page = new StringWriter();
        command.usage(new PrintWriter(page));
        return page.toString();
    }
}
