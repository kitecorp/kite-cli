package cloud.kitelang.cli;

import cloud.kitelang.cli.commands.*;
import cloud.kitelang.engine.kitefile.KiteInjector;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

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
                EnvCommand.class,
                ProvidersCommand.class,
                ConfigCommand.class,
                DoctorCommand.class,
                VersionCommand.class,
                UpgradeCommand.class,
                CompletionCommand.class
        }
)
@Slf4j
public class KiteCLI implements Runnable {

    @Option(names = {"-h", "--help"}, description = "Show this help message and exit")
    private boolean helpRequested;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print version information and exit")
    private boolean versionRequested;

    @Override
    public void run() {
        // Show custom bun-style help (no subcommand or -h flag)
        KiteUsageHelp.render(new PrintWriter(System.out, true));
    }

    public static void main(String... args) {
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

        // Apply bun-style headings to all subcommands
        KiteSubcommandHelp.apply(cmd);

        var exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    /**
     * Configures logging for the CLI.
     * 1. Loads logging.properties from classpath
     * 2. Applies verbosity from kitefile.yml if present
     */
    private static void configureLogging() {
        // Load logging.properties from classpath
        try (var stream = KiteCLI.class.getResourceAsStream("/logging.properties")) {
            if (stream != null) {
                LogManager.getLogManager().readConfiguration(stream);
            }
        } catch (Exception e) {
            // Ignore - use JVM defaults
        }

        // Priority: KITE_LOGGING env var > kitefile.yml > logging.properties
        String verbosity = System.getenv("KITE_LOGGING");

        // If no env var, try kitefile.yml
        if (verbosity == null) {
            var kitefilePath = Path.of("kitefile.yml");
            if (!Files.exists(kitefilePath)) {
                kitefilePath = Path.of("kitefile.yaml");
            }

            if (Files.exists(kitefilePath)) {
                try {
                    var kitefile = KiteInjector.createkitefile();
                    verbosity = kitefile.config().logs().verbosity();
                } catch (Exception e) {
                    // Silently ignore - kitefile parsing errors will be reported by commands
                }
            }
        }

        // Apply logging level if configured
        if (verbosity != null) {
            var level = switch (verbosity.toLowerCase()) {
                case "error" -> Level.SEVERE;
                case "warn" -> Level.WARNING;
                case "debug" -> Level.FINE;
                case "trace" -> Level.FINEST;
                default -> Level.INFO;
            };

            // Apply to root logger and all kite/engine loggers
            Logger.getLogger("").setLevel(level);
            Logger.getLogger("cloud.kitelang").setLevel(level);
        }
    }
}
