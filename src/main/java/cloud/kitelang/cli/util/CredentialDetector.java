package cloud.kitelang.cli.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects available cloud provider credentials on the system.
 * Used to suggest credentials during project setup.
 */
@Slf4j
public class CredentialDetector {

    /**
     * Represents a detected credential option.
     */
    public record CredentialOption(
            String id,           // e.g., "default", "dev-profile"
            String displayName,  // e.g., "default (us-east-1)"
            String region        // detected region if available
    ) {}

    /**
     * Detects available AWS profiles from ~/.aws/credentials and ~/.aws/config.
     */
    public static List<CredentialOption> detectAwsProfiles() {
        var profiles = new ArrayList<CredentialOption>();
        var homeDir = System.getProperty("user.home");

        // Read profiles from credentials file
        var credentialsFile = Path.of(homeDir, ".aws", "credentials");
        var profileNames = new ArrayList<String>();

        if (Files.exists(credentialsFile)) {
            try {
                var content = Files.readString(credentialsFile);
                var pattern = Pattern.compile("^\\[([^\\]]+)\\]", Pattern.MULTILINE);
                var matcher = pattern.matcher(content);
                while (matcher.find()) {
                    profileNames.add(matcher.group(1));
                }
            } catch (IOException e) {
                log.debug("Failed to read AWS credentials file", e);
            }
        }

        // Also check config file for additional profiles
        var configFile = Path.of(homeDir, ".aws", "config");
        if (Files.exists(configFile)) {
            try {
                var content = Files.readString(configFile);
                var pattern = Pattern.compile("^\\[profile\\s+([^\\]]+)\\]", Pattern.MULTILINE);
                var matcher = pattern.matcher(content);
                while (matcher.find()) {
                    var name = matcher.group(1);
                    if (!profileNames.contains(name)) {
                        profileNames.add(name);
                    }
                }
                // Also check for [default] in config
                if (content.contains("[default]") && !profileNames.contains("default")) {
                    profileNames.add(0, "default");
                }
            } catch (IOException e) {
                log.debug("Failed to read AWS config file", e);
            }
        }

        // Build options with region info
        for (var name : profileNames) {
            var region = detectAwsProfileRegion(name);
            var displayName = region != null ? name + " (" + region + ")" : name;
            profiles.add(new CredentialOption(name, displayName, region));
        }

        return profiles;
    }

    /**
     * Detects the region configured for a specific AWS profile.
     */
    private static String detectAwsProfileRegion(String profileName) {
        var homeDir = System.getProperty("user.home");
        var configFile = Path.of(homeDir, ".aws", "config");

        if (!Files.exists(configFile)) {
            return null;
        }

        try {
            var content = Files.readString(configFile);
            var lines = content.lines().toList();

            // Find the profile section
            var sectionHeader = profileName.equals("default")
                    ? "[default]"
                    : "[profile " + profileName + "]";

            var inSection = false;
            for (var line : lines) {
                if (line.trim().equals(sectionHeader)) {
                    inSection = true;
                    continue;
                }
                if (inSection) {
                    if (line.startsWith("[")) {
                        break; // Next section
                    }
                    if (line.trim().startsWith("region")) {
                        var parts = line.split("=", 2);
                        if (parts.length == 2) {
                            return parts[1].trim();
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read AWS config for region", e);
        }

        return null;
    }

    /**
     * Detects available GCP projects from gcloud configuration.
     */
    public static List<CredentialOption> detectGcpProjects() {
        var projects = new ArrayList<CredentialOption>();
        var homeDir = System.getProperty("user.home");
        var gcloudDir = Path.of(homeDir, ".config", "gcloud");

        if (!Files.isDirectory(gcloudDir)) {
            return projects;
        }

        try {
            // Get active configuration
            var activeConfigFile = gcloudDir.resolve("active_config");
            var activeConfig = Files.exists(activeConfigFile)
                    ? Files.readString(activeConfigFile).trim()
                    : "default";

            // Read configurations directory
            var configsDir = gcloudDir.resolve("configurations");
            if (Files.isDirectory(configsDir)) {
                try (var stream = Files.list(configsDir)) {
                    stream.filter(p -> p.getFileName().toString().startsWith("config_"))
                            .forEach(configPath -> {
                                try {
                                    var configName = configPath.getFileName().toString()
                                            .substring("config_".length());
                                    var content = Files.readString(configPath);

                                    String project = null;
                                    String region = null;

                                    for (var line : content.lines().toList()) {
                                        if (line.startsWith("project = ")) {
                                            project = line.substring(10).trim();
                                        }
                                        if (line.startsWith("region = ")) {
                                            region = line.substring(9).trim();
                                        }
                                    }

                                    if (project != null) {
                                        var isActive = configName.equals(activeConfig);
                                        var displayName = project;
                                        if (region != null) {
                                            displayName += " (" + region + ")";
                                        }
                                        if (isActive) {
                                            displayName += " [active]";
                                        }
                                        projects.add(new CredentialOption(project, displayName, region));
                                    }
                                } catch (IOException e) {
                                    log.debug("Failed to read GCP config", e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.debug("Failed to detect GCP projects", e);
        }

        return projects;
    }

    /**
     * Detects available Azure subscriptions from az CLI configuration.
     */
    public static List<CredentialOption> detectAzureSubscriptions() {
        var subscriptions = new ArrayList<CredentialOption>();
        var homeDir = System.getProperty("user.home");
        var azureProfile = Path.of(homeDir, ".azure", "azureProfile.json");

        if (!Files.exists(azureProfile)) {
            return subscriptions;
        }

        try {
            var content = Files.readString(azureProfile);

            // Simple JSON parsing for subscriptions
            // Format: "subscriptions": [{"id": "...", "name": "...", "isDefault": true}, ...]
            var subsPattern = Pattern.compile(
                    "\"id\"\\s*:\\s*\"([^\"]+)\"[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*\"isDefault\"\\s*:\\s*(true|false)",
                    Pattern.DOTALL
            );
            var matcher = subsPattern.matcher(content);

            while (matcher.find()) {
                var id = matcher.group(1);
                var name = matcher.group(2);
                var isDefault = Boolean.parseBoolean(matcher.group(3));

                var displayName = name + (isDefault ? " [default]" : "");
                subscriptions.add(new CredentialOption(id, displayName, null));
            }
        } catch (IOException e) {
            log.debug("Failed to detect Azure subscriptions", e);
        }

        return subscriptions;
    }

    /**
     * Detects the default AWS region from environment or config.
     */
    public static String detectDefaultAwsRegion() {
        var region = System.getenv("AWS_REGION");
        if (region != null) return region;

        region = System.getenv("AWS_DEFAULT_REGION");
        if (region != null) return region;

        var profiles = detectAwsProfiles();
        if (!profiles.isEmpty()) {
            // Return region from default profile or first profile
            for (var p : profiles) {
                if (p.id().equals("default") && p.region() != null) {
                    return p.region();
                }
            }
            for (var p : profiles) {
                if (p.region() != null) {
                    return p.region();
                }
            }
        }

        return "us-east-1"; // Fallback
    }
}
