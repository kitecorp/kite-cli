package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.config.GlobalConfig;
import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.generator.ProjectStructureGenerator;
import cloud.kitelang.cli.interactive.InteractivePrompt;
import cloud.kitelang.cli.interactive.StateBackendWizard;
import cloud.kitelang.cli.util.CredentialDetector;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // Stores credential configuration per environment: Map<environment, Map<provider, CredentialConfig>>
    private final Map<String, Map<String, CredentialConfig>> environmentCredentials = new HashMap<>();

    /**
     * Credential configuration for a provider in an environment.
     */
    public record CredentialConfig(String type, String profile, String region, String project, String subscription) {
        public static CredentialConfig aws(String profile, String region) {
            return new CredentialConfig("aws", profile, region, null, null);
        }
        public static CredentialConfig gcp(String project, String region) {
            return new CredentialConfig("gcp", null, region, project, null);
        }
        public static CredentialConfig azure(String subscription) {
            return new CredentialConfig("azure", null, null, null, subscription);
        }
    }

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

            // Configure state management before creating project (if interactive)
            if (!skipInteractive) {
                Console.println();
                runStateBackendWizard(name);
            }

            // Show project summary and create
            Console.println();
            var stateBackend = getStateBackendType(name);
            Console.box(
                "Creating project: " + name,
                "       Providers: " + String.join(", ", providers),
                "    Environments: " + String.join(", ", environments),
                "        Location: " + projectDir.toAbsolutePath(),
                "   State backend: " + stateBackend,
                "    State config: " + GlobalConfig.getConfigPath()
            );
            Console.println();

            var generator = new ProjectStructureGenerator();
            generator.generate(projectDir, name, providers, environments, environmentCredentials, force);

            Console.success("Project created successfully");
            Console.println();

            Console.println(Console.bold("Next steps:"));
            Console.println("  cd " + projectDir.getFileName());
            Console.println("  kite config state        " + Console.cyan("# Configure state backend (if not done)"));
            Console.println("  kite providers install   " + Console.cyan("# Install providers"));
            Console.println("  kite validate            " + Console.cyan("# Check configuration"));
            Console.println("  kite plan                " + Console.cyan("# Preview changes"));
            Console.println("  kite apply               " + Console.cyan("# Provision resources"));

            return 0;
        } catch (Exception e) {
            Console.error(e.getMessage());
            log.debug("Failed to create project", e);
            return 1;
        }
    }

    /**
     * Runs interactive mode to gather project configuration from user input.
     */
    private void runInteractiveMode() throws IOException {
        // Try to use JLine interactive prompts
        try (var prompt = InteractivePrompt.create()) {
            if (prompt != null) {
                runInteractiveModeWithJLine(prompt);
                return;
            }
        }

        // Fallback to basic BufferedReader if terminal not available
        runInteractiveModeWithReader();
    }

    /**
     * Interactive mode using JLine prompts (arrow key navigation).
     */
    private void runInteractiveModeWithJLine(InteractivePrompt prompt) throws IOException {
        prompt.printHeader("Kite Project Setup");

        // Skip prompts if all values are provided
        if (projectName != null && providers != null) {
            if (!skipChecks) {
                checkProviderCredentialsSimple();
            }
            return;
        }

        projectName(prompt);

        providers(prompt);

        environmentSelection(prompt);

        credentialConfiguration(prompt);
    }

    /**
     * Configures cloud credentials for each environment.
     * Shows checkbox to select which environments to configure, with skip option.
     */
    private void credentialConfiguration(InteractivePrompt prompt) throws IOException {
        // Only configure for known providers
        var knownProviders = Arrays.stream(providers)
                .filter(p -> List.of("aws", "gcp", "azure").contains(p.toLowerCase()))
                .toList();

        if (knownProviders.isEmpty()) {
            return;
        }

        prompt.printSeparator();

        // Build options: all environments + skip
        var options = new ArrayList<InteractivePrompt.Option>();
        for (var env : environments) {
            options.add(new InteractivePrompt.Option(env, env, "Configure " + String.join(", ", knownProviders) + " credentials", true));
        }
        options.add(new InteractivePrompt.Option("_skip", "skip (configure later)", "Edit kitefile.yml manually", false));

        // Let user select which environments to configure
        var selected = new ArrayList<>(prompt.selectMany("Configure credentials for:", options));

        // If skip selected or nothing selected, skip configuration
        if (selected.isEmpty() || selected.contains("_skip")) {
            selected.remove("_skip");
            if (selected.isEmpty()) {
                prompt.printInfo("Skipped. Edit kitefile.yml later to configure credentials.");
                return;
            }
        }

        prompt.printSeparator();

        // Detect available credentials for each provider
        var awsProfiles = knownProviders.contains("aws") ? CredentialDetector.detectAwsProfiles() : List.<CredentialDetector.CredentialOption>of();
        var gcpProjects = knownProviders.contains("gcp") ? CredentialDetector.detectGcpProjects() : List.<CredentialDetector.CredentialOption>of();
        var azureSubs = knownProviders.contains("azure") ? CredentialDetector.detectAzureSubscriptions() : List.<CredentialDetector.CredentialOption>of();

        // Configure credentials for selected environments
        for (var env : selected) {
            prompt.printInfo("Environment: " + Console.bold(env));
            var creds = selectCredentialsForEnvironment(prompt, env, knownProviders,
                    awsProfiles, gcpProjects, azureSubs);
            environmentCredentials.put(env, creds);
            prompt.printSeparator();
        }

        prompt.printSuccess("Credentials configured for " + selected.size() + " environment(s)");
    }

    /**
     * Selects credentials for a single environment.
     */
    private Map<String, CredentialConfig> selectCredentialsForEnvironment(
            InteractivePrompt prompt,
            String envName,
            List<String> providerList,
            List<CredentialDetector.CredentialOption> awsProfiles,
            List<CredentialDetector.CredentialOption> gcpProjects,
            List<CredentialDetector.CredentialOption> azureSubs
    ) throws IOException {
        var creds = new HashMap<String, CredentialConfig>();

        for (var provider : providerList) {
            switch (provider.toLowerCase()) {
                case "aws" -> {
                    var config = selectAwsCredential(prompt, awsProfiles);
                    if (config != null) {
                        creds.put("aws", config);
                    }
                }
                case "gcp" -> {
                    var config = selectGcpCredential(prompt, gcpProjects);
                    if (config != null) {
                        creds.put("gcp", config);
                    }
                }
                case "azure" -> {
                    var config = selectAzureCredential(prompt, azureSubs);
                    if (config != null) {
                        creds.put("azure", config);
                    }
                }
            }
        }

        return creds;
    }

    /**
     * Selects AWS profile and region for an environment.
     */
    private CredentialConfig selectAwsCredential(
            InteractivePrompt prompt,
            List<CredentialDetector.CredentialOption> profiles
    ) throws IOException {
        String profile;
        String region;

        if (profiles.isEmpty()) {
            // No profiles detected, ask for manual input
            profile = prompt.input("AWS profile:", "default");
            region = prompt.input("AWS region:", CredentialDetector.detectDefaultAwsRegion());
        } else {
            // Build options from detected profiles
            var options = new ArrayList<InteractivePrompt.Option>();
            for (var p : profiles) {
                options.add(new InteractivePrompt.Option(p.id(), p.displayName()));
            }
            options.add(new InteractivePrompt.Option("_custom", "Enter custom profile name"));

            profile = prompt.selectOne("AWS profile:", options);

            if ("_custom".equals(profile)) {
                profile = prompt.input("AWS profile name:", "default");
            }

            // Get region - use detected region or ask
            var selectedProfile = profile; // final for lambda
            var detectedRegion = profiles.stream()
                    .filter(p -> p.id().equals(selectedProfile))
                    .findFirst()
                    .map(CredentialDetector.CredentialOption::region)
                    .orElse(null);

            if (detectedRegion != null) {
                region = detectedRegion;
                prompt.printInfo("  Region: " + region + " (from profile)");
            } else {
                region = prompt.input("AWS region:", CredentialDetector.detectDefaultAwsRegion());
            }
        }

        return CredentialConfig.aws(profile, region);
    }

    /**
     * Selects GCP project for an environment.
     */
    private CredentialConfig selectGcpCredential(
            InteractivePrompt prompt,
            List<CredentialDetector.CredentialOption> projects
    ) throws IOException {
        String project;
        String region = null;

        if (projects.isEmpty()) {
            // No projects detected, ask for manual input
            project = prompt.input("GCP project:", "my-project");
        } else {
            // Build options from detected projects
            var options = new ArrayList<InteractivePrompt.Option>();
            for (var p : projects) {
                options.add(new InteractivePrompt.Option(p.id(), p.displayName()));
            }
            options.add(new InteractivePrompt.Option("_custom", "Enter custom project ID"));

            project = prompt.selectOne("GCP project:", options);

            if ("_custom".equals(project)) {
                project = prompt.input("GCP project ID:", "my-project");
            }

            // Get region from detected project
            var selectedProject = project; // final for lambda
            region = projects.stream()
                    .filter(p -> p.id().equals(selectedProject))
                    .findFirst()
                    .map(CredentialDetector.CredentialOption::region)
                    .orElse(null);
        }

        return CredentialConfig.gcp(project, region);
    }

    /**
     * Selects Azure subscription for an environment.
     */
    private CredentialConfig selectAzureCredential(
            InteractivePrompt prompt,
            List<CredentialDetector.CredentialOption> subscriptions
    ) throws IOException {
        String subscription;

        if (subscriptions.isEmpty()) {
            // No subscriptions detected, ask for manual input
            subscription = prompt.input("Azure subscription ID:", "");
            if (subscription.isBlank()) {
                prompt.printWarning("No Azure subscription configured. Edit kitefile.yml later.");
                return null;
            }
        } else {
            // Build options from detected subscriptions
            var options = new ArrayList<InteractivePrompt.Option>();
            for (var s : subscriptions) {
                options.add(new InteractivePrompt.Option(s.id(), s.displayName()));
            }
            options.add(new InteractivePrompt.Option("_custom", "Enter custom subscription ID"));

            subscription = prompt.selectOne("Azure subscription:", options);

            if ("_custom".equals(subscription)) {
                subscription = prompt.input("Azure subscription ID:", "");
            }
        }

        return CredentialConfig.azure(subscription);
    }

    private void environmentSelection(InteractivePrompt prompt) throws IOException {
        // Environments selection - only ask if not already provided
        if (environments == null) {
            var selectedEnvs = new ArrayList<>(prompt.selectMany(
                    "Select environments:",
                    List.of(
                            new InteractivePrompt.Option("dev",     "dev     (Development)",        null, true),
                            new InteractivePrompt.Option("staging", "staging (Staging)",            null, true),
                            new InteractivePrompt.Option("prod",    "prod    (Production)",         null, true),
                            new InteractivePrompt.Option("custom",  "custom  (Enter custom names)", null, false)
                    )
            ));

            // If "custom" was selected, prompt for custom environment names
            if (selectedEnvs.remove("custom")) {
                var customEnvs = prompt.input("Enter environment names:", "local, qa");
                if (customEnvs != null && !customEnvs.isBlank()) {
                    for (var env : customEnvs.split("[,\\s]+")) {
                        var trimmed = env.trim().toLowerCase();
                        if (!trimmed.isBlank()) {
                            selectedEnvs.add(trimmed);
                        }
                    }
                }
            }

            if (selectedEnvs.isEmpty()) {
                environments = new String[]{"dev", "staging", "prod"};
            } else {
                environments = selectedEnvs.toArray(new String[0]);
            }

            // Print final list of environments
            Console.success("Environments: " + String.join(", ", environments));
        }
    }

    private void providers(InteractivePrompt prompt) throws IOException {
        // Providers selection - only ask if not already provided
        if (providers == null) {
            var selectedProviders = new ArrayList<>(prompt.selectMany(
                    "Select cloud providers:",
                    List.of(
                            new InteractivePrompt.Option("aws", "aws (Amazon Web Services)", null, true),
                            new InteractivePrompt.Option("gcp", "gcp (Google Cloud Platform)"),
                            new InteractivePrompt.Option("azure", "azure (Microsoft Azure)"),
                            new InteractivePrompt.Option("custom", "custom (Enter custom provider names)", null, false)
                    )
            ));

            // If "custom" was selected, prompt for custom provider names
            if (selectedProviders.remove("custom")) {
                var customProviders = prompt.input("Enter provider names:", "kubernetes, docker");
                if (customProviders != null && !customProviders.isBlank()) {
                    for (var p : customProviders.split("[,\\s]+")) {
                        var trimmed = p.trim().toLowerCase();
                        if (!trimmed.isBlank()) {
                            selectedProviders.add(trimmed);
                        }
                    }
                }
            }

            if (selectedProviders.isEmpty()) {
                providers = new String[]{"aws"};
            } else {
                providers = selectedProviders.toArray(new String[0]);
            }
        }

        // Check credentials for selected providers immediately after selection
        if (!skipChecks) {
            checkProviderCredentials(prompt);
        }
    }

    private void projectName(InteractivePrompt prompt) throws IOException {
        // Project name - only ask if not already provided
        if (projectName == null) {
            projectName = prompt.input("Project name:", "infra");
        }
        prompt.printSeparator();

        // Validate project name
        validateProjectName(projectName);
    }

    /**
     * Fallback interactive mode using BufferedReader.
     */
    private void runInteractiveModeWithReader() throws IOException {
        Console.println("Kite Project Setup");
        Console.println("==================");

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
            Console.print("Project name [" + defaultName + "]: ");
            String inputName = reader.readLine().trim();
            projectName = inputName.isEmpty() ? defaultName : inputName;
        }

        // Validate project name
        validateProjectName(projectName);

        // Providers selection - only ask if not already provided
        if (providers == null) {
            Console.println();
            Console.println("Select cloud providers (comma-separated):");
            Console.println("  1. aws (Amazon Web Services)");
            Console.println("  2. gcp (Google Cloud Platform)");
            Console.println("  3. azure (Microsoft Azure)");
            Console.print("Providers [aws]: ");
            String inputProviders = reader.readLine().trim();
            providers = inputProviders.isEmpty() ? new String[]{"aws"} : parseProviders(inputProviders);
        }

        // Check credentials for selected providers (unless skipped)
        if (!skipChecks) {
            checkProviderCredentials(reader);
        }
    }

    /**
     * Runs the state backend configuration wizard.
     */
    private void runStateBackendWizard(String projectName) {
        // Try JLine interactive prompt first
        try (var prompt = InteractivePrompt.create()) {
            if (prompt != null) {
                var config = GlobalConfig.load();
                var envList = environments != null ? Arrays.asList(environments) : null;
                var wizard = new StateBackendWizard(prompt, config, projectName, envList);
                wizard.run();
                Console.println();
                return;
            }
        } catch (Exception e) {
            log.debug("JLine prompt failed, trying fallback", e);
        }

        // Fallback to simple BufferedReader-based wizard
        runStateBackendWizardSimple(projectName);
    }

    /**
     * Simple fallback state backend wizard using BufferedReader.
     */
    private void runStateBackendWizardSimple(String projectName) {
        try {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            var config = GlobalConfig.load();

            Console.println("State Management Setup");
            Console.println("======================");
            Console.println();
            Console.println("Need a quick PostgreSQL instance? Run:");
            Console.println("  docker run --name postgres -dit -p 5432:5432 -e POSTGRES_PASSWORD=postgres -d postgres");
            Console.println();
            Console.println("How do you want to store infrastructure state?");
            Console.println("  1. self-managed (PostgreSQL - bring your own database)");
            Console.println("  2. skip (configure later with 'kite config state')");
            Console.print("Choice [2]: ");

            var choice = reader.readLine();
            if (choice == null || choice.isBlank() || choice.equals("2")) {
                Console.println("Skipped. Run 'kite config state' to configure later.");
                return;
            }

            if (!choice.equals("1")) {
                Console.println("Skipped. Run 'kite config state' to configure later.");
                return;
            }

            // Configure PostgreSQL for each environment
            var envList = environments != null ? environments : new String[]{"dev", "staging", "prod"};
            for (var env : envList) {
                Console.println();
                Console.println("--- Configuring: " + env + " ---");

                var schema = env.toLowerCase().replaceAll("[^a-z0-9]", "_");
                var defaultUrl = "jdbc:postgresql://localhost:5432/postgres?currentSchema=" + schema;

                Console.print("PostgreSQL URL [" + defaultUrl + "]: ");
                var url = reader.readLine();
                if (url == null || url.isBlank()) url = defaultUrl;

                Console.print("Username [postgres]: ");
                var username = reader.readLine();
                if (username == null || username.isBlank()) username = "postgres";

                Console.print("Password: ");
                var password = reader.readLine();
                if (password == null) password = "";

                var stateConfig = GlobalConfig.createPostgreSQLConfig(url, username, password);
                config.setStateConfig(projectName, env, stateConfig);
                Console.success("Configured " + env);
            }

            config.save();
            Console.success("Configuration saved to: " + GlobalConfig.getConfigPath());
            Console.println();
        } catch (Exception e) {
            log.debug("Failed to run simple state backend wizard", e);
            Console.println("Run 'kite config state' to configure state backend later.");
            Console.println();
        }
    }

    /**
     * Gets a human-readable description of the configured state backend.
     */
    private String getStateBackendType(String projectName) {
        try {
            var config = GlobalConfig.load();
            var envs = config.getEnvironments(projectName);
            if (envs.isEmpty()) {
                return "Not configured";
            }
            // Check the first configured environment's state type
            var firstEnv = envs.values().iterator().next();
            var state = firstEnv.getState();
            if (state == null) {
                return "Not configured";
            }
            return switch (state.getType()) {
                case "postgresql" -> "Self-managed PostgreSQL";
                case "kite-cloud" -> "Kite Cloud";
                default -> state.getType();
            };
        } catch (Exception e) {
            log.debug("Failed to get state backend type", e);
            return "Not configured";
        }
    }

    /**
     * Credential check using JLine prompt for consistent output.
     * Only checks known providers (aws, gcp, azure), skips custom providers.
     */
    private void checkProviderCredentials(InteractivePrompt prompt) {
        var knownProviders = List.of("aws", "gcp", "azure");
        var providersToCheck = Arrays.stream(providers)
                .filter(knownProviders::contains)
                .toList();

        if (providersToCheck.isEmpty()) {
            return; // No known providers to check
        }

        prompt.printSeparator();
        prompt.printInfo("Checking credentials...");

        for (String provider : providersToCheck) {
            var creds = detectCredentials(provider);
            if (creds != null) {
                prompt.printSuccess(Console.bold(provider.toUpperCase()) + ": " + creds);
                if (!creds.contains("region=") && !creds.contains("location=")) {
                    prompt.printWarning("No default region configured for " + provider.toUpperCase());
                }
            } else {
                prompt.printError(Console.bold(provider.toUpperCase()) + ": No credentials found");
            }
        }

        prompt.printSeparator();
    }

    /**
     * Simple credential check without interactive prompts.
     */
    private void checkProviderCredentialsSimple() {
        Console.println();
        Console.println("Checking credentials...");

        for (String provider : providers) {
            var creds = detectCredentials(provider);
            if (creds != null) {
                Console.println("  " + Console.green("\u2713") + " " + Console.bold(provider.toUpperCase()) + ": " + creds);
                if (!creds.contains("region=") && !creds.contains("location=")) {
                    warnMissingRegion(provider);
                }
            } else {
                Console.println("  " + Console.red("\u2717") + " " + Console.bold(provider.toUpperCase()) + ": No credentials found");
            }
        }
    }

    /**
     * Checks credentials for selected providers.
     */
    private void checkProviderCredentials(BufferedReader reader) throws IOException {
        Console.println();
        Console.println("Checking credentials...");

        for (String provider : providers) {
            var creds = detectCredentials(provider);
            if (creds != null) {
                Console.println("  " + Console.green("✓ ") + Console.bold(provider.toUpperCase()) + ": " + creds);
                // Check if region is missing (non-blocking warning)
                if (!creds.contains("region=") && !creds.contains("location=")) {
                    warnMissingRegion(provider);
                }
            } else {
                Console.println("  " + Console.red("✗ ") + Console.bold(provider.toUpperCase()) + ": No credentials found");
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
        Console.println();
        Console.println("    To configure " + provider.toUpperCase() + " credentials:");

        switch (provider.toLowerCase()) {
            case "aws" -> {
                Console.println("      Option 1: aws configure");
                Console.println("      Option 2: export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...");
                Console.println("      Option 3: export AWS_PROFILE=your-profile");
            }
            case "gcp" -> {
                Console.println("      Option 1: gcloud auth application-default login");
                Console.println("      Option 2: export GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json");
                Console.println("      Option 3: export GOOGLE_CLOUD_PROJECT=your-project");
            }
            case "azure" -> {
                Console.println("      Option 1: az login");
                Console.println("      Option 2: export AZURE_SUBSCRIPTION_ID=...");
            }
        }

        Console.print("    Press Enter to continue...");
        reader.readLine();
    }

    /**
     * Shows warning when region is not configured (non-blocking).
     */
    private void warnMissingRegion(String provider) {
        Console.println("    " + Console.yellow("⚠") + " No default region configured. To set one:");

        switch (provider.toLowerCase()) {
            case "aws" -> Console.println("      aws configure set region <region>  OR  export AWS_REGION=<region>");
            case "gcp" ->
                    Console.println("      gcloud config set compute/region <region>  OR  export CLOUDSDK_COMPUTE_REGION=<region>");
            case "azure" ->
                    Console.println("      az configure --defaults location=<location>  OR  export AZURE_DEFAULTS_LOCATION=<location>");
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
