package cloud.kitelang.cli.interactive;

import cloud.kitelang.cli.config.GlobalConfig;
import cloud.kitelang.cli.config.GlobalConfig.StateConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive wizard for configuring state backend per environment.
 * Guides users through setting up PostgreSQL or Kite Cloud state storage.
 */
@Slf4j
public class StateBackendWizard {

    private static final List<String> DEFAULT_ENVIRONMENTS = List.of("dev", "staging", "prod");

    private final InteractivePrompt prompt;
    private final GlobalConfig config;
    private final List<String> environments;

    /**
     * Creates a wizard with default environments.
     */
    public StateBackendWizard(InteractivePrompt prompt, GlobalConfig config) {
        this(prompt, config, DEFAULT_ENVIRONMENTS);
    }

    /**
     * Creates a wizard with specific environments.
     *
     * @param prompt       the interactive prompt
     * @param config       the global config
     * @param environments the list of environments to configure
     */
    public StateBackendWizard(InteractivePrompt prompt, GlobalConfig config, List<String> environments) {
        this.prompt = prompt;
        this.config = config;
        this.environments = environments != null && !environments.isEmpty()
                ? environments
                : DEFAULT_ENVIRONMENTS;
    }

    /**
     * Runs the state backend configuration wizard.
     *
     * @return true if configuration was saved, false if skipped or cancelled
     */
    public boolean run() throws IOException {
        prompt.printHeader("State Management Setup");

        // Select backend type
        var backendType = prompt.selectOne(
                "How do you want to store infrastructure state?",
                List.of(
                        new InteractivePrompt.Option("kite-cloud", "Kite Cloud (Managed - team collaboration, no setup)"),
                        new InteractivePrompt.Option("self-managed", "Self-managed PostgreSQL (bring your own database)"),
                        new InteractivePrompt.Option("skip", "Skip for now")
                )
        );

        if (backendType == null || "skip".equals(backendType)) {
            prompt.printInfo("Skipped state configuration. You can configure it later with: kite config state");
            return false;
        }

        if ("kite-cloud".equals(backendType)) {
            return configureKiteCloud();
        } else {
            return configurePostgreSQL();
        }
    }

    /**
     * Configures Kite Cloud backend.
     */
    private boolean configureKiteCloud() throws IOException {
        prompt.printInfo("\nKite Cloud provides managed state storage with team collaboration features.");
        prompt.printInfo("Sign up at: https://app.kitelang.cloud\n");

        // For now, just show a message - actual OAuth flow will be in Phase 2
        prompt.printInfo("Kite Cloud integration is coming soon!");
        prompt.printInfo("For now, please use Self-managed PostgreSQL.\n");

        // Ask if they want to configure PostgreSQL instead
        if (prompt.confirm("Would you like to configure a self-managed PostgreSQL database instead?", true)) {
            return configurePostgreSQL();
        }

        return false;
    }

    /**
     * Configures self-managed PostgreSQL backend.
     */
    private boolean configurePostgreSQL() throws IOException {
        // Build environment options from the user's selected environments
        var envOptions = new ArrayList<InteractivePrompt.Option>();
        for (var env : environments) {
            var label = switch (env.toLowerCase()) {
                case "dev" -> "dev (Development)";
                case "staging" -> "staging (Staging)";
                case "prod" -> "prod (Production)";
                default -> env;
            };
            // Pre-select all environments by default
            envOptions.add(new InteractivePrompt.Option(env, label, null, true));
        }

        // Let user select which environments to configure
        var selectedEnvs = prompt.selectMany(
                "Which environments do you want to configure?",
                envOptions
        );

        if (selectedEnvs.isEmpty()) {
            prompt.printInfo("No environments selected. Skipping state configuration.");
            return false;
        }

        prompt.printInfo("\nConfiguring " + selectedEnvs.size() + " environment(s): " + String.join(", ", selectedEnvs));
        prompt.printInfo("");

        // Configure each selected environment
        for (var environment : selectedEnvs) {
            prompt.printInfo("--- Configuring: " + environment + " ---");

            // Check if already configured
            if (config.hasEnvironment(environment)) {
                if (!prompt.confirm("Environment '" + environment + "' is already configured. Overwrite?", false)) {
                    continue;
                }
            }

            // Get PostgreSQL connection details
            var defaultUrl = getDefaultUrl(environment);
            var url = prompt.input("PostgreSQL connection URL:", defaultUrl);
            if (url == null || url.isBlank()) {
                url = defaultUrl;
            }

            var username = prompt.input("Username:", "postgres");
            if (username == null || username.isBlank()) {
                username = "postgres";
            }

            var password = prompt.password("Password:");

            // Test connection
            prompt.printInfo("\nTesting connection...");
            if (testConnection(url, username, password)) {
                prompt.printSuccess("Connected to PostgreSQL");

                // Save configuration with password
                var stateConfig = GlobalConfig.createPostgreSQLConfig(url, username, password);
                config.setStateConfig(environment, stateConfig);

            } else {
                prompt.printError("Failed to connect to PostgreSQL");
                if (!prompt.confirm("Save configuration anyway?", false)) {
                    continue;
                }

                var stateConfig = GlobalConfig.createPostgreSQLConfig(url, username, password);
                config.setStateConfig(environment, stateConfig);
            }

            prompt.printInfo("");
        }

        // Save configuration
        config.save();
        prompt.printSuccess("Configuration saved to: " + GlobalConfig.getConfigPath());

        return true;
    }

    /**
     * Tests a PostgreSQL connection.
     */
    private boolean testConnection(String url, String username, String password) {
        try {
            // Ensure PostgreSQL driver is loaded
            Class.forName("org.postgresql.Driver");

            try (var conn = DriverManager.getConnection(url, username, password)) {
                return conn.isValid(5);
            }
        } catch (ClassNotFoundException e) {
            log.warn("PostgreSQL driver not found", e);
            return false;
        } catch (SQLException e) {
            log.debug("Failed to connect to PostgreSQL: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Gets a sensible default URL for an environment.
     * Uses schema-per-environment pattern: database holds all envs, each env is a schema.
     */
    private String getDefaultUrl(String environment) {
        var schema = environment.toLowerCase().replaceAll("[^a-z0-9]", "_");
        return "jdbc:postgresql://localhost:5432/kite?currentSchema=" + schema;
    }

    /**
     * Runs the wizard for a specific environment (non-interactive mode).
     *
     * @param config      the global config
     * @param environment the target environment (dev, staging, prod)
     * @param url         PostgreSQL connection URL
     * @param username    database username
     * @param password    database password (can be null, then env var is expected)
     */
    public static void configureEnvironment(
            GlobalConfig config,
            String environment,
            String url,
            String username,
            String password
    ) throws IOException {
        var stateConfig = GlobalConfig.createPostgreSQLConfig(url, username, password);
        config.setStateConfig(environment, stateConfig);
        config.save();
    }
}
