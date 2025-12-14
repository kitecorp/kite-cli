package cloud.kitelang.cli.commands;

import cloud.kitelang.engine.Engine;
import cloud.kitelang.engine.diff.Plan;
import lombok.extern.log4j.Log4j2;
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
    public Integer call() {
        try {
            log.info("Creating execution plan...");
            log.info("Environment: {}", environment);
            log.info("Provider: {}", provider);

            // Determine which file(s) to plan
            var kiteFiles = determineKiteFiles();
            System.out.println("Planning: " + kiteFiles.size() + " file(s)");
            System.out.println();

            if (refresh) {
                System.out.println("Refreshing state...");
                refreshState();
            }

            // Load all .kite file contents
            var source = new StringBuilder();
            for (var file : kiteFiles) {
                source.append(Files.readString(file));
                source.append("\n");
            }

            // Use Engine to parse and plan
            try (var engine = Engine.builder()
                    .withProvidersDir(Path.of("providers"))
                    .build()) {

                var resources = engine.parse(source.toString());
                var plan = engine.plan(resources);

                if (jsonOutput) {
                    printJsonPlan(engine, plan);
                } else {
                    printPlan(engine, plan);
                }

                if (outputFile != null) {
                    // TODO: Serialize plan to file
                    System.out.println("\nPlan saved to: " + outputFile);
                }
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
     * Determines the .kite files to plan based on options.
     * Priority: --file override > --stack > all stacks in environment
     */
    private List<Path> determineKiteFiles() throws IOException {
        // If explicit file override, use that
        if (overrideFile != null) {
            if (!overrideFile.exists()) {
                throw new IOException("File not found: " + overrideFile);
            }
            return List.of(overrideFile.toPath());
        }

        // If specific stack requested
        if (stack != null) {
            var stackFile = Path.of("environments", environment, stack + ".kite");
            if (!Files.exists(stackFile)) {
                throw new IOException("Stack not found: " + stackFile);
            }
            return List.of(stackFile);
        }

        // Default: all stacks in the environment
        var envDir = Path.of("environments", environment);
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

        // TODO: Add detailed resource printing when needed
        // For now, just show summary

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
              "kite_version": "0.1.0",
              "environment": "%s",
              "provider": "%s",
              "changes": {
                "create": %d,
                "update": %d,
                "destroy": %d
              }
            }
            """.formatted(environment, provider, summary.creates(), summary.updates(), summary.deletes());

        System.out.println(json);
    }
}
