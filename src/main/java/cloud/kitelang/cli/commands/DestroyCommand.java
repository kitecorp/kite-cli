package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.interactive.InteractivePrompt;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
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

    @Override
    public Integer call() {
        try {
            log.debug("Preparing to destroy infrastructure...");
            log.debug("Environment: {}", environment);
            log.debug("Provider: {}", provider);

            if (stack != null) {
                log.debug("Stack: {}", stack);
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

            // Load and display resources to be destroyed
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
        }
    }

    /**
     * Displays the resources that will be destroyed.
     * Returns the count of resources to destroy.
     */
    private int displayDestructionPlan() {
        Console.println();
        Console.warning("Kite will " + Console.red("DESTROY") + " the following resources:");
        Console.println();

        // TODO: Load actual resources from state
        // For now, show placeholder output

        if (targets != null && targets.length > 0) {
            Console.println(Console.yellow("Targeted resources:"));
            for (var target : targets) {
                Console.println("  " + Console.red("-") + " destroy " + Console.red(target));
            }
            Console.println();
            return targets.length;
        }

        // Placeholder resources - would come from state backend
        var resources = new String[][]{
            {"Function", "handler", "aws_lambda_function.handler"},
            {"Bucket", "data", "aws_s3_bucket.data"},
            {"Database", "db", "aws_rds_instance.db"},
            {"SecurityGroup", "sg", "aws_security_group.sg"}
        };

        for (var resource : resources) {
            Console.println("  " + Console.red("-") + " destroy " + Console.red(resource[0] + "." + resource[1]));
            Console.println("            " + Console.yellow("# " + resource[2]));
        }

        Console.println();
        Console.println(Console.yellow("─────────────────────────────────────────────────────────────"));
        Console.println();
        Console.println("Plan: 0 to add, 0 to change, " + Console.red(resources.length + " to destroy") + ".");

        return resources.length;
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
     * Executes the destruction of resources.
     */
    private int executeDestruction() {
        Console.println();
        Console.warning("Destroying resources...");
        Console.println();

        // TODO: Integrate with cloud providers
        // 1. Load state from PostgreSQL
        // 2. Build dependency graph
        // 3. Destroy in reverse dependency order
        // 4. Update state after each destruction
        // 5. Handle errors and rollback if needed

        // Simulate destruction with progress
        var resources = new String[]{
            "Function.handler",
            "Bucket.data",
            "Database.db",
            "SecurityGroup.sg"
        };

        for (var resource : resources) {
            Console.print("  " + Console.red("✗") + " Destroying " + Console.yellow(resource) + "... ");

            // Simulate work
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Console.println(Console.red("destroyed"));
            log.debug("Destroyed: {}", resource);
        }

        Console.println();
        Console.println(Console.red("─────────────────────────────────────────────────────────────"));
        Console.println();
        Console.println(Console.red("✗") + " Destroy complete! Resources destroyed: " + Console.red(String.valueOf(resources.length)));

        return 0;
    }
}
