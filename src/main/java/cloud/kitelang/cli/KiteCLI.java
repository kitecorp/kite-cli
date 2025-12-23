package cloud.kitelang.cli;

import cloud.kitelang.cli.commands.*;
import cloud.kitelang.engine.kitefile.KiteInjector;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main entry point for the Kite CLI.
 * Provides multi-cloud Infrastructure as Code provisioning.
 */
@Command(
        name = "kite",
        versionProvider = VersionProvider.class,
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
@Slf4j
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
        // Configure logging from kitefile.yml if present
        configureLogging();

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
                // Typo suggestions: "Did you mean 'new'?"
                .setSubcommandsCaseInsensitive(true)
                .setOptionsCaseInsensitive(true)
                // Allow abbreviated options: --ver for --version
                .setAbbreviatedOptionsAllowed(true)
                // Allow abbreviated subcommands: ne for new
                .setAbbreviatedSubcommandsAllowed(true)
                // Strict parsing - fail on unknown options
                .setUnmatchedArgumentsAllowed(false)
                // POSIX clustering: -hv instead of -h -v
                .setPosixClusteredShortOptionsAllowed(true);

        var exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    /**
     * Configures logging based on kitefile.yml logs.verbosity setting.
     * Supported values: error, warn, info, debug, trace
     */
    private static void configureLogging() {
        var kitefilePath = Path.of("kitefile.yml");
        if (!Files.exists(kitefilePath)) {
            kitefilePath = Path.of("kitefile.yaml");
        }

        if (!Files.exists(kitefilePath)) {
            return; // No kitefile, use defaults
        }

        try {
            var kitefile = KiteInjector.createkitefile();
            var verbosity = kitefile.config().logs().verbosity();

            var level = switch (verbosity) {
                case "error" -> Level.ERROR;
                case "warn" -> Level.WARN;
                case "debug" -> Level.DEBUG;
                case "trace" -> Level.TRACE;
                default -> Level.INFO;
            };

        } catch (Exception e) {
            // Silently ignore - kitefile parsing errors will be reported by commands
        }
    }
}
