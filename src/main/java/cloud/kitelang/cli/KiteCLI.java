package cloud.kitelang.cli;

import cloud.kitelang.cli.commands.ApplyCommand;
import cloud.kitelang.cli.commands.CompletionCommand;
import cloud.kitelang.cli.commands.ConfigCommand;
import cloud.kitelang.cli.commands.DestroyCommand;
import cloud.kitelang.cli.commands.DoctorCommand;
import cloud.kitelang.cli.commands.FmtCommand;
import cloud.kitelang.cli.commands.NewCommand;
import cloud.kitelang.cli.commands.OutputCommand;
import cloud.kitelang.cli.commands.PlanCommand;
import cloud.kitelang.cli.commands.ProvidersCommand;
import cloud.kitelang.cli.commands.ValidateCommand;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Main entry point for the Kite CLI.
 * Provides multi-cloud Infrastructure as Code provisioning.
 */
@Command(
        name = "kite",
        version = "kite 0.1.0",
        description = "Kite - Write once, provision anywhere. Multi-cloud IaC tool.",
        subcommands = {
                NewCommand.class,
                ValidateCommand.class,
                PlanCommand.class,
                ApplyCommand.class,
                DestroyCommand.class,
                OutputCommand.class,
                FmtCommand.class,
                ProvidersCommand.class,
                ConfigCommand.class,
                DoctorCommand.class,
                CompletionCommand.class,
                CommandLine.HelpCommand.class
        }
)
@Log4j2
public class KiteCLI implements Runnable {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit")
    private boolean helpRequested;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print version information and exit")
    private boolean versionRequested;

    @Override
    public void run() {
        // When no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }

    static void main(String... args) {
        var colorScheme = new CommandLine.Help.ColorScheme.Builder()
                .commands(CommandLine.Help.Ansi.Style.bold, CommandLine.Help.Ansi.Style.fg_cyan)
                .options(CommandLine.Help.Ansi.Style.fg_cyan)
                .parameters(CommandLine.Help.Ansi.Style.fg_yellow)
                .optionParams(CommandLine.Help.Ansi.Style.italic, CommandLine.Help.Ansi.Style.fg_yellow)
                .build();

        var exceptionHandler = new KiteExceptionHandler();

        var cmd = new CommandLine(new KiteCLI())
                .setCommandName("kite")
                .setColorScheme(colorScheme)
                // Custom error handling with suggestions
                .setParameterExceptionHandler(exceptionHandler)
                .setExecutionExceptionHandler(exceptionHandler)
                // Help formatting
                .setUsageHelpAutoWidth(true)
                // Typo suggestions: "Did you mean 'init'?"
                .setSubcommandsCaseInsensitive(true)
                .setOptionsCaseInsensitive(true)
                // Allow abbreviated options: --ver for --version
                .setAbbreviatedOptionsAllowed(true)
                // Allow abbreviated subcommands: ini for init
                .setAbbreviatedSubcommandsAllowed(true)
                // Strict parsing - fail on unknown options
                .setUnmatchedArgumentsAllowed(false)
                // POSIX clustering: -hv instead of -h -v
                .setPosixClusteredShortOptionsAllowed(true);

        var exitCode = cmd.execute(args);
        System.exit(exitCode);
    }
}
