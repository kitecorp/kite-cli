package cloud.kitelang.cli.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Global configuration for Kite CLI.
 * Loaded from ~/.kite/config.yml
 */
public record KiteConfig(
        Defaults defaults,
        int verbosity,
        Map<String, ProviderConfig> providers
) {
    public static final String CONFIG_DIR = ".kite";
    public static final String CONFIG_FILE = "config.yml";

    /**
     * Default values when no config exists.
     */
    public static KiteConfig empty() {
        return new KiteConfig(
                Defaults.empty(),
                1,
                new HashMap<>()
        );
    }

    /**
     * Get a provider-specific config.
     */
    public ProviderConfig provider(String name) {
        return providers.getOrDefault(name, ProviderConfig.empty());
    }

    /**
     * Default settings for commands.
     */
    public record Defaults(
            String environment,
            String provider,
            String output
    ) {
        public static Defaults empty() {
            return new Defaults("dev", null, "table");
        }
    }

    /**
     * Provider-specific configuration (credentials, regions, etc.)
     */
    public record ProviderConfig(
            String profile,
            String region,
            Map<String, String> extra
    ) {
        public static ProviderConfig empty() {
            return new ProviderConfig(null, null, new HashMap<>());
        }
    }
}
