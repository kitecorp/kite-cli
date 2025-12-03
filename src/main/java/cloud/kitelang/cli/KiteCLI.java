package cloud.kitelang.cli;

import cloud.kitelang.cli.commands.ApplyCommand;
import cloud.kitelang.cli.commands.InitCommand;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for the Kite CLI.
 * Provides multi-cloud Infrastructure as Code provisioning.
 */
@Command(
    name = "kite",
    mixinStandardHelpOptions = true,
    version = "kite 0.1.0",
    description = "Kite - Write once, provision anywhere. Multi-cloud IaC tool.",
    subcommands = {
        InitCommand.class,
        ApplyCommand.class,
        CommandLine.HelpCommand.class
    }
)
@Log4j2
public class KiteCLI implements Runnable {

    @Override
    public void run() {
        // When no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }

    static void main(String... args) {
        var exitCode = new CommandLine(new KiteCLI())
            .setCommandName("kite")
            .execute(args);
        System.exit(exitCode);
    }
}
