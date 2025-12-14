package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.config.ConfigLoader;
import lombok.extern.log4j.Log4j2;
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
        mixinStandardHelpOptions = true
)
@Log4j2
public class DoctorCommand implements Callable<Integer> {

    @Option(names = {"--fix"}, description = "Attempt to fix issues automatically")
    private boolean fix;

    @Option(names = {"--verbose"}, description = "Show all checks, including passed ones")
    private boolean verbose;

    private final List<Check> checks = new ArrayList<>();
    private int passed = 0;
    private int failed = 0;
    private int warnings = 0;

    @Override
    public Integer call() {
        System.out.println("Kite Doctor");
        System.out.println("===========");
        System.out.println();

        // Run all checks
        checkJavaVersion();
        checkKiteConfig();
        checkProjectFiles();
        checkProviders();
        checkCredentials();
        checkStateBackend();

        // Print summary
        System.out.println();
        System.out.println("Summary");
        System.out.println("-------");
        System.out.printf("  Passed:   %d%n", passed);
        if (warnings > 0) {
            System.out.printf("  Warnings: %d%n", warnings);
        }
        if (failed > 0) {
            System.out.printf("  Failed:   %d%n", failed);
        }

        if (failed > 0) {
            System.out.println();
            System.out.println("Some checks failed. Run with --fix to attempt automatic fixes.");
            return 1;
        } else if (warnings > 0) {
            System.out.println();
            System.out.println("All checks passed, but there are warnings to review.");
            return 0;
        } else {
            System.out.println();
            System.out.println("All checks passed! Your Kite installation is healthy.");
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
                        System.out.println("      " + entry.getKey() + " = " + entry.getValue());
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

        var hasAny = false;

        // AWS
        var awsProfile = System.getenv("AWS_PROFILE");
        var awsAccessKey = System.getenv("AWS_ACCESS_KEY_ID");
        var awsConfig = Path.of(System.getProperty("user.home"), ".aws", "credentials");

        if (awsProfile != null || awsAccessKey != null || Files.exists(awsConfig)) {
            hasAny = true;
            if (verbose) {
                if (awsProfile != null) {
                    printSubcheck("AWS: profile=" + awsProfile, true, null);
                } else if (awsAccessKey != null) {
                    printSubcheck("AWS: access key configured", true, null);
                } else {
                    printSubcheck("AWS: ~/.aws/credentials exists", true, null);
                }
            }
        }

        // GCP
        var gcpProject = System.getenv("GOOGLE_CLOUD_PROJECT");
        var gcpCreds = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        var gcloudConfig = Path.of(System.getProperty("user.home"), ".config", "gcloud");

        if (gcpProject != null || gcpCreds != null || Files.isDirectory(gcloudConfig)) {
            hasAny = true;
            if (verbose) {
                if (gcpProject != null) {
                    printSubcheck("GCP: project=" + gcpProject, true, null);
                } else if (gcpCreds != null) {
                    printSubcheck("GCP: credentials file configured", true, null);
                } else {
                    printSubcheck("GCP: gcloud configured", true, null);
                }
            }
        }

        // Azure
        var azureSub = System.getenv("AZURE_SUBSCRIPTION_ID");
        var azureDir = Path.of(System.getProperty("user.home"), ".azure");

        if (azureSub != null || Files.isDirectory(azureDir)) {
            hasAny = true;
            if (verbose) {
                if (azureSub != null) {
                    printSubcheck("Azure: subscription configured", true, null);
                } else {
                    printSubcheck("Azure: ~/.azure exists", true, null);
                }
            }
        }

        if (hasAny) {
            printPassed("Cloud credentials found");
        } else {
            printWarning("No cloud credentials detected");
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
        System.out.println("[" + name + "]");
    }

    private void printPassed(String message) {
        System.out.println("  \u2713 " + message);
        passed++;
    }

    private void printWarning(String message) {
        System.out.println("  ! " + message);
        warnings++;
    }

    private void printFailed(String message) {
        System.out.println("  \u2717 " + message);
        failed++;
    }

    private void printSubcheck(String message, boolean passed, String detail) {
        var icon = passed ? "\u2713" : "\u2717";
        System.out.println("    " + icon + " " + message);
        if (detail != null) {
            System.out.println("      " + detail);
        }
    }

    /**
     * Represents a diagnostic check.
     */
    private record Check(String name, Status status, String message, String fix) {
        enum Status { PASSED, WARNING, FAILED }
    }
}
