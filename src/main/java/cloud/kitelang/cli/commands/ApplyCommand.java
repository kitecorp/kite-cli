package cloud.kitelang.cli.commands;

import cloud.kitelang.engine.Engine;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
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
@Log4j2
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
            System.out.println("Applying: " + kiteFiles.size() + " file(s)");

            if (dryRun) {
                System.out.println("\nDRY RUN: No changes will be made");
                System.out.println("Run 'kite plan --env " + environment + "' to see detailed changes");
                return 0;
            }

            // Load all .kite file contents
            var source = new StringBuilder();
            for (var file : kiteFiles) {
                source.append(Files.readString(file));
                source.append("\n");
            }

            // Use Engine to parse, plan, and apply
            try (var engine = Engine.builder()
                    .withProvidersDir(Path.of("providers"))
                    .build()) {

                var resources = engine.parse(source.toString());
                var plan = engine.plan(resources);

                // Show plan summary
                var summary = engine.getPlanSummary(plan);
                System.out.println();
                engine.printPlanSummary(plan);

                // Request confirmation unless auto-approve
                if (!autoApprove && summary.hasChanges()) {
                    System.out.print("\nDo you want to perform these actions? (yes/no): ");
                    var console = System.console();
                    if (console != null) {
                        var answer = console.readLine();
                        if (!"yes".equalsIgnoreCase(answer)) {
                            System.out.println("Apply cancelled.");
                            return 0;
                        }
                    } else {
                        log.warn("No console available, proceeding without confirmation");
                    }
                }

                // Apply the plan
                System.out.println("\nApplying changes...");
                engine.apply(plan);

                // Print outputs
                engine.printOutputs();
            }

            System.out.println("\n✓ Apply completed successfully");
            return 0;

        } catch (Exception e) {
            log.error("Failed to apply changes", e);
            System.err.println("Error: " + e.getMessage());
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
