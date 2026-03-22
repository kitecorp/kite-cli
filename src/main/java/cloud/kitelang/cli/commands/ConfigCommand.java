package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.commands.config.StateCommand;
import cloud.kitelang.cli.config.ConfigLoader;
import cloud.kitelang.cli.console.Console;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Manage Kite CLI configuration.
 * Configuration is stored in ~/.kite/config.yml
 */
@Command(
        name = "config",
        description = "Manage Kite CLI configuration",
        footer = {
                "",
                "Examples:",
                "  kite config get defaults.environment  Get default environment",
                "  kite config set defaults.environment prod",
                "  kite config set aws.profile production",
                "  kite config set aws.region us-west-2",
                "  kite config list                      Show all settings",
                "  kite config path                      Show config file location"
        },
        mixinStandardHelpOptions = true,
        subcommands = {
                ConfigCommand.GetCommand.class,
                ConfigCommand.SetCommand.class,
                ConfigCommand.ListCommand.class,
                ConfigCommand.PathCommand.class,
                StateCommand.class
        }
)
@Slf4j
public class ConfigCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        Console.println("Usage: kite config <command>");
        Console.println();
        Console.println("Commands:");
        Console.println("  get <key>          Get a config value");
        Console.println("  set <key> <value>  Set a config value");
        Console.println("  list               List all config values");
        Console.println("  path               Show config file path");
        Console.println("  state              Configure state backend (database connection)");
        Console.println();
        Console.println("Config keys:");
        Console.println("  defaults.environment  Default environment (dev, staging, prod)");
        Console.println("  defaults.provider     Default cloud provider");
        Console.println("  defaults.output       Output format (table, json, yaml)");
        Console.println("  verbosity             Log level (0=quiet, 1=normal, 2=verbose)");
        Console.println("  aws.profile           AWS profile name");
        Console.println("  aws.region            AWS region");
        Console.println();
        Console.println("Examples:");
        Console.println("  kite config set defaults.environment prod");
        Console.println("  kite config set aws.profile production");
        Console.println("  kite config get defaults.environment");
        Console.println("  kite config list");
        Console.println("  kite config state                         Configure state backend");
        return 0;
    }

    /**
     * Get a config value.
     */
    @Command(
            name = "get",
            description = "Get a configuration value",
            mixinStandardHelpOptions = true
    )
    public static class GetCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "key",
                description = "Config key (e.g., defaults.environment)"
        )
        private String key;

        @Override
        public Integer call() {
            var value = ConfigLoader.get(key);
            if (value == null) {
                Console.error("Key not found: " + key);
                return 1;
            }
            Console.println(value);
            return 0;
        }
    }

    /**
     * Set a config value.
     */
    @Command(
            name = "set",
            description = "Set a configuration value",
            mixinStandardHelpOptions = true
    )
    public static class SetCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "key",
                description = "Config key (e.g., defaults.environment)"
        )
        private String key;

        @Parameters(
                index = "1",
                paramLabel = "value",
                description = "Value to set"
        )
        private String value;

        @Override
        public Integer call() {
            try {
                ConfigLoader.set(key, value);
                Console.success("Set " + key + " = " + value);
                return 0;
            } catch (Exception e) {
                Console.error("Failed to set config: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * List all config values.
     */
    @Command(
            name = "list",
            description = "List all configuration values",
            mixinStandardHelpOptions = true
    )
    public static class ListCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            var config = ConfigLoader.list();

            if (config.isEmpty()) {
                Console.println("No configuration set.");
                Console.println("Config file: " + ConfigLoader.configFile());
                return 0;
            }

            // Find max key length for alignment
            int maxLen = config.keySet().stream()
                    .mapToInt(String::length)
                    .max()
                    .orElse(20);

            for (var entry : config.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                Console.printf("%-" + maxLen + "s = %s%n", entry.getKey(), entry.getValue());
            }

            return 0;
        }
    }

    /**
     * Show config file path.
     */
    @Command(
            name = "path",
            description = "Show configuration file path",
            mixinStandardHelpOptions = true
    )
    public static class PathCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            Console.println(ConfigLoader.configFile().toString());
            return 0;
        }
    }
}
