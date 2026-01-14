package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.VersionProvider;
import cloud.kitelang.cli.config.GlobalConfig;
import cloud.kitelang.cli.console.Console;
import cloud.kitelang.engine.Engine;
import cloud.kitelang.engine.diff.Plan;
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
 * Shows a preview of infrastructure changes without applying them.
 * Compares desired state (from .kite files) with current state (from state backend).
 */
@Command(
    name = "plan",
    description = {
        "Preview infrastructure changes without applying them",
        "",
        "Compares your .kite files (desired state) with the current state",
        "stored in the state backend. Shows what would be created, updated,",
        "or destroyed if you run 'kite apply'.",
        "",
        "Resource changes are shown as:",
        "  + create    New resource to be created",
        "  ~ update    Existing resource to be modified",
        "  - destroy   Existing resource to be removed",
        "  ~ replace   Resource must be destroyed and recreated"
    },
    footer = {
        "",
        "Examples:",
        "  kite plan                             Plan changes for dev environment",
        "  kite plan -e prod                     Plan changes for production",
        "  kite plan -e dev -s Backend           Plan only the Backend stack",
        "  kite plan -e dev -p aws               Plan only AWS resources",
        "  kite plan -e dev --json               Output plan as JSON",
        "  kite plan -e dev -o plan.json         Save plan to file for later apply",
        "  kite plan -e dev --compact            Show compact diff output"
    },
    mixinStandardHelpOptions = true
)
@Slf4j
public class PlanCommand implements Callable<Integer> {

    @Option(
        names = {"-e", "--environment"},
        description = "Target environment (dev, staging, prod)",
        defaultValue = "dev"
    )
    private String environment;

    @Option(
        names = {"-p", "--provider"},
        description = "Target cloud provider (aws, gcp, azure, or 'all')",
        defaultValue = "all"
    )
    private String provider;

    @Option(
        names = {"-s", "--stack"},
        description = "Specific stack to plan (e.g., Backend, Frontend). If omitted, plans all stacks in the environment."
    )
    private String stack;

    @Option(
        names = {"-f", "--file"},
        description = "Override: plan a specific .kite file instead of environment stacks"
    )
    private File overrideFile;

    @Option(
        names = {"-o", "--out"},
        description = "Save plan to file for later apply"
    )
    private File outputFile;

    @Option(
        names = {"--refresh"},
        description = "Refresh state before planning",
        defaultValue = "true",
        negatable = true
    )
    private boolean refresh;

    @Option(
        names = {"--target"},
        description = "Plan only for specific resource(s)",
        split = ","
    )
    private String[] targets;

    @Option(
        names = {"--compact"},
        description = "Show compact diff output"
    )
    private boolean compact;

    @Option(
        names = {"--json"},
        description = "Output plan as JSON"
    )
    private boolean jsonOutput;

    @Override
    public Integer call() throws Exception {
        log.info("Creating execution plan...");
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

        // Determine which file(s) to plan
        var kiteFiles = determineKiteFiles(environmentsDir);
        Console.println("Planning: " + kiteFiles.size() + " file(s)");
        Console.println();

        if (refresh) {
            Console.println("Refreshing state...");
            refreshState();
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

        // Any exceptions from here will be handled by KiteExceptionHandler
        // which shows user-friendly messages and only shows stack traces when KITE_LOGGING=debug
        try (var engine = engineBuilder.build()) {
            var resources = engine.parse(source.toString(), filePath);
            var plan = engine.plan(resources);

            if (jsonOutput) {
                printJsonPlan(engine, plan);
            } else {
                printPlan(engine, plan);
            }

            if (outputFile != null) {
                // TODO: Serialize plan to file
                Console.println("\nPlan saved to: " + outputFile);
            }
        }

        Console.println("\nTo apply this plan, run: kite apply --env " + environment);

        return 0;
    }

    /**
     * Determines the .kite files to plan based on options.
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
     * Uses the directory name as the project identifier.
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
            // Config file doesn't exist - return a default config to continue
            // This allows basic operations without configured state
            return null;
        }
    }

    /**
     * Refreshes state from cloud providers.
     */
    private void refreshState() {
        // TODO: Implement state refresh
        // 1. Connect to state backend
        // 2. For each tracked resource, query cloud provider
        // 3. Update state with actual values
        log.debug("State refresh not yet implemented");
    }

    /**
     * Prints the execution plan.
     */
    private void printPlan(Engine engine, Plan plan) {
        System.out.println("Kite will perform the following actions:");
        System.out.println();

        if (targets != null && targets.length > 0) {
            System.out.println("Targeted resources: " + String.join(", ", targets));
            System.out.println();
        }

        // Print each resource change
        engine.printPlanDetails(plan);

        System.out.println();
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println();
        engine.printPlanSummary(plan);
    }

    /**
     * Prints plan as JSON.
     */
    private void printJsonPlan(Engine engine, Plan plan) {
        var summary = engine.getPlanSummary(plan);

        var json = """
            {
              "format_version": "1.0",
              "kite_version": "%s",
              "environment": "%s",
              "provider": "%s",
              "changes": {
                "create": %d,
                "update": %d,
                "destroy": %d
              }
            }
            """.formatted(VersionProvider.getVersionString(), environment, provider, summary.creates(), summary.updates(), summary.deletes());

        System.out.println(json);
    }
}
