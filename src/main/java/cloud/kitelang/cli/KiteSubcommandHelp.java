package cloud.kitelang.cli;

import picocli.CommandLine;
import picocli.CommandLine.Model.UsageMessageSpec;

import java.util.ArrayList;

import static cloud.kitelang.cli.util.TerminalColors.*;

/**
 * Applies bun-style heading colors and section labels to all subcommands.
 *
 * <p>Transforms picocli's default subcommand help from plain text to a
 * styled layout with colored section headings, a version header, and
 * renamed sections ("Flags:" instead of default unnamed options list).</p>
 *
 * <p>Also reorders sections so the description appears right after the
 * header (before synopsis), matching bun's layout:</p>
 * <pre>
 * kite new (0.4.0)
 * Create a new Kite project
 *
 * Usage: kite new [name] [...flags]
 * </pre>
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
     * Reorders sections so description comes right after the header.
     */
    private static void styleCommand(CommandLine cmd) {
        var usage = cmd.getCommandSpec().usageMessage();
        var qualifiedName = cmd.getCommandSpec().qualifiedName(" ");
        var version = VersionProvider.getVersionString();

        // Header: "kite <command> (version)"
        usage.headerHeading(BOLD + MAGENTA + qualifiedName + RESET + " " + DIM + "(" + version + ")" + RESET + "%n%n");
        usage.descriptionHeading("");

        // Section headings with colors
        usage.synopsisHeading(BOLD + WHITE + "Usage: " + RESET);
        usage.parameterListHeading("%n" + BOLD + CYAN + "Arguments:" + RESET + "%n");
        usage.optionListHeading("%n" + BOLD + CYAN + "Flags:" + RESET + "%n");
        usage.commandListHeading("%n" + BOLD + CYAN + "Commands:" + RESET + "%n");

        // Reorder: move description before synopsis
        // Default order: header → synopsis → description → params → options → commands → footer
        // New order:     header → description → synopsis → params → options → commands → footer
        var keys = new ArrayList<>(cmd.getHelpSectionKeys());
        var descHeadingIdx = keys.indexOf(UsageMessageSpec.SECTION_KEY_DESCRIPTION_HEADING);
        var descIdx = keys.indexOf(UsageMessageSpec.SECTION_KEY_DESCRIPTION);
        var synopsisHeadingIdx = keys.indexOf(UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING);

        if (descHeadingIdx > synopsisHeadingIdx) {
            // Remove description entries from their current position
            keys.remove(UsageMessageSpec.SECTION_KEY_DESCRIPTION);
            keys.remove(UsageMessageSpec.SECTION_KEY_DESCRIPTION_HEADING);

            // Re-find synopsis heading index (shifted after removal)
            var insertIdx = keys.indexOf(UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING);

            // Insert description before synopsis
            keys.add(insertIdx, UsageMessageSpec.SECTION_KEY_DESCRIPTION);
            keys.add(insertIdx, UsageMessageSpec.SECTION_KEY_DESCRIPTION_HEADING);

            cmd.setHelpSectionKeys(keys);
        }
    }
}
