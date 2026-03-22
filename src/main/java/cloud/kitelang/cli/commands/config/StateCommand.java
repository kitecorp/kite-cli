package cloud.kitelang.cli.commands.config;

import cloud.kitelang.cli.config.GlobalConfig;
import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.interactive.InteractivePrompt;
import cloud.kitelang.cli.interactive.StateBackendWizard;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Configures state backend settings for infrastructure state storage.
 * State configuration is stored in ~/.kite/config.yml per project and environment.
 */
@Command(
        name = "state",
        description = "Configure state backend for infrastructure state storage",
        footer = {
                "",
                "Examples:",
                "  kite config state                  Interactive state configuration wizard",
                "  kite config state --show           Show current state configuration",
                "  kite config state -p myproject -e prod --url jdbc:postgresql://db.example.com:5432/postgres",
                "",
                "State configuration is stored in: ~/.kite/config.yml"
        },
        mixinStandardHelpOptions = true
)
@Slf4j
public class StateCommand implements Callable<Integer> {

    @Option(
            names = {"-p", "--project"},
            paramLabel = "name",
            description = "Project name (defaults to current directory name)"
    )
    private String project;

    @Option(
            names = {"-e", "--environment"},
            paramLabel = "env",
            description = "Target environment (dev, staging, prod)"
    )
    private String environment;

    @Option(
            names = {"--url"},
            paramLabel = "url",
            description = "PostgreSQL connection URL"
    )
    private String url;

    @Option(
            names = {"--username"},
            paramLabel = "user",
            description = "PostgreSQL username",
            defaultValue = "postgres"
    )
    private String username;

    @Option(
            names = {"--password"},
            paramLabel = "pass",
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
            var projectName = getProjectName();

            if (show) {
                return showConfiguration(config, projectName);
            }

            if (remove) {
                return removeConfiguration(config, projectName);
            }

            // If environment and URL provided, configure non-interactively
            if (environment != null && url != null) {
                return configureNonInteractive(config, projectName);
            }

            // Otherwise, run interactive wizard
            return runInteractiveWizard(config, projectName);

        } catch (Exception e) {
            log.error("Failed to configure state", e);
            Console.error(e.getMessage());
            return 1;
        }
    }

    /**
     * Gets the project name from option or current directory.
     */
    private String getProjectName() {
        if (project != null && !project.isBlank()) {
            return project;
        }
        return Path.of("").toAbsolutePath().getFileName().toString();
    }

    private int showConfiguration(GlobalConfig config, String projectName) {
        var projects = config.getProjects();

        if (projects.isEmpty()) {
            Console.println("No state configurations found.");
            Console.println("\nRun 'kite config state' to configure state storage.");
            return 0;
        }

        Console.header("State Configuration");
        Console.println("Config file: " + GlobalConfig.getConfigPath());
        Console.println();

        for (var projectConfig : projects) {
            var projName = projectConfig.getName();

            Console.println(Console.bold("[" + projName + "]"));

            for (var envEntry : projectConfig.getEnvironments().entrySet()) {
                var env = envEntry.getKey();
                var envConfig = envEntry.getValue();
                var state = envConfig.getState();

                Console.println("  " + env + ":");
                if (state != null) {
                    Console.println("    Type: " + state.getType());
                    if ("postgresql".equals(state.getType())) {
                        Console.println("    URL: " + state.getUrl());
                        Console.println("    Username: " + state.getUsername());
                        Console.println("    Password: " + getPasswordStatus(state, projName, env));
                    } else if ("kite-cloud".equals(state.getType())) {
                        Console.println("    Backend: Kite Cloud (managed)");
                    }
                } else {
                    Console.println("    Not configured");
                }
            }
            Console.println();
        }

        return 0;
    }

    private int removeConfiguration(GlobalConfig config, String projectName) throws Exception {
        if (environment == null) {
            Console.error("--environment is required with --remove");
            return 1;
        }

        if (!config.hasEnvironment(projectName, environment)) {
            Console.error("No configuration found for project '" + projectName + "' environment '" + environment + "'");
            return 1;
        }

        config.removeEnvironment(projectName, environment);
        config.save();

        Console.success("Removed state configuration for: " + projectName + "/" + environment);
        return 0;
    }

    private int configureNonInteractive(GlobalConfig config, String projectName) throws Exception {
        StateBackendWizard.configureEnvironment(config, projectName, environment, url, username, password);

        Console.success("State configuration saved for: " + projectName + "/" + environment);

        if (password == null || password.isBlank()) {
            var passwordEnvVar = GlobalConfig.getPasswordEnvVarName(projectName, environment);
            Console.println("\nNo password provided. Set it via:");
            Console.println("  - Config: kite config state -p " + projectName + " -e " + environment + " --url \"" + url + "\" --password");
            Console.println("  - Env var (takes precedence): export " + passwordEnvVar + "=<password>");
        }

        return 0;
    }

    /**
     * Gets a human-readable password status for display.
     */
    private String getPasswordStatus(GlobalConfig.StateConfig state, String projectName, String environment) {
        var envVarName = GlobalConfig.getPasswordEnvVarName(projectName, environment);
        var envPassword = System.getenv(envVarName);

        if (envPassword != null && !envPassword.isBlank()) {
            return "**** (from $" + envVarName + ")";
        } else if (state.getPassword() != null && !state.getPassword().isBlank()) {
            return "**** (from config)";
        } else {
            return "Not configured";
        }
    }

    private int runInteractiveWizard(GlobalConfig config, String projectName) throws Exception {
        try (var prompt = InteractivePrompt.create()) {
            if (prompt == null) {
                Console.error("Interactive mode requires a terminal.");
                Console.println("Use --project, --environment and --url for non-interactive configuration.");
                return 1;
            }

            var wizard = new StateBackendWizard(prompt, config, projectName);
            wizard.run();
            return 0;
        }
    }
}
