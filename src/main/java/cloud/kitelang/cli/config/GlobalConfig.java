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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the global Kite configuration stored in ~/.kite/config.yml.
 * Handles per-project, per-environment state backend configurations.
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
        private List<ProjectConfig> projects = new ArrayList<>();
    }

    /**
     * Per-project configuration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectConfig {
        private String name;
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
         * Environment variable format: KITE__{PROJECT}__{ENVIRONMENT}__DB_PASSWORD
         *
         * @param project     the project name
         * @param environment the environment name (e.g., "prod", "dev")
         * @return the resolved password
         */
        public String getEffectivePassword(String project, String environment) {
            var envVarName = getPasswordEnvVarName(project, environment);
            var envPassword = System.getenv(envVarName);
            if (envPassword != null && !envPassword.isBlank()) {
                return envPassword;
            }
            return password;
        }

        /**
         * Checks if password is available from any source.
         *
         * @param project     the project name
         * @param environment the environment name
         * @return true if password is available
         */
        public boolean hasPassword(String project, String environment) {
            var effective = getEffectivePassword(project, environment);
            return effective != null && !effective.isBlank();
        }
    }

    /**
     * Gets the environment variable name for a given project/environment's password.
     * Format: KITE__{PROJECT}__{ENVIRONMENT}__DB_PASSWORD
     * Uses double underscores as separators to clearly delimit project/environment names.
     */
    public static String getPasswordEnvVarName(String project, String environment) {
        var projectPart = project.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        var envPart = environment.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return "KITE__" + projectPart + "__" + envPart + "__DB_PASSWORD";
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
     * Finds a project by name.
     */
    private Optional<ProjectConfig> findProject(String projectName) {
        return config.getProjects().stream()
                .filter(p -> projectName.equals(p.getName()))
                .findFirst();
    }

    /**
     * Gets or creates a project by name.
     */
    private ProjectConfig getOrCreateProject(String projectName) {
        return findProject(projectName).orElseGet(() -> {
            var project = new ProjectConfig();
            project.setName(projectName);
            config.getProjects().add(project);
            return project;
        });
    }

    /**
     * Gets the state configuration for a specific project and environment.
     */
    public StateConfig getStateConfig(String project, String environment) {
        var projectConfig = findProject(project).orElse(null);
        if (projectConfig == null) {
            return null;
        }
        var envConfig = projectConfig.getEnvironments().get(environment);
        return envConfig != null ? envConfig.getState() : null;
    }

    /**
     * Sets the state configuration for a specific project and environment.
     */
    public void setStateConfig(String project, String environment, StateConfig stateConfig) {
        var projectConfig = getOrCreateProject(project);
        var envConfig = projectConfig.getEnvironments().computeIfAbsent(environment, k -> new EnvironmentConfig());
        envConfig.setState(stateConfig);
    }

    /**
     * Gets all configured projects.
     */
    public List<ProjectConfig> getProjects() {
        return config.getProjects();
    }

    /**
     * Gets all configured environments for a project.
     */
    public Map<String, EnvironmentConfig> getEnvironments(String project) {
        return findProject(project)
                .map(ProjectConfig::getEnvironments)
                .orElse(Map.of());
    }

    /**
     * Checks if a project is configured.
     */
    public boolean hasProject(String project) {
        return findProject(project).isPresent();
    }

    /**
     * Checks if an environment is configured for a project.
     */
    public boolean hasEnvironment(String project, String environment) {
        return findProject(project)
                .map(p -> p.getEnvironments().containsKey(environment))
                .orElse(false);
    }

    /**
     * Removes an environment configuration from a project.
     */
    public void removeEnvironment(String project, String environment) {
        findProject(project).ifPresent(projectConfig -> {
            projectConfig.getEnvironments().remove(environment);
            // Clean up empty project
            if (projectConfig.getEnvironments().isEmpty()) {
                config.getProjects().remove(projectConfig);
            }
        });
    }

    /**
     * Removes an entire project configuration.
     */
    public void removeProject(String project) {
        findProject(project).ifPresent(p -> config.getProjects().remove(p));
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
