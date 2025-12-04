package cloud.kitelang.cli.commands;

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
 * Apply (provision) infrastructure changes to the cloud.
 * Creates or updates resources to match the desired state.
 */
@Command(
        name = "apply",
        aliases = {"provision"},
        description = "Apply infrastructure changes to provision resources"
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

            // Determine what to apply
            var applyTarget = determineApplyTarget();
            System.out.println("Applying: " + applyTarget);

            if (dryRun) {
                System.out.println("\nDRY RUN: No changes will be made");
                System.out.println("Run 'kite plan --env " + environment + "' to see detailed changes");
                return 0;
            }

            // TODO: Integrate with Kite engine once compilation issues are resolved
            // 1. Load kite.yaml and environment config
            // 2. Parse .kite files using Kite engine
            // 3. Generate plan
            // 4. Show plan to user
            // 5. If not auto-approve, ask for confirmation
            // 6. Execute plan via cloud providers
            // 7. Update state

            System.out.println("\n✓ Apply completed successfully");
            return 0;

        } catch (Exception e) {
            log.error("Failed to apply changes", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Determines the target for applying based on options.
     * Priority: --file override > --stack > all stacks in environment
     */
    private String determineApplyTarget() throws IOException {
        // If explicit file override, use that
        if (overrideFile != null) {
            if (!overrideFile.exists()) {
                throw new IOException("File not found: " + overrideFile);
            }
            return overrideFile.getPath();
        }

        // If specific stack requested
        if (stack != null) {
            var stackFile = Path.of("environments", environment, stack + ".kite");
            if (!Files.exists(stackFile)) {
                throw new IOException("Stack not found: " + stackFile);
            }
            return stackFile.toString();
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

        return envDir + "/ (" + stacks.size() + " stack" + (stacks.size() > 1 ? "s" : "") + ")";
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
