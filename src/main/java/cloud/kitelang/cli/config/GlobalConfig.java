package cloud.kitelang.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the global Kite configuration stored in ~/.kite/config.yml.
 * Handles per-environment state backend configurations.
 */
@Slf4j
public class GlobalConfig {

    private static final String KITE_DIR = ".kite";
    private static final String CONFIG_FILE = "config.yml";

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(
            new YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    );

    private final Path configPath;
    private KiteConfig config;

    /**
     * Root configuration structure.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KiteConfig {
        private Map<String, EnvironmentConfig> environments = new HashMap<>();
    }

    /**
     * Per-environment configuration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnvironmentConfig {
        private StateConfig state;
    }

    /**
     * State backend configuration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StateConfig {
        private String type; // "postgresql" or "kite-cloud"
        private String url;
        private String username;
        private String password; // Stored password (fallback if env var not set)

        /**
         * Gets the effective password with priority: env var > config file.
         * Environment variable format: KITE_DB_PASSWORD_{ENVIRONMENT}
         *
         * @param environment the environment name (e.g., "prod", "dev")
         * @return the resolved password
         */
        public String getEffectivePassword(String environment) {
            var envVarName = getPasswordEnvVarName(environment);
            var envPassword = System.getenv(envVarName);
            if (envPassword != null && !envPassword.isBlank()) {
                return envPassword;
            }
            return password;
        }

        /**
         * Checks if password is available from any source.
         *
         * @param environment the environment name
         * @return true if password is available
         */
        public boolean hasPassword(String environment) {
            var effective = getEffectivePassword(environment);
            return effective != null && !effective.isBlank();
        }
    }

    /**
     * Gets the environment variable name for a given environment's password.
     */
    public static String getPasswordEnvVarName(String environment) {
        return "KITE_DB_PASSWORD_" + environment.toUpperCase().replaceAll("[^A-Z0-9]", "_");
    }

    private GlobalConfig(Path configPath) {
        this.configPath = configPath;
    }

    /**
     * Loads or creates the global configuration.
     */
    public static GlobalConfig load() throws IOException {
        var configPath = getConfigPath();
        var globalConfig = new GlobalConfig(configPath);
        globalConfig.loadFromFile();
        return globalConfig;
    }

    /**
     * Gets the default config path (~/.kite/config.yml).
     */
    public static Path getConfigPath() {
        return Path.of(System.getProperty("user.home"), KITE_DIR, CONFIG_FILE);
    }

    /**
     * Gets the kite directory path (~/.kite).
     */
    public static Path getKiteDir() {
        return Path.of(System.getProperty("user.home"), KITE_DIR);
    }

    private void loadFromFile() throws IOException {
        if (Files.exists(configPath)) {
            config = YAML_MAPPER.readValue(configPath.toFile(), KiteConfig.class);
            log.debug("Loaded config from {}", configPath);
        } else {
            config = new KiteConfig();
            log.debug("No config file found, using defaults");
        }
    }

    /**
     * Saves the configuration to disk.
     */
    public void save() throws IOException {
        var kiteDir = configPath.getParent();
        if (!Files.exists(kiteDir)) {
            Files.createDirectories(kiteDir);
        }
        YAML_MAPPER.writeValue(configPath.toFile(), config);
        log.debug("Saved config to {}", configPath);
    }

    /**
     * Gets the state configuration for a specific environment.
     */
    public StateConfig getStateConfig(String environment) {
        var envConfig = config.getEnvironments().get(environment);
        return envConfig != null ? envConfig.getState() : null;
    }

    /**
     * Sets the state configuration for a specific environment.
     */
    public void setStateConfig(String environment, StateConfig stateConfig) {
        var envConfig = config.getEnvironments().computeIfAbsent(environment, k -> new EnvironmentConfig());
        envConfig.setState(stateConfig);
    }

    /**
     * Gets all configured environments.
     */
    public Map<String, EnvironmentConfig> getEnvironments() {
        return config.getEnvironments();
    }

    /**
     * Checks if an environment is configured.
     */
    public boolean hasEnvironment(String environment) {
        return config.getEnvironments().containsKey(environment);
    }

    /**
     * Removes an environment configuration.
     */
    public void removeEnvironment(String environment) {
        config.getEnvironments().remove(environment);
    }

    /**
     * Creates a PostgreSQL state configuration.
     */
    public static StateConfig createPostgreSQLConfig(String url, String username, String password) {
        var config = new StateConfig();
        config.setType("postgresql");
        config.setUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        return config;
    }

    /**
     * Creates a Kite Cloud state configuration.
     */
    public static StateConfig createKiteCloudConfig() {
        var config = new StateConfig();
        config.setType("kite-cloud");
        return config;
    }
}
