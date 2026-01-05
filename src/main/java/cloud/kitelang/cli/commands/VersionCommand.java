package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Manage Kite CLI versions - list, install, switch, and remove.
 */
@Command(
        name = "version",
        description = "Manage Kite CLI versions",
        footer = {
                "",
                "Examples:",
                "  kite version                          Show current and installed versions",
                "  kite version list                     List installed and available versions",
                "  kite version install 1.2.0            Install specific version",
                "  kite version use 1.2.0                Switch to installed version",
                "  kite version remove 1.2.0             Remove an installed version"
        },
        mixinStandardHelpOptions = true,
        subcommands = {
                VersionCommand.ListCommand.class,
                VersionCommand.InstallCommand.class,
                VersionCommand.UseCommand.class,
                VersionCommand.RemoveCommand.class
        }
)
@Slf4j
public class VersionCommand implements Callable<Integer> {

    private static final String GITHUB_REPO = "kitecorp/kite-cli";
    private static final String INSTALL_SCRIPT_BASE = "https://cli.kitelang.cloud/scripts";
    private static final String INSTALL_SCRIPT_URL = INSTALL_SCRIPT_BASE + "/install.sh";
    private static final String INSTALL_SCRIPT_PS1_URL = INSTALL_SCRIPT_BASE + "/install.ps1";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final int PROCESS_TIMEOUT_SECONDS = 300; // 5 minutes for download

    @Override
    public Integer call() {
        // Default behavior: show current and installed versions
        try {
            var currentVersion = getCurrentVersion();
            var installedVersions = getInstalledVersions();

            Console.println("Current: " + (currentVersion != null ? currentVersion : "not set"));
            Console.println();

            if (installedVersions.isEmpty()) {
                Console.println("No versions installed in ~/.kite/versions/");
            } else {
                Console.println("Installed versions:");
                for (var version : installedVersions) {
                    var marker = version.equals(currentVersion) ? " (current)" : "";
                    Console.println("  " + version + marker);
                }
            }

            return 0;
        } catch (Exception e) {
            Console.error("Error: " + e.getMessage());
            log.debug("Failed to list versions", e);
            return 1;
        }
    }

    /**
     * List installed and available versions.
     */
    @Command(
            name = "list",
            description = "List installed and available versions",
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class ListCommand implements Callable<Integer> {

        @Option(names = {"--available", "-a"}, description = "Show available versions from GitHub")
        private boolean showAvailable;

        @Override
        public Integer call() {
            try {
                var currentVersion = getCurrentVersion();
                var installedVersions = getInstalledVersions();

                Console.println("Installed:");
                if (installedVersions.isEmpty()) {
                    Console.println("  (none)");
                } else {
                    for (var version : installedVersions) {
                        var marker = version.equals(currentVersion) ? " (current)" : "";
                        Console.println("  " + version + marker);
                    }
                }

                if (showAvailable) {
                    Console.println();
                    Console.println("Available:");
                    var availableVersions = fetchAvailableVersions();
                    if (availableVersions.isEmpty()) {
                        Console.println("  (could not fetch releases)");
                    } else {
                        for (var version : availableVersions) {
                            var marker = installedVersions.contains(version) ? " (installed)" : "";
                            Console.println("  " + version + marker);
                        }
                    }
                }

                return 0;
            } catch (Exception e) {
                Console.error("Error: " + e.getMessage());
                log.debug("Failed to list versions", e);
                return 1;
            }
        }
    }

    /**
     * Install a specific version.
     */
    @Command(
            name = "install",
            description = "Install a specific version",
            footer = {
                    "",
                    "Examples:",
                    "  kite version install 1.2.0            Install version 1.2.0",
                    "  kite version install latest           Install latest version",
                    "  kite version install 1.2.0 --use      Install and switch to it"
            },
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class InstallCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "VERSION",
                description = "Version to install (e.g., 1.2.0 or 'latest')"
        )
        private String version;

        @Option(names = {"--use", "-u"}, description = "Switch to this version after installing")
        private boolean switchAfterInstall;

        @Override
        public Integer call() {
            try {
                // Resolve 'latest' to actual version
                var targetVersion = version;
                if ("latest".equalsIgnoreCase(version)) {
                    targetVersion = fetchLatestVersion();
                    if (targetVersion == null) {
                        Console.error("Could not determine latest version");
                        return 1;
                    }
                    Console.println("Latest version: " + targetVersion);
                }

                // Check if already installed
                var installedVersions = getInstalledVersions();
                if (installedVersions.contains(targetVersion)) {
                    Console.println("Version " + targetVersion + " is already installed");
                    if (switchAfterInstall) {
                        return switchToVersion(targetVersion);
                    }
                    return 0;
                }

                Console.println("Installing Kite " + targetVersion + "...");
                Console.println();

                var exitCode = runInstallScript(targetVersion);

                if (exitCode == 0) {
                    Console.println();
                    Console.success("Version " + targetVersion + " installed successfully");

                    if (switchAfterInstall) {
                        return switchToVersion(targetVersion);
                    }
                }

                return exitCode;
            } catch (Exception e) {
                Console.error("Installation failed: " + e.getMessage());
                log.debug("Install failed", e);
                return 1;
            }
        }
    }

    /**
     * Switch to an installed version.
     */
    @Command(
            name = "use",
            description = "Switch to an installed version",
            footer = {
                    "",
                    "Examples:",
                    "  kite version use 1.2.0                Switch to version 1.2.0"
            },
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class UseCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "VERSION",
                description = "Version to switch to"
        )
        private String version;

        @Override
        public Integer call() {
            return switchToVersion(version);
        }
    }

    /**
     * Remove an installed version.
     */
    @Command(
            name = "remove",
            description = "Remove an installed version",
            footer = {
                    "",
                    "Examples:",
                    "  kite version remove 1.2.0             Remove version 1.2.0"
            },
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class RemoveCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "VERSION",
                description = "Version to remove"
        )
        private String version;

        @Option(names = {"--force", "-f"}, description = "Force removal even if it's the current version")
        private boolean force;

        @Override
        public Integer call() {
            try {
                var versionDir = getVersionsDir().resolve(version);

                if (!Files.exists(versionDir)) {
                    Console.error("Version " + version + " is not installed");
                    return 1;
                }

                var currentVersion = getCurrentVersion();
                if (version.equals(currentVersion) && !force) {
                    Console.error("Cannot remove current version. Use --force to override or switch to another version first.");
                    return 1;
                }

                Console.println("Removing version " + version + "...");

                // Delete version directory recursively
                try (var walk = Files.walk(versionDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    log.warn("Failed to delete: " + path, e);
                                }
                            });
                }

                Console.success("Version " + version + " removed");

                // If we removed the current version, warn user
                if (version.equals(currentVersion)) {
                    Console.warning("Removed current version. Run 'kite version use <version>' to set a new current version.");
                }

                return 0;
            } catch (Exception e) {
                Console.error("Failed to remove version: " + e.getMessage());
                log.debug("Remove failed", e);
                return 1;
            }
        }
    }

    // ========== Utility Methods ==========

    private static Path getKiteHome() {
        return Path.of(System.getProperty("user.home"), ".kite");
    }

    private static Path getVersionsDir() {
        return getKiteHome().resolve("versions");
    }

    private static Path getCurrentLink() {
        return getKiteHome().resolve("current");
    }

    /**
     * Get the currently active version by reading the symlink target.
     */
    static String getCurrentVersion() {
        try {
            var currentLink = getCurrentLink();
            if (!Files.exists(currentLink)) {
                return null;
            }

            var target = Files.readSymbolicLink(currentLink);
            return target.getFileName().toString();
        } catch (Exception e) {
            log.debug("Failed to read current version", e);
            return null;
        }
    }

    /**
     * Get list of installed versions.
     */
    static List<String> getInstalledVersions() {
        var versions = new ArrayList<String>();
        var versionsDir = getVersionsDir();

        if (!Files.exists(versionsDir)) {
            return versions;
        }

        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder()) // Newest first
                    .forEach(versions::add);
        } catch (Exception e) {
            log.debug("Failed to list versions", e);
        }

        return versions;
    }

    /**
     * Fetch available versions from GitHub releases.
     */
    static List<String> fetchAvailableVersions() {
        var versions = new ArrayList<String>();

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();

            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + GITHUB_REPO + "/releases"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                var mapper = new ObjectMapper();
                var releases = mapper.readTree(response.body());

                for (var release : releases) {
                    var tagName = release.get("tag_name").asText();
                    // Remove 'v' prefix if present
                    var version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    versions.add(version);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to fetch releases", e);
        }

        return versions;
    }

    /**
     * Fetch the latest version from GitHub.
     */
    static String fetchLatestVersion() {
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();

            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                var mapper = new ObjectMapper();
                var release = mapper.readTree(response.body());
                var tagName = release.get("tag_name").asText();
                return tagName.startsWith("v") ? tagName.substring(1) : tagName;
            }
        } catch (Exception e) {
            log.debug("Failed to fetch latest version", e);
        }

        return null;
    }

    /**
     * Switch to a specific version by updating the symlink.
     */
    static int switchToVersion(String version) {
        try {
            var versionDir = getVersionsDir().resolve(version);

            if (!Files.exists(versionDir)) {
                Console.error("Version " + version + " is not installed");
                Console.println("  Run 'kite version install " + version + "' first");
                return 1;
            }

            var currentLink = getCurrentLink();

            // Remove existing symlink
            Files.deleteIfExists(currentLink);

            // Create new symlink
            Files.createSymbolicLink(currentLink, versionDir);

            Console.success("Switched to version " + version);
            return 0;
        } catch (Exception e) {
            Console.error("Failed to switch version: " + e.getMessage());
            log.debug("Switch failed", e);
            return 1;
        }
    }

    /**
     * Run the install script to download and install a version.
     */
    static int runInstallScript(String version) throws Exception {
        var os = getOS();

        if ("windows".equals(os)) {
            return runWindowsInstallScript(version);
        } else {
            return runUnixInstallScript(version);
        }
    }

    private static int runUnixInstallScript(String version) throws Exception {
        // Download and pipe to bash with KITE_VERSION set
        var command = new String[]{
                "bash", "-c",
                "curl -fsSL " + INSTALL_SCRIPT_URL + " | KITE_VERSION=" + version + " bash"
        };

        var processBuilder = new ProcessBuilder(command)
                .inheritIO(); // Show output directly to console

        var process = processBuilder.start();
        var completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("Installation timed out");
        }

        return process.exitValue();
    }

    private static int runWindowsInstallScript(String version) throws Exception {
        // Download script and execute with PowerShell
        var command = new String[]{
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "$env:KITE_VERSION='" + version + "'; " +
                        "Invoke-RestMethod -Uri '" + INSTALL_SCRIPT_PS1_URL + "' | Invoke-Expression"
        };

        var processBuilder = new ProcessBuilder(command)
                .inheritIO();

        var process = processBuilder.start();
        var completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("Installation timed out");
        }

        return process.exitValue();
    }

    private static String getOS() {
        var os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        return os;
    }
}
