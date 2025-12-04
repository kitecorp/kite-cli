package cloud.kitelang.cli.commands;

import lombok.extern.log4j.Log4j2;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
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
    mixinStandardHelpOptions = true
)
@Log4j2
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
            log.info("Preparing to destroy infrastructure...");
            log.info("Environment: {}", environment);
            log.info("Provider: {}", provider);

            if (stack != null) {
                log.info("Stack: {}", stack);
            }

            // Safety check for production
            if ("prod".equalsIgnoreCase(environment) && !force) {
                System.err.println();
                System.err.println("WARNING: You are about to destroy PRODUCTION resources!");
                System.err.println("This action is irreversible and may cause service outages.");
                System.err.println();
                System.err.println("To proceed, you must use --force flag.");
                return 1;
            }

            // Load and display resources to be destroyed
            var resourceCount = displayDestructionPlan();

            if (resourceCount == 0) {
                System.out.println("No resources to destroy.");
                return 0;
            }

            if (dryRun) {
                System.out.println("\nDRY RUN: No changes were made.");
                return 0;
            }

            // Prompt for confirmation
            if (!autoApprove) {
                if (!confirmDestruction(resourceCount)) {
                    System.out.println("Destruction cancelled.");
                    return 0;
                }
            }

            // Execute destruction
            return executeDestruction();

        } catch (Exception e) {
            log.error("Destruction failed", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Displays the resources that will be destroyed.
     * Returns the count of resources to destroy.
     */
    private int displayDestructionPlan() {
        System.out.println();
        System.out.println("Kite will destroy the following resources:");
        System.out.println();

        // TODO: Load actual resources from state
        // For now, show placeholder output

        if (targets != null && targets.length > 0) {
            System.out.println("Targeted resources:");
            for (var target : targets) {
                System.out.println("  - destroy " + target);
            }
            System.out.println();
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
            System.out.printf("  - destroy %s.%s%n", resource[0], resource[1]);
            System.out.printf("            # %s%n", resource[2]);
        }

        System.out.println();
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.printf("%nPlan: 0 to add, 0 to change, %d to destroy.%n", resources.length);

        return resources.length;
    }

    /**
     * Prompts user to confirm destruction.
     */
    private boolean confirmDestruction(int resourceCount) {
        System.out.println();
        System.out.printf("Do you really want to destroy %d resource(s)?%n", resourceCount);
        System.out.println("  Kite will destroy all resources shown above.");
        System.out.println("  There is no undo. Only 'yes' will be accepted to confirm.");
        System.out.println();
        System.out.print("  Enter a value: ");

        var console = System.console();
        if (console == null) {
            // Running without console (e.g., in IDE)
            log.warn("No console available, cannot prompt for confirmation");
            System.err.println("Error: Cannot prompt for confirmation. Use --auto-approve to skip.");
            return false;
        }

        var input = console.readLine();
        return "yes".equalsIgnoreCase(input != null ? input.trim() : "");
    }

    /**
     * Executes the destruction of resources.
     */
    private int executeDestruction() {
        System.out.println();
        System.out.println("Destroying resources...");
        System.out.println();

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

        for (var i = 0; i < resources.length; i++) {
            System.out.printf("  Destroying %s... ", resources[i]);

            // Simulate work
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("done");
            log.info("Destroyed: {}", resources[i]);
        }

        System.out.println();
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println();
        System.out.printf("Destroy complete! Resources destroyed: %d%n", resources.length);

        return 0;
    }
}
