package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.config.ConfigLoader;
import cloud.kitelang.cli.config.KiteConfig;
import cloud.kitelang.cli.console.Console;
import cloud.kitelang.engine.kitefile.KiteInjector;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Quick environment switching command.
 * Shows current environment or switches to a new one.
 */
@Command(
        name = "env",
        description = "Show or switch the current environment",
        footer = {
                "",
                "Examples:",
                "  kite env                  Show current environment",
                "  kite env dev              Switch to dev environment",
                "  kite env prod             Switch to prod environment",
                "  kite env staging          Switch to staging environment"
        },
        mixinStandardHelpOptions = true
)
@Slf4j
public class EnvCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            paramLabel = "ENVIRONMENT",
            description = "Environment to switch to (dev, staging, prod, etc.)",
            arity = "0..1"
    )
    private String environment;

    @Override
    public Integer call() {
        try {
            if (environment == null) {
                return showCurrentEnvironment();
            } else {
                return switchEnvironment();
            }
        } catch (Exception e) {
            Console.error(e.getMessage());
            log.debug("Environment command failed", e);
            return 1;
        }
    }

    /**
     * Shows the current environment.
     */
    private int showCurrentEnvironment() {
        var config = ConfigLoader.load();
        var currentEnv = config.defaults().environment();

        Console.println("Current environment: " + Console.bold(currentEnv));

        // Show available environments from kitefile.yml if present
        var kitefilePath = findKitefile();
        if (kitefilePath != null) {
            try {
                var kitefile = KiteInjector.createkitefile();
                var environments = kitefile.config().environments();

                if (!environments.isEmpty()) {
                    Console.println();
                    Console.println("Available environments:");
                    for (var env : environments.keySet()) {
                        var marker = env.equals(currentEnv) ? " (current)" : "";
                        Console.println("  " + env + marker);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to load kitefile", e);
            }
        }

        // Show environments directory if present
        var envDir = Path.of("environments");
        if (Files.isDirectory(envDir)) {
            try (var stream = Files.list(envDir)) {
                var envFolders = stream
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();

                if (!envFolders.isEmpty()) {
                    Console.println();
                    Console.println("Environment directories:");
                    for (var env : envFolders) {
                        var marker = env.equals(currentEnv) ? " (current)" : "";
                        Console.println("  " + env + marker);
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to list environment directories", e);
            }
        }

        return 0;
    }

    /**
     * Switches to the specified environment.
     */
    private int switchEnvironment() throws IOException {
        // Validate environment exists (either in kitefile or as directory)
        if (!environmentExists(environment)) {
            Console.error("Environment '" + environment + "' not found.");
            Console.println();
            Console.println("Create it by adding to kitefile.yml:");
            Console.println("  environments:");
            Console.println("    " + environment + ":");
            Console.println("      credentials:");
            Console.println("        - type: aws");
            Console.println("          profile: " + environment);
            return 1;
        }

        // Update config
        ConfigLoader.set("defaults.environment", environment);

        Console.success("Switched to environment: " + environment);
        Console.println();
        Console.println("Future commands will use this environment by default.");
        Console.println("Override with: kite plan -e <other-env>");

        return 0;
    }

    /**
     * Checks if an environment exists in kitefile.yml or as a directory.
     */
    private boolean environmentExists(String env) {
        // Check kitefile.yml
        var kitefilePath = findKitefile();
        if (kitefilePath != null) {
            try {
                var kitefile = KiteInjector.createkitefile();
                if (kitefile.config().environments().containsKey(env)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("Failed to load kitefile", e);
            }
        }

        // Check environments directory
        var envDir = Path.of("environments", env);
        if (Files.isDirectory(envDir)) {
            return true;
        }

        // Allow creating new environments by accepting common names
        return env.matches("^[a-z][a-z0-9_-]*$");
    }

    private Path findKitefile() {
        var names = java.util.List.of("kitefile.yml", "kitefile.yaml", "kite.yml", "kite.yaml");
        for (var name : names) {
            var path = Path.of(name);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }
}
