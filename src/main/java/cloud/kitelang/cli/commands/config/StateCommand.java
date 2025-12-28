package cloud.kitelang.cli.commands.config;

import cloud.kitelang.cli.config.GlobalConfig;
import cloud.kitelang.cli.interactive.InteractivePrompt;
import cloud.kitelang.cli.interactive.StateBackendWizard;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Configures state backend settings for infrastructure state storage.
 * State configuration is stored in ~/.kite/config.yml per environment.
 */
@Command(
        name = "state",
        description = "Configure state backend for infrastructure state storage",
        footer = {
                "",
                "Examples:",
                "  kite config state                  Interactive state configuration wizard",
                "  kite config state --show           Show current state configuration",
                "  kite config state -e prod --url jdbc:postgresql://db.example.com:5432/kite",
                "",
                "State configuration is stored in: ~/.kite/config.yml"
        },
        mixinStandardHelpOptions = true
)
@Slf4j
public class StateCommand implements Callable<Integer> {

    @Option(
            names = {"-e", "--environment"},
            description = "Target environment (dev, staging, prod)"
    )
    private String environment;

    @Option(
            names = {"--url"},
            description = "PostgreSQL connection URL"
    )
    private String url;

    @Option(
            names = {"--username"},
            description = "PostgreSQL username",
            defaultValue = "postgres"
    )
    private String username;

    @Option(
            names = {"--password"},
            description = "PostgreSQL password (stored in config file)",
            interactive = true,
            arity = "0..1"
    )
    private String password;

    @Option(
            names = {"--show"},
            description = "Show current state configuration"
    )
    private boolean show;

    @Option(
            names = {"--remove"},
            description = "Remove state configuration for an environment"
    )
    private boolean remove;

    @Override
    public Integer call() {
        try {
            var config = GlobalConfig.load();

            if (show) {
                return showConfiguration(config);
            }

            if (remove) {
                return removeConfiguration(config);
            }

            // If environment and URL provided, configure non-interactively
            if (environment != null && url != null) {
                return configureNonInteractive(config);
            }

            // Otherwise, run interactive wizard
            return runInteractiveWizard(config);

        } catch (Exception e) {
            log.error("Failed to configure state", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int showConfiguration(GlobalConfig config) {
        var environments = config.getEnvironments();

        if (environments.isEmpty()) {
            System.out.println("No state configurations found.");
            System.out.println("\nRun 'kite config state' to configure state storage.");
            return 0;
        }

        System.out.println("State Configuration");
        System.out.println("===================");
        System.out.println("Config file: " + GlobalConfig.getConfigPath());
        System.out.println();

        for (var entry : environments.entrySet()) {
            var env = entry.getKey();
            var envConfig = entry.getValue();
            var state = envConfig.getState();

            System.out.println("[" + env + "]");
            if (state != null) {
                System.out.println("  Type: " + state.getType());
                if ("postgresql".equals(state.getType())) {
                    System.out.println("  URL: " + state.getUrl());
                    System.out.println("  Username: " + state.getUsername());
                    System.out.println("  Password: " + getPasswordStatus(state, env));
                } else if ("kite-cloud".equals(state.getType())) {
                    System.out.println("  Backend: Kite Cloud (managed)");
                }
            } else {
                System.out.println("  Not configured");
            }
            System.out.println();
        }

        return 0;
    }

    private int removeConfiguration(GlobalConfig config) throws Exception {
        if (environment == null) {
            System.err.println("Error: --environment is required with --remove");
            return 1;
        }

        if (!config.hasEnvironment(environment)) {
            System.err.println("Error: No configuration found for environment '" + environment + "'");
            return 1;
        }

        config.removeEnvironment(environment);
        config.save();

        System.out.println("Removed state configuration for: " + environment);
        return 0;
    }

    private int configureNonInteractive(GlobalConfig config) throws Exception {
        StateBackendWizard.configureEnvironment(config, environment, url, username, password);

        System.out.println("State configuration saved for: " + environment);

        if (password == null || password.isBlank()) {
            var passwordEnvVar = GlobalConfig.getPasswordEnvVarName(environment);
            System.out.println("\nNo password provided. Set it via:");
            System.out.println("  - Config: kite config state -e " + environment + " --url \"" + url + "\" --password");
            System.out.println("  - Env var (takes precedence): export " + passwordEnvVar + "=<password>");
        }

        return 0;
    }

    /**
     * Gets a human-readable password status for display.
     */
    private String getPasswordStatus(GlobalConfig.StateConfig state, String environment) {
        var envVarName = GlobalConfig.getPasswordEnvVarName(environment);
        var envPassword = System.getenv(envVarName);

        if (envPassword != null && !envPassword.isBlank()) {
            return "**** (from $" + envVarName + ")";
        } else if (state.getPassword() != null && !state.getPassword().isBlank()) {
            return "**** (from config)";
        } else {
            return "Not configured";
        }
    }

    private int runInteractiveWizard(GlobalConfig config) throws Exception {
        try (var prompt = InteractivePrompt.create()) {
            if (prompt == null) {
                System.err.println("Error: Interactive mode requires a terminal.");
                System.err.println("Use --environment and --url for non-interactive configuration.");
                return 1;
            }

            var wizard = new StateBackendWizard(prompt, config);
            wizard.run();
            return 0;
        }
    }
}
