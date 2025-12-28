package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import cloud.kitelang.engine.Engine;
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
            names = {"-y", "--yes"},
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

            // Determine what files to apply
            var kiteFiles = determineKiteFiles();
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
                    : "environments/" + environment;

            // Use Engine to parse, plan, and apply
            try (var engine = Engine.builder()
                    .withProvidersDir(Path.of("providers"))
                    .build()) {

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
                    Console.print("\nDo you want to perform these actions? (yes/no): ");
                    var sysConsole = System.console();
                    if (sysConsole != null) {
                        var answer = sysConsole.readLine();
                        if (!"yes".equalsIgnoreCase(answer)) {
                            Console.println("Apply cancelled.");
                            return 0;
                        }
                    } else {
                        log.warn("No console available, proceeding without confirmation");
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
}
