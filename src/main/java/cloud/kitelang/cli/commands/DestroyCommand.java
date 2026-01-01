package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.config.GlobalConfig;
import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.interactive.InteractivePrompt;
import cloud.kitelang.engine.Engine;
import cloud.kitelang.engine.diff.Plan;
import cloud.kitelang.engine.domain.Resource;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Destroys (tears down) provisioned infrastructure.
 * Removes all resources tracked in state for the specified environment/stack.
 */
@Command(
    name = "destroy",
    description = {
        "Destroy provisioned infrastructure",
        "",
        "Removes all resources that were provisioned by Kite for the",
        "specified environment. Resources are destroyed in reverse",
        "dependency order to handle dependencies correctly.",
        "",
        "WARNING: This action is destructive and cannot be undone.",
        "Always run 'kite plan --destroy' first to preview changes."
    },
    footer = {
        "",
        "Examples:",
        "  kite destroy -e dev                   Destroy all resources in dev",
        "  kite destroy -e dev -s Backend        Destroy only the Backend stack",
        "  kite destroy -e dev --dry-run         Preview what would be destroyed",
        "  kite destroy -e prod --force          Required for production (safety check)",
        "  kite destroy -e dev --auto-approve    Skip confirmation prompt",
        "  kite destroy -e dev --target db       Destroy only specific resource"
    },
    mixinStandardHelpOptions = true
)
@Slf4j
public class DestroyCommand implements Callable<Integer> {

    @Option(
        names = {"-e", "--environment"},
        description = "Target environment to destroy (dev, staging, prod)",
        required = true
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
        description = "Specific stack to destroy (e.g., Backend, Frontend)"
    )
    private String stack;

    @Option(
        names = {"--target"},
        description = "Destroy only specific resource(s)",
        split = ","
    )
    private String[] targets;

    @Option(
        names = {"--auto-approve"},
        description = "Skip interactive approval prompt"
    )
    private boolean autoApprove;

    @Option(
        names = {"--force"},
        description = "Force destruction even if resources have dependencies"
    )
    private boolean force;

    @Option(
        names = {"--dry-run"},
        description = "Show what would be destroyed without making changes"
    )
    private boolean dryRun;

    @Option(
        names = {"--parallelism"},
        description = "Limit the number of concurrent destroy operations",
        defaultValue = "10"
    )
    private int parallelism;

    /** Resources loaded from state to destroy. */
    private List<Resource> stateResources;

    /** Engine instance for state management. */
    private Engine engine;

    /** Destruction plan. */
    private Plan plan;

    @Override
    public Integer call() {
        try {
            log.debug("Preparing to destroy infrastructure...");
            log.debug("Environment: {}", environment);
            log.debug("Provider: {}", provider);

            if (stack != null) {
                log.debug("Stack: {}", stack);
            }

            // Check state configuration for this environment
            var stateConfig = getStateConfiguration();
            if (stateConfig == null) {
                return 1;
            }

            // Build Engine with credentials from GlobalConfig
            var engineBuilder = Engine.builder()
                    .withProvidersDir(Path.of("providers"));

            // Pass database credentials from state configuration
            if ("postgresql".equals(stateConfig.getType())) {
                var projectName = getProjectName();
                engineBuilder.withDatabaseCredentials(
                        stateConfig.getUrl(),
                        stateConfig.getUsername(),
                        stateConfig.getEffectivePassword(projectName, environment)
                );
            }

            engine = engineBuilder.build();

            // Load resources from state
            stateResources = engine.getRepository().findAll();
            log.debug("Found {} resources in state", stateResources.size());

            // Filter by provider if specified
            if (!"all".equalsIgnoreCase(provider)) {
                stateResources = stateResources.stream()
                        .filter(r -> r.getKind().toLowerCase().startsWith(provider.toLowerCase()))
                        .toList();
            }

            // Filter by targets if specified
            if (targets != null && targets.length > 0) {
                var targetList = Arrays.asList(targets);
                stateResources = stateResources.stream()
                        .filter(r -> targetList.contains(r.getResourceNameString()) ||
                                targetList.contains(r.getKind() + "." + r.getResourceNameString()))
                        .toList();
            }

            // Safety check for production
            if ("prod".equalsIgnoreCase(environment) && !force) {
                Console.println();
                Console.box(
                    Console.red("⚠  WARNING: PRODUCTION DESTRUCTION  ⚠"),
                    "",
                    "You are about to destroy " + Console.red("PRODUCTION") + " resources!",
                    "This action is " + Console.red("IRREVERSIBLE") + " and may cause service outages.",
                    "",
                    "To proceed, you must use the " + Console.yellow("--force") + " flag."
                );
                Console.println();
                return 1;
            }

            // Display resources to be destroyed
            var resourceCount = displayDestructionPlan();

            if (resourceCount == 0) {
                Console.println("No resources to destroy.");
                return 0;
            }

            if (dryRun) {
                Console.println();
                Console.info(Console.yellow("DRY RUN") + ": No changes were made.");
                return 0;
            }

            // Prompt for confirmation
            if (!autoApprove) {
                if (!confirmDestruction(resourceCount)) {
                    Console.println();
                    Console.success("Destruction cancelled. No resources were harmed.");
                    return 0;
                }
            }

            // Execute destruction
            return executeDestruction();

        } catch (Exception e) {
            log.error("Destruction failed", e);
            Console.error(e.getMessage());
            return 1;
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    /**
     * Displays the resources that will be destroyed using the engine's plan.
     * Returns the count of resources to destroy.
     */
    private int displayDestructionPlan() {
        if (stateResources.isEmpty()) {
            return 0;
        }

        // Create destroy plan
        plan = engine.planDestroy(stateResources);

        // Show plan details
        Console.println();
        engine.printPlanDetails(plan);

        // Show colored summary
        Console.println();
        Console.println(Console.red("─────────────────────────────────────────────────────────────"));
        Console.println();
        var summary = engine.getPlanSummary(plan);
        Console.println("Plan: " + summary.creates() + " to add, " +
                summary.updates() + " to change, " +
                Console.red(summary.deletes() + " to destroy") + ".");

        return summary.deletes();
    }

    /**
     * Prompts user to confirm destruction.
     */
    private boolean confirmDestruction(int resourceCount) {
        Console.println();
        Console.warning("Do you really want to destroy " + Console.red(resourceCount + " resource(s)") + "?");
        Console.println("  Kite will " + Console.red("permanently delete") + " all resources shown above.");
        Console.println("  " + Console.yellow("⚠ There is no undo."));
        Console.println();

        try (var prompt = InteractivePrompt.create()) {
            if (prompt != null) {
                return prompt.confirm("Confirm destruction?", false);
            } else {
                log.warn("No interactive terminal available, cannot prompt for confirmation");
                Console.error("Cannot prompt for confirmation. Use --auto-approve to skip.");
                return false;
            }
        } catch (IOException e) {
            log.error("Failed to read confirmation", e);
            return false;
        }
    }

    /**
     * Executes the destruction of resources using the engine's apply.
     */
    private int executeDestruction() {
        Console.println();
        Console.warning("Destroying resources...");
        Console.println();

        // Apply the destroy plan (calls provider delete methods and updates state)
        engine.apply(plan);

        Console.println();
        Console.println(Console.red("─────────────────────────────────────────────────────────────"));
        Console.println();
        Console.println(Console.red("✗") + " Destroy complete! Resources: " + Console.red(String.valueOf(stateResources.size())) + " destroyed.");

        return 0;
    }

    /**
     * Gets the project name from the current directory.
     */
    private String getProjectName() {
        return Path.of("").toAbsolutePath().getFileName().toString();
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
