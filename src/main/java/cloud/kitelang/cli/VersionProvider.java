package cloud.kitelang.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.util.Properties;

/**
 * Provides version information from build-generated properties file.
 */
public class VersionProvider implements CommandLine.IVersionProvider {

    private static final String VERSION_PROPERTIES = "/version.properties";
    private static String cachedVersion;

    @Override
    public String[] getVersion() {
        return new String[]{"kite " + getVersionString()};
    }

    /**
     * Returns the version string (e.g., "0.2.3").
     * Can be called from other parts of the application.
     */
    public static String getVersionString() {
        if (cachedVersion == null) {
            cachedVersion = loadVersion();
        }
        return cachedVersion;
    }

    private static String loadVersion() {
        try (var stream = VersionProvider.class.getResourceAsStream(VERSION_PROPERTIES)) {
            if (stream != null) {
                var props = new Properties();
                props.load(stream);
                return props.getProperty("version", "unknown");
            }
        } catch (IOException e) {
            // Fall through to default
        }
        return "unknown";
    }
}
