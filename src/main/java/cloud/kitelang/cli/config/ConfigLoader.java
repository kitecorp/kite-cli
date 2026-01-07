package cloud.kitelang.cli.config;

import cloud.kitelang.cli.util.JacksonMappers;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and saves Kite CLI configuration from ~/.kite/config.yml
 */
@Slf4j
public class ConfigLoader {

    private static KiteConfig cachedConfig;

    /**
     * Get the config directory path (~/.kite)
     */
    public static Path configDir() {
        return Path.of(System.getProperty("user.home"), KiteConfig.CONFIG_DIR);
    }

    /**
     * Get the config file path (~/.kite/config.yml)
     */
    public static Path configFile() {
        return configDir().resolve(KiteConfig.CONFIG_FILE);
    }

    /**
     * Load configuration, using cached version if available.
     */
    public static KiteConfig load() {
        if (cachedConfig != null) {
            return cachedConfig;
        }
        cachedConfig = loadFromFile();
        return cachedConfig;
    }

    /**
     * Force reload configuration from file.
     */
    public static KiteConfig reload() {
        cachedConfig = null;
        return load();
    }

    /**
     * Load configuration from file.
     */
    private static KiteConfig loadFromFile() {
        var configPath = configFile();

        if (!Files.exists(configPath)) {
            log.debug("No config file found at {}", configPath);
            return KiteConfig.empty();
        }

        var data = JacksonMappers.yaml().readValue(configPath.toFile(), ConfigData.class);
        return toConfig(data);
    }

    /**
     * Save configuration to file.
     */
    public static void save(KiteConfig config) throws IOException {
        var configPath = configFile();
        Files.createDirectories(configPath.getParent());

        var data = toData(config);
        JacksonMappers.yaml().writeValue(configPath.toFile(), data);

        cachedConfig = config;
        log.debug("Saved config to {}", configPath);
    }

    /**
     * Get a specific config value by key path (e.g., "defaults.environment")
     */
    public static String get(String key) {
        var config = load();
        return switch (key) {
            case "defaults.environment" -> config.defaults().environment();
            case "defaults.provider" -> config.defaults().provider();
            case "defaults.output" -> config.defaults().output();
            case "verbosity" -> String.valueOf(config.verbosity());
            default -> {
                // Check provider-specific keys (e.g., "aws.profile")
                if (key.contains(".")) {
                    var parts = key.split("\\.", 2);
                    var providerConfig = config.provider(parts[0]);
                    yield switch (parts[1]) {
                        case "profile" -> providerConfig.profile();
                        case "region" -> providerConfig.region();
                        default -> providerConfig.extra().get(parts[1]);
                    };
                }
                yield null;
            }
        };
    }

    /**
     * Set a specific config value by key path.
     */
    public static void set(String key, String value) throws IOException {
        var config = load();

        // Build new config with updated value
        var newDefaults = config.defaults();
        var newVerbosity = config.verbosity();
        var newProviders = new HashMap<>(config.providers());

        switch (key) {
            case "defaults.environment" -> newDefaults = new KiteConfig.Defaults(
                    value, newDefaults.provider(), newDefaults.output());
            case "defaults.provider" -> newDefaults = new KiteConfig.Defaults(
                    newDefaults.environment(), value, newDefaults.output());
            case "defaults.output" -> newDefaults = new KiteConfig.Defaults(
                    newDefaults.environment(), newDefaults.provider(), value);
            case "verbosity" -> newVerbosity = Integer.parseInt(value);
            default -> {
                // Provider-specific keys
                if (key.contains(".")) {
                    var parts = key.split("\\.", 2);
                    var providerName = parts[0];
                    var providerKey = parts[1];
                    var existing = config.provider(providerName);

                    var newExtra = new HashMap<>(existing.extra());
                    var newProfile = existing.profile();
                    var newRegion = existing.region();

                    switch (providerKey) {
                        case "profile" -> newProfile = value;
                        case "region" -> newRegion = value;
                        default -> newExtra.put(providerKey, value);
                    }

                    newProviders.put(providerName, new KiteConfig.ProviderConfig(
                            newProfile, newRegion, newExtra));
                }
            }
        }

        var newConfig = new KiteConfig(newDefaults, newVerbosity, newProviders);
        save(newConfig);
    }

    /**
     * List all config keys and values.
     */
    public static Map<String, String> list() {
        var config = load();
        var result = new HashMap<String, String>();

        result.put("defaults.environment", config.defaults().environment());
        if (config.defaults().provider() != null) {
            result.put("defaults.provider", config.defaults().provider());
        }
        result.put("defaults.output", config.defaults().output());
        result.put("verbosity", String.valueOf(config.verbosity()));

        for (var entry : config.providers().entrySet()) {
            var name = entry.getKey();
            var pc = entry.getValue();
            if (pc.profile() != null) result.put(name + ".profile", pc.profile());
            if (pc.region() != null) result.put(name + ".region", pc.region());
            for (var extra : pc.extra().entrySet()) {
                result.put(name + "." + extra.getKey(), extra.getValue());
            }
        }

        return result;
    }

    // Internal data classes for YAML mapping
    private static KiteConfig toConfig(ConfigData data) {
        var defaults = data.defaults != null
                ? new KiteConfig.Defaults(
                data.defaults.getOrDefault("environment", "dev"),
                data.defaults.get("provider"),
                data.defaults.getOrDefault("output", "table"))
                : KiteConfig.Defaults.empty();

        var providers = new HashMap<String, KiteConfig.ProviderConfig>();
        if (data.providers != null) {
            for (var entry : data.providers.entrySet()) {
                var pd = entry.getValue();
                var extra = new HashMap<>(pd);
                extra.remove("profile");
                extra.remove("region");
                providers.put(entry.getKey(), new KiteConfig.ProviderConfig(
                        pd.get("profile"),
                        pd.get("region"),
                        extra
                ));
            }
        }

        return new KiteConfig(defaults, data.verbosity, providers);
    }

    private static ConfigData toData(KiteConfig config) {
        var data = new ConfigData();
        data.defaults = new HashMap<>();
        data.defaults.put("environment", config.defaults().environment());
        if (config.defaults().provider() != null) {
            data.defaults.put("provider", config.defaults().provider());
        }
        data.defaults.put("output", config.defaults().output());
        data.verbosity = config.verbosity();

        data.providers = new HashMap<>();
        for (var entry : config.providers().entrySet()) {
            var pd = new HashMap<String, String>();
            var pc = entry.getValue();
            if (pc.profile() != null) pd.put("profile", pc.profile());
            if (pc.region() != null) pd.put("region", pc.region());
            pd.putAll(pc.extra());
            if (!pd.isEmpty()) {
                data.providers.put(entry.getKey(), pd);
            }
        }

        return data;
    }

    private static class ConfigData {
        public Map<String, String> defaults;
        public int verbosity = 1;
        public Map<String, Map<String, String>> providers;
    }
}
