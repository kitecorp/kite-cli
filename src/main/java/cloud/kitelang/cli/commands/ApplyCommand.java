package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.config.GlobalConfig;
import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.interactive.InteractivePrompt;
import cloud.kitelang.engine.Engine;
import cloud.kitelang.engine.kitefile.Dependencies;
import cloud.kitelang.engine.kitefile.KiteInjector;
import cloud.kitelang.engine.kitefile.Kitefile;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Apply (provision) infrastructure changes to the cloud.
 * Creates or updates resources to match the desired state.
 */
@Command(
        name = "apply",
        aliases = {"provision"},
        description = "Apply infrastructure changes to provision resources",
        footer = {
                "",
                "Examples:",
                "  kite apply -e dev                     Apply changes to dev environment",
                "  kite apply -e prod                    Apply changes to production",
                "  kite apply -e dev -s Backend          Apply only the Backend stack",
                "  kite apply -e dev -p aws              Apply only AWS resources",
                "  kite apply -e dev -y                  Skip confirmation prompt",
                "  kite apply -e dev --dry-run           Preview without applying",
                "  kite apply -e prod --parallelism 5    Limit concurrent operations"
        },
        mixinStandardHelpOptions = true
)
@Slf4j
public class ApplyCommand implements Callable<Integer> {


    @Option(
            names = {"-e", "--env"},
            paramLabel = "ENV",
            description = "Target environment (default: dev)",
            defaultValue = "dev"
    )
    private String environment;

    @Option(
            names = {"-p", "--provider"},
            paramLabel = "PROVIDER",
            description = "Cloud provider: aws,gcp,azure,all (default: all)",
            defaultValue = "all"
    )
    private String provider;

    @Option(
            names = {"-s", "--stack"},
            paramLabel = "STACK",
            description = "Specific stack to apply (e.g., Backend, Frontend). If omitted, applies all stacks."
    )
    private String stack;

    @Option(
            names = {"-f", "--file"},
            paramLabel = "FILE",
            description = "Override: apply a specific .kite file instead of environment stacks"
    )
    private File overrideFile;

    @Option(
            names = {"-y", "--yes", "--approve", "--auto-approve"},
            description = "Skip interactive approval"
    )
    private boolean autoApprove;

    @Option(
            names = {"--dry-run"},
            description = "Preview changes without applying"
    )
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            log.info("Applying infrastructure changes...");
            log.info("Environment: {}", environment);
            log.info("Provider: {}", provider);

            // Load kitefile.yml for provider and project configuration
            var kitefile = loadKitefile();
            var environmentsDir = kitefile.config().environmentsDir();

            // Check state configuration for this environment
            var stateConfig = getStateConfiguration();
            if (stateConfig == null) {
                return 1;
            }

            // Determine what files to apply
            var kiteFiles = determineKiteFiles(environmentsDir);
            Console.println("Applying: " + kiteFiles.size() + " file(s)");

            if (dryRun) {
                Console.println("\nDRY RUN: No changes will be made");
                Console.println("Run 'kite plan --env " + environment + "' to see detailed changes");
                return 0;
            }

            // Load all .kite file contents
            var source = new StringBuilder();
            for (var file : kiteFiles) {
                source.append(Files.readString(file));
                source.append("\n");
            }

            // Use first file path for tracking (when single file) or environment path (when multiple)
            var filePath = kiteFiles.size() == 1
                    ? kiteFiles.get(0).toString()
                    : environmentsDir + "/" + environment;

            // Build Engine with kitefile, environment, and credentials
            var engineBuilder = Engine.builder()
                    .withKitefile(kitefile)
                    .withEnvironment(environment);

            // Pass database credentials from state configuration
            if ("postgresql".equals(stateConfig.getType())) {
                var projectName = getProjectName();
                engineBuilder.withDatabaseCredentials(
                        stateConfig.getUrl(),
                        stateConfig.getUsername(),
                        stateConfig.getEffectivePassword(projectName, environment)
                );
            }

            try (var engine = engineBuilder.build()) {
                var resources = engine.parse(source.toString(), filePath);
                var plan = engine.plan(resources);

                // Show plan details and summary
                var summary = engine.getPlanSummary(plan);
                Console.println();
                engine.printPlanDetails(plan);
                Console.println();
                engine.printPlanSummary(plan);

                // Request confirmation unless auto-approve
                if (!autoApprove && summary.hasChanges()) {
                    try (var prompt = InteractivePrompt.create()) {
                        if (prompt != null) {
                            var confirmed = prompt.confirm("Do you want to perform these actions?", false);
                            if (!confirmed) {
                                Console.println("Apply cancelled.");
                                return 0;
                            }
                        } else {
                            log.warn("No interactive terminal available, proceeding without confirmation");
                        }
                    }
                }

                // Apply the plan
                Console.println("\nApplying changes...");
                engine.apply(plan);

                // Print outputs
                engine.printOutputs();
            }

            Console.println("\n✓ Apply completed successfully");
            return 0;

        } catch (Exception e) {
            log.error("Failed to apply changes", e);
            Console.error(e.getMessage());
            return 1;
        }
    }

    /**
     * Determines the .kite files to apply based on options.
     * Priority: --file override > --stack > all stacks in environment
     *
     * @param environmentsDir Base directory for environments (from kitefile.yml)
     */
    private List<Path> determineKiteFiles(String environmentsDir) throws IOException {
        // If explicit file override, use that
        if (overrideFile != null) {
            if (!overrideFile.exists()) {
                throw new IOException("File not found: " + overrideFile);
            }
            return List.of(overrideFile.toPath());
        }

        // If specific stack requested
        if (stack != null) {
            var stackFile = Path.of(environmentsDir, environment, stack + ".kite");
            if (!Files.exists(stackFile)) {
                throw new IOException("Stack not found: " + stackFile);
            }
            return List.of(stackFile);
        }

        // Default: all stacks in the environment
        var envDir = Path.of(environmentsDir, environment);
        if (!Files.exists(envDir)) {
            throw new IOException("Environment not found: " + envDir);
        }

        var stacks = findStackFiles(envDir);
        if (stacks.isEmpty()) {
            throw new IOException("No .kite files found in: " + envDir);
        }

        return stacks;
    }

    /**
     * Finds all .kite stack files in an environment directory.
     */
    private List<Path> findStackFiles(Path envDir) throws IOException {
        try (Stream<Path> files = Files.list(envDir)) {
            return files
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".kite"))
                .sorted()
                .toList();
        }
    }

    /**
     * Gets the project name from the current directory.
     */
    private String getProjectName() {
        return Path.of("").toAbsolutePath().getFileName().toString();
    }

    /**
     * Loads provider configuration from kitefile.yml.
     * Falls back to empty dependencies if kitefile.yml doesn't exist.
     */
    private Kitefile loadKitefile() {
        try {
            if (Files.exists(Path.of("kitefile.yml"))) {
                return KiteInjector.createkitefile();
            }
            log.debug("No kitefile.yml found, using empty dependencies");
            return new Kitefile(new Dependencies(List.of()));
        } catch (IOException e) {
            log.warn("Failed to load kitefile.yml: {}", e.getMessage());
            return new Kitefile(new Dependencies(List.of()));
        }
    }

    /**
     * Gets the state configuration for the target environment.
     * Returns the StateConfig if valid, null otherwise (with error messages printed).
     */
    private GlobalConfig.StateConfig getStateConfiguration() {
        try {
            var projectName = getProjectName();
            var config = GlobalConfig.load();
            var stateConfig = config.getStateConfig(projectName, environment);

            if (stateConfig == null) {
                Console.error("No state configuration found for project '" + projectName + "' environment '" + environment + "'");
                Console.println();
                Console.println("Configure state backend by running:");
                Console.println("  kite config state");
                Console.println();
                Console.println("Or use non-interactive mode:");
                Console.println("  kite config state -e " + environment + " --url jdbc:postgresql://localhost:5432/postgres?currentSchema=" + environment);
                return null;
            }

            // Check if password is available for PostgreSQL
            if ("postgresql".equals(stateConfig.getType())) {
                if (!stateConfig.hasPassword(projectName, environment)) {
                    var envVarName = GlobalConfig.getPasswordEnvVarName(projectName, environment);
                    Console.warning("No password configured for environment '" + environment + "'");
                    Console.println("Set password via:");
                    Console.println("  - Config: kite config state -e " + environment + " --password");
                    Console.println("  - Env var: export " + envVarName + "=<password>");
                    Console.println();
                }

                log.debug("Using state backend: {} (user: {})", stateConfig.getUrl(), stateConfig.getUsername());
            } else if ("kite-cloud".equals(stateConfig.getType())) {
                log.debug("Using Kite Cloud state backend");
            }

            return stateConfig;
        } catch (IOException e) {
            log.debug("Failed to load state configuration", e);
            return null;
        }
    }
}
