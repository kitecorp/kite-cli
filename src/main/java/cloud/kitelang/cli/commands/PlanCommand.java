package cloud.kitelang.cli.commands;

import lombok.extern.log4j.Log4j2;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

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
    mixinStandardHelpOptions = true
)
@Log4j2
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
        names = {"-f", "--file"},
        description = "Path to main .kite file or stack file",
        defaultValue = "resources/main.kite"
    )
    private File sourceFile;

    @Option(
        names = {"-s", "--stack"},
        description = "Stack name to plan (e.g., Backend, Frontend)"
    )
    private String stack;

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
    public Integer call() {
        try {
            log.info("Creating execution plan...");
            log.info("Environment: {}", environment);
            log.info("Provider: {}", provider);

            // Determine which file(s) to plan
            var planTarget = determinePlanTarget();
            System.out.println("Planning: " + planTarget);
            System.out.println();

            if (refresh) {
                System.out.println("Refreshing state...");
                refreshState();
            }

            // TODO: Integrate with Kite engine
            // 1. Load kite.yaml configuration
            // 2. Parse .kite files to build resource graph
            // 3. Load current state from PostgreSQL backend
            // 4. Compare desired vs current state
            // 5. Generate execution plan
            // 6. Display diff to user

            // Placeholder output showing expected format
            printPlaceholderPlan();

            if (outputFile != null) {
                System.out.println("\nPlan saved to: " + outputFile);
                // TODO: Serialize plan to file
            }

            System.out.println("\nTo apply this plan, run: kite apply --env " + environment);

            return 0;

        } catch (Exception e) {
            log.error("Failed to create plan", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Determines the target for planning based on options.
     */
    private String determinePlanTarget() {
        if (stack != null) {
            return "environments/" + environment + "/" + stack + ".kite";
        }
        return sourceFile.getPath();
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
     * Prints a placeholder plan to show expected output format.
     */
    private void printPlaceholderPlan() {
        if (jsonOutput) {
            printJsonPlan();
            return;
        }

        System.out.println("Kite will perform the following actions:");
        System.out.println();

        if (targets != null && targets.length > 0) {
            System.out.println("Targeted resources: " + String.join(", ", targets));
            System.out.println();
        }

        // Example plan output
        System.out.println("  # module.backend.aws_s3_bucket.data");
        System.out.println("  + resource \"Bucket\" \"data\" {");
        System.out.println("      + name       = \"" + environment + "-api-data\"");
        System.out.println("      + versioning = true");
        System.out.println("      + encryption = true");
        System.out.println("    }");
        System.out.println();

        System.out.println("  # module.backend.aws_lambda_function.handler");
        System.out.println("  + resource \"Function\" \"handler\" {");
        System.out.println("      + runtime = \"nodejs20\"");
        System.out.println("      + memory  = 512");
        System.out.println("    }");
        System.out.println();

        if (!compact) {
            System.out.println("  # module.backend.aws_rds_instance.db");
            System.out.println("  ~ resource \"Database\" \"db\" {");
            System.out.println("      ~ instance_class = \"db.t3.small\" -> \"db.t3.medium\"");
            System.out.println("        # (1 unchanged attribute hidden)");
            System.out.println("    }");
            System.out.println();
        }

        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println();
        System.out.println("Plan: 2 to add, 1 to change, 0 to destroy.");
    }

    /**
     * Prints plan as JSON.
     */
    private void printJsonPlan() {
        // TODO: Generate proper JSON plan
        var json = """
            {
              "format_version": "1.0",
              "kite_version": "0.1.0",
              "environment": "%s",
              "provider": "%s",
              "changes": {
                "create": 2,
                "update": 1,
                "destroy": 0,
                "replace": 0
              },
              "resources": [
                {
                  "action": "create",
                  "type": "Bucket",
                  "name": "data",
                  "provider": "aws"
                },
                {
                  "action": "create",
                  "type": "Function",
                  "name": "handler",
                  "provider": "aws"
                },
                {
                  "action": "update",
                  "type": "Database",
                  "name": "db",
                  "provider": "aws"
                }
              ]
            }
            """.formatted(environment, provider);

        System.out.println(json);
    }
}
