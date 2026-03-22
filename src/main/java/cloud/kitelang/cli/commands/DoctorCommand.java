package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.config.ConfigLoader;
import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.validation.CredentialValidatorRegistry;
import cloud.kitelang.engine.kitefile.Dependencies.Credential;
import cloud.kitelang.engine.kitefile.KiteInjector;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Diagnose and troubleshoot Kite installation and project configuration.
 * Checks for common issues and provides recommendations.
 */
@Command(
        name = "doctor",
        description = "Check your Kite installation and project setup",
        footer = {
                "",
                "Examples:",
                "  kite doctor                           Run all diagnostic checks",
                "  kite doctor -e prod                   Validate credentials for prod environment",
                "  kite doctor --verbose                 Show detailed output for all checks",
                "  kite doctor --fix                     Attempt to fix issues automatically"
        },
        mixinStandardHelpOptions = true
)
@Slf4j
public class DoctorCommand implements Callable<Integer> {

    @Option(names = {"--fix"}, description = "Attempt to fix issues automatically")
    private boolean fix;

    @Option(names = {"--verbose"}, description = "Show all checks, including passed ones")
    private boolean verbose;

    @Option(names = {"-e", "--environment"}, paramLabel = "env", description = "Environment to validate credentials for")
    private String environment;

    private final List<Check> checks = new ArrayList<>();
    private int passed = 0;
    private int failed = 0;
    private int warnings = 0;

    @Override
    public Integer call() {
        Console.println("Kite Doctor");
        Console.println("===========");
        Console.println();

        // Run all checks
        checkJavaVersion();
        checkKiteConfig();
        checkProjectFiles();
        checkProviders();
        checkCredentials();
        checkStateBackend();

        // Print summary
        Console.println();
        Console.println("Summary");
        Console.println("-------");
        Console.printf("  Passed:   %d%n", passed);
        if (warnings > 0) {
            Console.printf("  Warnings: %d%n", warnings);
        }
        if (failed > 0) {
            Console.printf("  Failed:   %d%n", failed);
        }

        if (failed > 0) {
            Console.println();
            Console.println("Some checks failed. Run with --fix to attempt automatic fixes.");
            return 1;
        } else if (warnings > 0) {
            Console.println();
            Console.println("All checks passed, but there are warnings to review.");
            return 0;
        } else {
            Console.println();
            Console.println("All checks passed! Your Kite installation is healthy.");
            return 0;
        }
    }

    private void checkJavaVersion() {
        printCheck("Java version");

        var version = Runtime.version();
        var major = version.feature();

        if (major >= 21) {
            printPassed("Java " + version);
        } else if (major >= 17) {
            printWarning("Java " + version + " (21+ recommended for best performance)");
        } else {
            printFailed("Java " + version + " (21+ required)");
        }
    }

    private void checkKiteConfig() {
        printCheck("Global configuration");

        var configPath = ConfigLoader.configFile();
        if (Files.exists(configPath)) {
            try {
                var config = ConfigLoader.load();
                printPassed(configPath.toString());
                if (verbose) {
                    var list = ConfigLoader.list();
                    for (var entry : list.entrySet()) {
                        Console.println("      " + entry.getKey() + " = " + entry.getValue());
                    }
                }
            } catch (Exception e) {
                printFailed("Invalid config: " + e.getMessage());
            }
        } else {
            printWarning("No config file (run 'kite config set' to create)");
        }
    }

    private void checkProjectFiles() {
        printCheck("Project files");

        var cwd = Path.of(System.getProperty("user.dir"));

        // Check for kitefile.yml
        var kitefile = findKitefile(cwd);
        if (kitefile != null) {
            printPassed("Found " + cwd.relativize(kitefile));

            // Check for environments directory
            var envDir = cwd.resolve("environments");
            if (Files.isDirectory(envDir)) {
                if (verbose) {
                    printSubcheck("environments/", true, null);
                }
            } else {
                printWarning("No environments/ directory");
            }

            // Check for .kite files
            try (var stream = Files.walk(cwd)) {
                var kiteFiles = stream
                        .filter(p -> p.toString().endsWith(".kite"))
                        .count();
                if (kiteFiles > 0) {
                    if (verbose) {
                        printSubcheck(kiteFiles + " .kite files found", true, null);
                    }
                } else {
                    printWarning("No .kite files found");
                }
            } catch (IOException e) {
                log.debug("Error scanning for .kite files", e);
            }
        } else {
            printWarning("Not in a Kite project (no kitefile.yml)");
        }
    }

    private Path findKitefile(Path dir) {
        var names = List.of("kitefile.yml", "kitefile.yaml", "kite.yml", "kite.yaml");
        for (var name : names) {
            var path = dir.resolve(name);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    private void checkProviders() {
        printCheck("Providers");

        var globalDir = ConfigLoader.configDir().resolve("providers");
        var localDir = Path.of(System.getProperty("user.dir"), ".kite", "providers");

        var found = false;

        if (Files.isDirectory(globalDir)) {
            try (var stream = Files.list(globalDir)) {
                var providers = stream.filter(Files::isDirectory).toList();
                if (!providers.isEmpty()) {
                    found = true;
                    if (verbose) {
                        for (var p : providers) {
                            printSubcheck(p.getFileName() + " (global)", true, null);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Error listing global providers", e);
            }
        }

        if (Files.isDirectory(localDir)) {
            try (var stream = Files.list(localDir)) {
                var providers = stream.filter(Files::isDirectory).toList();
                if (!providers.isEmpty()) {
                    found = true;
                    if (verbose) {
                        for (var p : providers) {
                            printSubcheck(p.getFileName() + " (local)", true, null);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Error listing local providers", e);
            }
        }

        if (found) {
            printPassed("Providers installed");
        } else {
            printWarning("No providers installed (run 'kite providers install')");
        }
    }

    private void checkCredentials() {
        printCheck("Cloud credentials");

        // Try to load kitefile.yml and validate credentials for the environment
        var cwd = Path.of(System.getProperty("user.dir"));
        var kitefilePath = findKitefile(cwd);

        if (kitefilePath == null) {
            // No kitefile - just check if CLIs are available
            checkCliAvailability();
            return;
        }

        try {
            var kitefile = KiteInjector.createkitefile();
            var config = kitefile.config();

            // Determine which environment to check
            var envToCheck = environment != null ? environment : "dev";
            var envConfig = config.getEnvironment(envToCheck);

            if (envConfig == null || envConfig.credentials().isEmpty()) {
                // No credentials configured for this environment - check CLI availability
                if (verbose) {
                    Console.println("  No credentials configured for environment: " + envToCheck);
                }
                checkCliAvailability();
                return;
            }

            Console.println("  Validating credentials for environment: " + envToCheck);

            var allValid = true;
            for (var credential : envConfig.credentials()) {
                var result = CredentialValidatorRegistry.validate(credential);
                var label = credential.identifier();

                if (result.success()) {
                    printSubcheck(label + ": " + result.identity(), true,
                            verbose ? result.details() : null);
                } else {
                    printSubcheck(label + ": " + result.message(), false, null);
                    allValid = false;
                }
            }

            if (allValid) {
                printPassed("All credentials valid");
            } else {
                failed++; // Count as failed (not using printFailed to avoid double count)
            }

        } catch (Exception e) {
            log.debug("Failed to validate credentials", e);
            printWarning("Could not validate credentials: " + e.getMessage());
            checkCliAvailability();
        }
    }

    /**
     * Checks if cloud provider CLIs are available (fallback when no kitefile).
     */
    private void checkCliAvailability() {
        var awsCli = isCommandAvailable("aws", "--version");
        var gcloudCli = isCommandAvailable("gcloud", "--version");
        var azCli = isCommandAvailable("az", "--version");

        if (verbose) {
            printSubcheck("AWS CLI: " + (awsCli ? "available" : "not found"), awsCli, null);
            printSubcheck("gcloud CLI: " + (gcloudCli ? "available" : "not found"), gcloudCli, null);
            printSubcheck("Azure CLI: " + (azCli ? "available" : "not found"), azCli, null);
        }

        if (awsCli || gcloudCli || azCli) {
            printPassed("Cloud CLIs available");
        } else {
            printWarning("No cloud CLIs found (aws, gcloud, az)");
        }
    }

    private boolean isCommandAvailable(String... command) {
        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void checkStateBackend() {
        printCheck("State backend");

        // Check for database configuration
        var dbHost = System.getenv("KITE_DB_HOST");
        var dbUrl = System.getenv("KITE_DB_URL");

        if (dbHost != null || dbUrl != null) {
            // TODO: Actually test the connection
            printPassed("Database configured" + (dbHost != null ? " (" + dbHost + ")" : ""));
        } else {
            // Check for local state file
            var stateFile = Path.of(System.getProperty("user.dir"), ".kite", "state.json");
            if (Files.exists(stateFile)) {
                printPassed("Local state file");
            } else {
                printWarning("No state backend configured (using in-memory)");
            }
        }
    }

    // Output helpers

    private void printCheck(String name) {
        Console.println("[" + name + "]");
    }

    private void printPassed(String message) {
        Console.println("  \u2713 " + message);
        passed++;
    }

    private void printWarning(String message) {
        Console.println("  ! " + message);
        warnings++;
    }

    private void printFailed(String message) {
        Console.println("  \u2717 " + message);
        failed++;
    }

    private void printSubcheck(String message, boolean passed, String detail) {
        var icon = passed ? "\u2713" : "\u2717";
        Console.println("    " + icon + " " + message);
        if (detail != null) {
            Console.println("      " + detail);
        }
    }

    /**
     * Represents a diagnostic check.
     */
    private record Check(String name, Status status, String message, String fix) {
        enum Status { PASSED, WARNING, FAILED }
    }
}
