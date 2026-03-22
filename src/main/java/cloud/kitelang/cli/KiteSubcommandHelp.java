package cloud.kitelang.cli;

import picocli.CommandLine;

import static cloud.kitelang.cli.util.TerminalColors.*;

/**
 * Applies bun-style heading colors and section labels to all subcommands.
 *
 * <p>Transforms picocli's default subcommand help from plain text to a
 * styled layout with colored section headings, a version header, and
 * renamed sections ("Flags:" instead of default unnamed options list).</p>
 */
public final class KiteSubcommandHelp {

    /** Dim text (ANSI faint). */
    private static final String DIM = "\u001B[2m";

    private KiteSubcommandHelp() {
        // Utility class
    }

    /**
     * Recursively applies styled headings to all subcommands of the given root.
     *
     * @param root the root CommandLine (subcommands are styled, root is not)
     */
    public static void apply(CommandLine root) {
        for (var sub : root.getSubcommands().values()) {
            styleCommand(sub);
            apply(sub);
        }
    }

    /**
     * Styles a single command's usage help with colored headings and a version header.
     */
    private static void styleCommand(CommandLine cmd) {
        var usage = cmd.getCommandSpec().usageMessage();
        var qualifiedName = cmd.getCommandSpec().qualifiedName(" ");
        var version = VersionProvider.getVersionString();

        // Header: "kite <command> (version)"
        usage.headerHeading(BOLD + MAGENTA + qualifiedName + RESET + " " + DIM + "(" + version + ")" + RESET + "%n%n");

        // Section headings with colors
        usage.synopsisHeading(BOLD + WHITE + "Usage: " + RESET);
        usage.parameterListHeading("%n" + BOLD + CYAN + "Arguments:" + RESET + "%n");
        usage.optionListHeading("%n" + BOLD + CYAN + "Flags:" + RESET + "%n");
        usage.commandListHeading("%n" + BOLD + CYAN + "Commands:" + RESET + "%n");
    }
}
