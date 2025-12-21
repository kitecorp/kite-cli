package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.generator.ProjectStructureGenerator;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Create a new Kite project with the standard multi-cloud structure.
 * Interactive mode is the default - use -y to skip prompts.
 */
@Command(
        name = "new",
        aliases = {"create"},
        description = "Create a new Kite project",
        footer = {
                "",
                "Examples:",
                "  kite new                              Interactive mode (prompts for name/providers)",
                "  kite new my-app                       Create 'my-app' with interactive provider selection",
                "  kite new my-app -y                    Create with defaults (no prompts)",
                "  kite new my-app -p aws,gcp            Create with specific providers",
                "  kite new my-app -e dev,prod           Create with only dev and prod environments",
                "  kite new my-app -d ~/projects         Create in specific directory",
                "  kite new my-app -f                    Overwrite existing files"
        },
        mixinStandardHelpOptions = true
)
@Slf4j
public class NewCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            paramLabel = "NAME",
            description = "Project name",
            arity = "0..1"
    )
    private String projectName;

    @Option(
            names = {"-d", "--directory"},
            paramLabel = "DIR",
            description = "Target directory (default: ./<NAME>)"
    )
    private File targetDirectory;

    @Option(
            names = {"-p", "--providers"},
            paramLabel = "PROVIDERS",
            arity = "1",
            description = "Cloud providers: aws, gcp, azure",
            split = ","
    )
    private String[] providers;

    @Option(
            names = {"-e", "--env"},
            paramLabel = "ENVS",
            arity = "1",
            description = "Environments (default: dev,staging,prod)",
            split = ","
    )
    private String[] environments;

    @Option(
            names = {"-f", "--force"},
            description = "Overwrite existing files"
    )
    private boolean force;

    @Option(
            names = {"-y", "--yes"},
            description = "Skip interactive prompts and credential checks, use defaults"
    )
    private boolean skipInteractive;

    @Option(
            names = {"--skip-checks"},
            description = "Skip credential and CLI tool checks"
    )
    private boolean skipChecks;

    @Override
    public Integer call() {
        try {
            // Interactive mode is the default unless -y is specified
            if (!skipInteractive) {
                runInteractiveMode();
            }

            // Apply defaults for any unset values
            if (providers == null) {
                providers = new String[]{"aws"};
            }
            if (environments == null) {
                environments = new String[]{"dev", "staging", "prod"};
            }

            var projectDir = determineProjectDirectory();
            var name = determineProjectName(projectDir);

            // Validate project name (also validates CLI argument)
            validateProjectName(name);

            System.out.println();
            System.out.println("Creating project: " + name);
            System.out.println("       Providers: " + String.join(", ", providers));
            System.out.println("        Location: " + projectDir.toAbsolutePath());
            System.out.println();

            var generator = new ProjectStructureGenerator();
            generator.generate(projectDir, name, providers, environments, force);

            System.out.println("✓ Project created successfully");
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  cd " + projectDir.getFileName());
            System.out.println("  kite providers install   # Install providers");
            System.out.println("  kite validate            # Check configuration");
            System.out.println("  kite plan                # Preview changes");
            System.out.println("  kite apply               # Provision resources");

            return 0;
        } catch (Exception e) {
            System.err.println("✗ Error: " + e.getMessage());
            log.debug("Failed to create project", e);
            return 1;
        }
    }

    /**
     * Runs interactive mode to gather project configuration from user input.
     */
    private void runInteractiveMode() throws IOException {
        System.out.println("Kite Project Setup");
        System.out.println("==================");

        var reader = new BufferedReader(new InputStreamReader(System.in));

        // Skip prompts if all values are provided
        if (projectName != null && providers != null) {
            if (!skipInteractive && !skipChecks) {
                checkProviderCredentials(reader);
            }
            return;
        }

        // Project name - only ask if not already provided
        if (projectName == null) {
            String defaultName = "infra";
            System.out.print("Project name [" + defaultName + "]: ");
            String inputName = reader.readLine().trim();
            projectName = inputName.isEmpty() ? defaultName : inputName;
        }

        // Validate project name
        validateProjectName(projectName);

        // Providers selection - only ask if not already provided
        if (providers == null) {
            System.out.println();
            System.out.println("Select cloud providers (comma-separated):");
            System.out.println("  1. aws (Amazon Web Services)");
            System.out.println("  2. gcp (Google Cloud Platform)");
            System.out.println("  3. azure (Microsoft Azure)");
            System.out.print("Providers [aws]: ");
            String inputProviders = reader.readLine().trim();
            providers = inputProviders.isEmpty() ? new String[]{"aws"} : parseProviders(inputProviders);
        }

        // Check credentials for selected providers (unless skipped)
        if (!skipChecks) {
            checkProviderCredentials(reader);
        }

        // Environments use default (dev/staging/prod) unless specified via -e flag
    }

    /**
     * Checks credentials for selected providers.
     */
    private void checkProviderCredentials(BufferedReader reader) throws IOException {
        System.out.println();
        System.out.println("Checking credentials...");

        for (String provider : providers) {
            var creds = detectCredentials(provider);
            if (creds != null) {
                System.out.println("  ✓ " + provider.toUpperCase() + ": " + creds);
                // Check if region is missing (non-blocking warning)
                if (!creds.contains("region=") && !creds.contains("location=")) {
                    warnMissingRegion(provider);
                }
            } else {
                System.out.println("  ✗ " + provider.toUpperCase() + ": No credentials found");
                promptForCredentials(provider, reader);
            }
        }
    }

    /**
     * Detects credentials for a provider, returns description if found or null if not.
     */
    private String detectCredentials(String provider) {
        return switch (provider.toLowerCase()) {
            case "aws" -> detectAwsCredentials();
            case "gcp" -> detectGcpCredentials();
            case "azure" -> detectAzureCredentials();
            default -> null;
        };
    }

    private String detectAwsCredentials() {
        var region = detectAwsRegion();
        var regionSuffix = region != null ? ", region=" + region : "";

        var profile = System.getenv("AWS_PROFILE");
        if (profile != null) {
            return "profile=" + profile + regionSuffix;
        }

        var accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        if (accessKey != null) {
            return "access key from environment" + regionSuffix;
        }

        // Check for AWS credentials file with profiles
        var credentialsFile = Path.of(System.getProperty("user.home"), ".aws", "credentials");
        if (Files.exists(credentialsFile)) {
            try {
                var content = Files.readString(credentialsFile);
                // Count profiles
                var profiles = content.lines()
                        .filter(line -> line.matches("^\\[.+\\]$"))
                        .map(line -> line.substring(1, line.length() - 1))
                        .toList();
                if (profiles.isEmpty()) {
                    return null;
                }
                if (profiles.contains("default")) {
                    return "profile=default" + (profiles.size() > 1 ? " (+" + (profiles.size() - 1) + " more)" : "") + regionSuffix;
                }
                return "profile=" + profiles.get(0) + (profiles.size() > 1 ? " (+" + (profiles.size() - 1) + " more)" : "") + regionSuffix;
            } catch (IOException e) {
                return "~/.aws/credentials" + regionSuffix;
            }
        }

        return null;
    }

    private String detectAwsRegion() {
        // Check environment variables first
        var region = System.getenv("AWS_REGION");
        if (region != null) return region;

        region = System.getenv("AWS_DEFAULT_REGION");
        if (region != null) return region;

        // Check ~/.aws/config
        var configFile = Path.of(System.getProperty("user.home"), ".aws", "config");
        if (Files.exists(configFile)) {
            try {
                var content = Files.readString(configFile);
                for (var line : content.lines().toList()) {
                    if (line.trim().startsWith("region = ")) {
                        return line.trim().substring(9);
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }
        return null;
    }

    private String detectGcpCredentials() {
        var region = detectGcpRegion();
        var regionSuffix = region != null ? ", region=" + region : "";

        var project = System.getenv("GOOGLE_CLOUD_PROJECT");
        if (project != null) {
            return "project=" + project + regionSuffix;
        }

        var credsFile = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (credsFile != null) {
            return "service account from GOOGLE_APPLICATION_CREDENTIALS" + regionSuffix;
        }

        // Check for gcloud CLI configuration
        var gcloudConfig = Path.of(System.getProperty("user.home"), ".config", "gcloud");
        if (Files.isDirectory(gcloudConfig)) {
            try {
                // Read active configuration name
                var activeConfigFile = gcloudConfig.resolve("active_config");
                var configName = Files.exists(activeConfigFile)
                        ? Files.readString(activeConfigFile).trim()
                        : "default";

                // Read the configuration file
                var configFile = gcloudConfig.resolve("configurations/config_" + configName);
                if (Files.exists(configFile)) {
                    var content = Files.readString(configFile);
                    // Extract project
                    for (var line : content.lines().toList()) {
                        if (line.startsWith("project = ")) {
                            return "project=" + line.substring(10).trim() + regionSuffix;
                        }
                    }
                }

                // Check for application default credentials
                var adcFile = gcloudConfig.resolve("application_default_credentials.json");
                if (Files.exists(adcFile)) {
                    return "application default credentials" + regionSuffix;
                }
            } catch (IOException e) {
                return "gcloud CLI configured" + regionSuffix;
            }
        }

        return null;
    }

    private String detectGcpRegion() {
        // Check environment variable
        var region = System.getenv("CLOUDSDK_COMPUTE_REGION");
        if (region != null) return region;

        // Check gcloud config
        var gcloudConfig = Path.of(System.getProperty("user.home"), ".config", "gcloud");
        try {
            var activeConfigFile = gcloudConfig.resolve("active_config");
            var configName = Files.exists(activeConfigFile)
                    ? Files.readString(activeConfigFile).trim()
                    : "default";

            var configFile = gcloudConfig.resolve("configurations/config_" + configName);
            if (Files.exists(configFile)) {
                var content = Files.readString(configFile);
                for (var line : content.lines().toList()) {
                    if (line.trim().startsWith("region = ")) {
                        return line.trim().substring(9);
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    private String detectAzureCredentials() {
        var location = detectAzureLocation();
        var locationSuffix = location != null ? ", location=" + location : "";

        var subscription = System.getenv("AZURE_SUBSCRIPTION_ID");
        if (subscription != null) {
            return "subscription=" + subscription + locationSuffix;
        }

        // Check for Azure CLI profile with subscriptions
        var azureProfile = Path.of(System.getProperty("user.home"), ".azure", "azureProfile.json");
        if (Files.exists(azureProfile)) {
            try {
                var content = Files.readString(azureProfile);
                if (content.contains("\"subscriptions\": []") || !content.contains("\"id\":")) {
                    return null; // Empty subscriptions = not logged in
                }
                // Extract subscription name if possible
                var nameMatch = content.indexOf("\"name\": \"");
                if (nameMatch > 0) {
                    var start = nameMatch + 9;
                    var end = content.indexOf("\"", start);
                    if (end > start) {
                        return "subscription=" + content.substring(start, end) + locationSuffix;
                    }
                }
                return "Azure CLI logged in" + locationSuffix;
            } catch (IOException e) {
                return null;
            }
        }

        return null;
    }

    private String detectAzureLocation() {
        // Check environment variable
        var location = System.getenv("AZURE_DEFAULTS_LOCATION");
        if (location != null) return location;

        // Check Azure CLI config
        var azureConfig = Path.of(System.getProperty("user.home"), ".azure", "config");
        if (Files.exists(azureConfig)) {
            try {
                var content = Files.readString(azureConfig);
                for (var line : content.lines().toList()) {
                    if (line.trim().startsWith("location = ")) {
                        return line.trim().substring(11);
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }
        return null;
    }

    /**
     * Prompts user to configure credentials for a provider.
     */
    private void promptForCredentials(String provider, BufferedReader reader) throws IOException {
        System.out.println();
        System.out.println("    To configure " + provider.toUpperCase() + " credentials:");

        switch (provider.toLowerCase()) {
            case "aws" -> {
                System.out.println("      Option 1: aws configure");
                System.out.println("      Option 2: export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...");
                System.out.println("      Option 3: export AWS_PROFILE=your-profile");
            }
            case "gcp" -> {
                System.out.println("      Option 1: gcloud auth application-default login");
                System.out.println("      Option 2: export GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json");
                System.out.println("      Option 3: export GOOGLE_CLOUD_PROJECT=your-project");
            }
            case "azure" -> {
                System.out.println("      Option 1: az login");
                System.out.println("      Option 2: export AZURE_SUBSCRIPTION_ID=...");
            }
        }

        System.out.print("    Press Enter to continue...");
        reader.readLine();
    }

    /**
     * Shows warning when region is not configured (non-blocking).
     */
    private void warnMissingRegion(String provider) {
        System.out.println("    ⚠ No default region configured. To set one:");

        switch (provider.toLowerCase()) {
            case "aws" -> System.out.println("      aws configure set region <region>  OR  export AWS_REGION=<region>");
            case "gcp" -> System.out.println("      gcloud config set compute/region <region>  OR  export CLOUDSDK_COMPUTE_REGION=<region>");
            case "azure" -> System.out.println("      az configure --defaults location=<location>  OR  export AZURE_DEFAULTS_LOCATION=<location>");
        }
    }

    /**
     * Validates project name for invalid characters.
     */
    private void validateProjectName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name cannot be empty");
        }

        // Allow alphanumeric, dash, underscore, dot
        if (!name.matches("^[a-zA-Z][a-zA-Z0-9._-]*$")) {
            throw new IllegalArgumentException(
                "Invalid project name '" + name + "'. " +
                "Must start with a letter and contain only letters, numbers, dashes, underscores, or dots."
            );
        }

        // Check for reserved names
        var reserved = List.of(".", "..", "con", "prn", "aux", "nul");
        if (reserved.contains(name.toLowerCase())) {
            throw new IllegalArgumentException("'" + name + "' is a reserved name");
        }
    }

    /**
     * Parses provider input, handling both names and numbers.
     */
    private String[] parseProviders(String input) {
        var providerMap = java.util.Map.of(
            "1", "aws",
            "2", "gcp",
            "3", "azure"
        );

        List<String> result = new ArrayList<>();
        for (String part : input.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (providerMap.containsKey(trimmed)) {
                result.add(providerMap.get(trimmed));
            } else if (Arrays.asList("aws", "gcp", "azure").contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? new String[]{"aws"} : result.toArray(new String[0]);
    }

    private Path determineProjectDirectory() {
        if (targetDirectory != null) {
            return targetDirectory.toPath();
        }

        if (projectName != null && !projectName.isBlank()) {
            return Paths.get(projectName);
        }

        return Paths.get(".");
    }

    private String determineProjectName(Path projectDir) {
        if (projectName != null && !projectName.isBlank()) {
            return projectName;
        }

        return projectDir.toAbsolutePath().getFileName().toString();
    }
}
