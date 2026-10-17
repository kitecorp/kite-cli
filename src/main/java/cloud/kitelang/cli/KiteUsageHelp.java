package cloud.kitelang.cli;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cloud.kitelang.cli.util.TerminalColors.*;

/**
 * Renders bun-style usage help for the Kite CLI root command.
 *
 * <p>Produces a clean, grouped layout with inline examples instead of
 * picocli's default wall-of-text help. Example output:</p>
 *
 * <pre>
 * Kite is a multi-cloud IaC tool. Write once, provision anywhere. (0.4.0)
 *
 * Usage: kite &lt;command&gt; [...flags] [...args]
 *
 * Commands:
 *   new       my-project           Create a new Kite project
 *   plan      -e dev               Preview infrastructure changes
 *   ...
 * </pre>
 */
public final class KiteUsageHelp {

    private KiteUsageHelp() {
        // Utility class
    }

    /**
     * A single command entry in the help output.
     *
     * @param name        command name (e.g. "new")
     * @param example     inline example argument (e.g. "my-project")
     * @param description short description
     */
    record CommandEntry(String name, String example, String description) {
    }

    /**
     * A group of related commands with a label and accent color.
     *
     * @param label   section label (e.g. "Infrastructure")
     * @param color   ANSI color code for this group's command names, empty without colour
     * @param entries commands in this group
     */
    record CommandGroup(String label, String color, List<CommandEntry> entries) {
    }

    /** The one section the root command's usage help consists of. */
    private static final String SECTION_KEY = "kiteUsageHelp";

    /**
     * Makes this page the root command's usage help, so picocli prints it for {@code kite -h} and
     * {@code kite --help}. Those flags are picocli help options because every subcommand inherits
     * them, so picocli, not {@code KiteCLI.run()}, answers them. Only the root changes: subcommands
     * keep picocli's own help.
     *
     * @param root the root command line
     */
    public static void apply(CommandLine root) {
        root.getCommandSpec().usageMessage()
                .sectionMap(Map.of(SECTION_KEY, help -> text()))
                .sectionKeys(List.of(SECTION_KEY));
    }

    /** The page {@link #render} writes, as text. */
    private static String text() {
        var text = new StringWriter();
        render(new PrintWriter(text));
        return text.toString();
    }

    /**
     * Builds the full bun-style help text and writes it to the given writer.
     *
     * @param out the writer to print to
     */
    public static void render(PrintWriter out) {
        var version = VersionProvider.getVersionString();

        // Header
        out.println(BOLD + "Kite" + RESET + " is a multi-cloud IaC tool. Write once, provision anywhere. "
                + DIM + "(" + version + ")" + RESET);
        out.println();

        // Usage line
        out.println(BOLD + "Usage:" + RESET + " kite <command> [...flags] [...args]");
        out.println();

        // Command groups
        var groups = buildCommandGroups();

        for (var group : groups) {
            out.println(BOLD + group.color() + group.label() + ":" + RESET);
            for (var entry : group.entries()) {
                out.printf("  " + group.color() + "%-12s" + RESET + DIM + "%-13s" + RESET + "%s%n",
                        entry.name(), entry.example(), entry.description());
            }
            out.println();
        }

        // Help hint
        out.printf("  " + DIM + "%-12s" + "%-13s" + RESET + "%s%n",
                "<command>", "--help", "Print help text for command.");
        out.println();

        // Flags
        out.println(BOLD + CYAN + "Flags:" + RESET);
        out.printf("  " + CYAN + "-h" + RESET + ", " + CYAN + "--help" + RESET
                + "                     Show this help message and exit%n");
        out.printf("  " + CYAN + "-v" + RESET + ", " + CYAN + "--version" + RESET
                + "                  Print version information and exit%n");
        out.println();

        // Footer
        out.println("Learn more about Kite:           " + CYAN + "https://kitelang.cloud/docs" + RESET);

        out.flush();
    }

    /**
     * Builds the grouped command entries for the help output.
     *
     * @return ordered list of command groups
     */
    static List<CommandGroup> buildCommandGroups() {
        var groups = new ArrayList<CommandGroup>();

        // Infrastructure commands (magenta)
        groups.add(new CommandGroup("Infrastructure", MAGENTA, List.of(
                new CommandEntry("new", "my-project", "Create a new Kite project"),
                new CommandEntry("validate", "./src", "Validate .kite files for syntax and schema errors"),
                new CommandEntry("plan", "-e dev", "Preview infrastructure changes without applying"),
                new CommandEntry("apply", "-e prod", "Apply infrastructure changes to provision resources"),
                new CommandEntry("destroy", "-e dev", "Destroy provisioned infrastructure"),
                new CommandEntry("output", "my-stack", "Display output values from deployed infrastructure")
        )));

        // Configuration commands (yellow)
        groups.add(new CommandGroup("Configuration", YELLOW, List.of(
                new CommandEntry("fmt", "./src", "Format .kite files to canonical style"),
                new CommandEntry("env", "dev", "Show or switch the current environment"),
                new CommandEntry("config", "get <key>", "Manage Kite CLI configuration"),
                new CommandEntry("providers", "install", "Manage Kite providers")
        )));

        // Utility commands (green)
        groups.add(new CommandGroup("Utility", GREEN, List.of(
                new CommandEntry("doctor", "", "Check your Kite installation and project setup"),
                new CommandEntry("version", "list", "Manage Kite CLI versions"),
                new CommandEntry("upgrade", "", "Upgrade Kite CLI to the latest version"),
                new CommandEntry("completion", "bash", "Generate shell completion scripts")
        )));

        return groups;
    }
}
