package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.util.ProgressBar;
import cloud.kitelang.engine.distribution.ProviderInstaller;
import cloud.kitelang.engine.distribution.ProviderSpec;
import cloud.kitelang.engine.kitefile.KiteInjector;
import cloud.kitelang.engine.kitefile.Kitefile;
import cloud.kitelang.engine.plugin.grpc.PluginManifest;
import cloud.kitelang.engine.plugin.grpc.PluginProcessManager;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Manage Kite providers - install, list, and update.
 */
@Command(
        name = "providers",
        description = "Manage Kite providers",
        footer = {
                "",
                "Examples:",
                "  kite providers install                Install all from kitefile.yml",
                "  kite providers install name            Install latest AWS provider",
                "  kite providers install name@1.0.0      Install specific version",
                "  kite providers install name --local    Install to project (.kite/providers)",
                "  kite providers install myp --git github.com/org/provider",
                "  kite providers install myp --git github.com/org/repo/tree/dev/aws",
                "  kite providers list                   List global providers",
                "  kite providers list --local           List project providers"
        },
        mixinStandardHelpOptions = true,
        subcommands = {
                ProvidersCommand.InstallCommand.class,
                ProvidersCommand.ListCommand.class,
                ProvidersCommand.StartCommand.class,
                ProvidersCommand.StopCommand.class,
                ProvidersCommand.StatusCommand.class
        }
)
@Slf4j
public class ProvidersCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        Console.println("Usage: kite providers <command>");
        Console.println();
        Console.println("Commands:");
        Console.println("  install    Install providers");
        Console.println("  list       List installed providers");
        Console.println("  start      Start a provider process (keeps running for fast operations)");
        Console.println("  stop       Stop a running provider process");
        Console.println("  status     Show running provider processes");
        Console.println();
        Console.println("Examples:");
        Console.println("  kite providers install                              # Install from kitefile.yml");
        Console.println("  kite providers install name                          # Download from GitHub Releases");
        Console.println("  kite providers install name --from-source            # Clone and build from source");
        Console.println("  kite providers install name@1.0.0                    # Install specific version");
        Console.println("  kite providers install myp --git github.com/org/p   # Install from git repo");
        Console.println("  kite providers list                                 # List global providers");
        Console.println("  kite providers list --local                         # List project providers");
        Console.println("  kite providers start name                            # Start AWS provider");
        Console.println("  kite providers stop name                             # Stop AWS provider");
        Console.println("  kite providers status                               # Show running providers");
        return 0;
    }

    /**
     * Install providers from kitefile.yml or by name.
     */
    @Command(
            name = "install",
            description = "Install providers from registry or git",
            footer = {
                    "",
                    "Examples:",
                    "  kite providers install                Install all from kitefile.yml",
                    "  kite providers install name            Install latest (from GitHub Releases)",
                    "  kite providers install name@1.0.0      Install specific version",
                    "  kite providers install myp --git github.com/org/provider",
                    "  kite providers install myp --git github.com/org/repo/aws",
                    "  kite providers install name --from-source   Clone and build from source",
                    "  kite providers install name --local    Install to .kite/providers"
            },
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class InstallCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "PROVIDER[@VERSION]",
                description = "Provider name with optional version (e.g., aws@1.0.0). Installs all from kitefile.yml if not specified.",
                arity = "0..1"
        )
        private String providerName;

        @Option(
                names = {"--git"},
                paramLabel = "URL",
                description = "Install from git repository"
        )
        private String gitUrl;

        @Option(
                names = {"--ref"},
                paramLabel = "REF",
                description = "Git ref (branch, tag, or commit)"
        )
        private String gitRef;

        @Option(
                names = {"--path"},
                paramLabel = "PATH",
                description = "Subdirectory within the git repository (for monorepos)"
        )
        private String gitPath;

        @Option(
                names = {"--local"},
                description = "Install to project directory (.kite/providers) instead of global"
        )
        private boolean local;

        @Option(
                names = {"--from-source"},
                description = "Build from source instead of downloading pre-built releases"
        )
        private boolean fromSource;

        @Override
        public Integer call() {
            try {
                Path providersPath = local
                        ? Kitefile.localProvidersPath()
                        : Kitefile.globalProvidersPath();

                var installer = new ProviderInstaller(providersPath, fromSource);

                if (providerName != null) {
                    // Install specific provider
                    return installProvider(installer, providerName);
                } else {
                    // Install all from kitefile.yml
                    return installFromKitefile(installer);
                }
            } catch (Exception e) {
                Console.error(e.getMessage());
                log.debug("Installation failed", e);
                return 1;
            }
        }

        private Integer installProvider(ProviderInstaller installer, String nameWithVersion) {
            // Parse name@version format
            String name;
            String version = "latest";

            if (nameWithVersion.contains("@")) {
                var parts = nameWithVersion.split("@", 2);
                name = parts[0];
                version = normalizeVersion(parts[1]);
            } else {
                name = nameWithVersion;
            }

            Console.println("Installing provider: " + name + "@" + version);

            var specBuilder = ProviderSpec.builder().name(name);

            // Determine git URL - explicit, official provider, or none
            String effectiveGitUrl = gitUrl;
            if (effectiveGitUrl == null) {
                // Check if it's an official provider
                effectiveGitUrl = getOfficialProviderUrl(name);
            }

            // Always set version for proper directory naming
            specBuilder.version(version);

            if (effectiveGitUrl != null) {
                // Parse GitHub /tree/branch/path URLs automatically
                var parsed = ProviderSpec.parseGitHubUrl(name, effectiveGitUrl);
                specBuilder.git(effectiveGitUrl);

                // CLI options override parsed values
                if (gitRef != null) {
                    specBuilder.ref(gitRef);
                } else if (parsed.getRef() != null) {
                    specBuilder.ref(parsed.getRef());
                } else if (!"latest".equals(version)) {
                    // Use version as tag (e.g., aws@0.1.0 -> aws-v0.1.0)
                    // Version is already normalized to have 'v' prefix
                    specBuilder.ref(name + "-" + version);
                }

                if (gitPath != null) {
                    specBuilder.path(gitPath);
                } else if (parsed.getPath() != null) {
                    specBuilder.path(parsed.getPath());
                }
            }

            var spec = specBuilder.build();

            // Setup progress bar
            var progress = new ProgressBar("  " + name);
            installer.withProgress(progress);

            try {
                var path = installer.install(spec);
                progress.complete("✓ Installed " + name + " to " + path);
                return 0;
            } catch (Exception e) {
                progress.clear();
                Console.error("Failed to install " + name + ": " + e.getMessage());
                return 1;
            }
        }

        /**
         * Get the official provider URL for well-known providers.
         * Returns null for unknown providers.
         */
        private String getOfficialProviderUrl(String name) {
            return switch (name.toLowerCase()) {
                case "aws", "azure", "gcp", "files" -> "github.com/kitecorp/kite-providers/" + name;
                default -> null;
            };
        }

        private Integer installFromKitefile(ProviderInstaller installer) throws Exception {
            Path kitefilePath = Path.of("kitefile.yml");

            if (!Files.exists(kitefilePath)) {
                Console.error("No kitefile.yml found in current directory");
                Console.println("  Run 'kite new' to create a project, or specify a provider name");
                return 1;
            }

            var kitefile = KiteInjector.createkitefile();
            var config = kitefile.config();

            if (config.dependencies().isEmpty()) {
                Console.println("No dependencies configured in kitefile.yml");
                return 0;
            }

            Console.println("Installing " + config.dependencies().size() + " provider(s)...");
            Console.println();

            int installed = 0;
            int failed = 0;

            for (var dep : config.dependencies()) {
                // Normalize version (strip 'v' prefix if present)
                String version = normalizeVersion(dep.version());

                // Determine display version (version, ref, or "latest")
                String displayVersion = version != null ? version
                        : dep.ref() != null ? dep.ref() : "latest";

                // Resolve official provider URLs if no git specified
                String gitUrl = dep.git();
                if (gitUrl == null) {
                    gitUrl = getOfficialProviderUrl(dep.name());
                }

                var specBuilder = ProviderSpec.builder()
                        .name(dep.name())
                        .version(version)
                        .git(gitUrl);

                // Parse GitHub URLs automatically to extract ref and path
                if (gitUrl != null) {
                    var parsed = ProviderSpec.parseGitHubUrl(dep.name(), gitUrl);
                    // Use explicit ref from kitefile, otherwise use parsed ref
                    specBuilder.ref(dep.ref() != null ? dep.ref() : parsed.getRef());
                    specBuilder.path(dep.path() != null ? dep.path() : parsed.getPath());
                } else {
                    specBuilder.ref(dep.ref());
                    specBuilder.path(dep.path());
                }

                var spec = specBuilder.build();

                if (installer.isInstalled(spec)) {
                    Console.println("  ✓ " + dep.name() + "@" + displayVersion + " (already installed)");
                    installed++;
                    continue;
                }

                // Setup progress bar
                var progress = new ProgressBar("  " + dep.name());
                installer.withProgress(progress);

                try {
                    installer.install(spec);
                    progress.complete("  ✓ " + dep.name() + "@" + displayVersion);
                    installed++;
                } catch (Exception e) {
                    progress.clear();
                    Console.println("  ✗ " + dep.name() + "@" + displayVersion + " - " + e.getMessage());
                    failed++;
                }
            }

            Console.println();
            if (failed == 0) {
                Console.success("All providers installed successfully");
            } else {
                Console.println("Installed: " + installed + ", Failed: " + failed);
            }

            return failed > 0 ? 1 : 0;
        }

        /**
         * Normalize version to always have 'v' prefix for semantic versions.
         * Treats "main" as "latest" since kitefile.yml commonly uses "main" to mean latest release.
         * Accepts: 0.1.3 -> v0.1.3, v0.1.3 -> v0.1.3, main -> latest
         * Matches folder naming: ~/.kite/providers/aws/aws-v0.1.3
         */
        private String normalizeVersion(String version) {
            if (version == null || "latest".equals(version)) return version;
            // "main" in kitefile.yml means "latest release", not the main branch
            if ("main".equals(version)) return "latest";
            return version.startsWith("v") ? version : "v" + version;
        }
    }

    /**
     * List installed providers.
     */
    @Command(
            name = "list",
            description = "List installed providers",
            footer = {
                    "",
                    "Examples:",
                    "  kite providers list                   List global providers",
                    "  kite providers list --local           List project providers"
            },
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class ListCommand implements Callable<Integer> {

        @Option(
                names = {"--local"},
                description = "List project providers (.kite/providers)"
        )
        private boolean local;

        @Override
        public Integer call() {
            Path providersPath = local
                    ? Kitefile.localProvidersPath()
                    : Kitefile.globalProvidersPath();

            Console.println("Providers directory: " + providersPath);
            Console.println();

            if (!Files.exists(providersPath)) {
                Console.println("No providers installed");
                return 0;
            }

            try (var stream = Files.list(providersPath)) {
                var providerDirs = stream
                        .filter(Files::isDirectory)
                        .toList();

                if (providerDirs.isEmpty()) {
                    Console.println("No providers installed");
                    return 0;
                }

                var found = false;

                for (var providerDir : providerDirs) {
                    var providerName = providerDir.getFileName().toString();

                    // Check for versioned structure: aws/v0.1.3/provider.json
                    var currentLink = providerDir.resolve("current");
                    if (Files.exists(currentLink)) {
                        // Has current symlink - resolve it
                        Path currentVersion;
                        if (Files.isSymbolicLink(currentLink)) {
                            currentVersion = providerDir.resolve(Files.readSymbolicLink(currentLink));
                        } else {
                            // Text file fallback (Windows)
                            var versionName = Files.readString(currentLink).trim();
                            currentVersion = providerDir.resolve(versionName);
                        }

                        if (Files.exists(currentVersion.resolve("provider.json"))) {
                            if (!found) {
                                Console.println("Installed providers:");
                                found = true;
                            }
                            var version = formatVersion(currentVersion.getFileName().toString());
                            Console.println("  " + providerName + " " + version + " (current)");

                            // List other installed versions
                            listOtherVersions(providerDir, currentVersion.getFileName().toString(), providerName);
                            continue;
                        }
                    }

                    // List all version subdirectories
                    try (var versions = Files.list(providerDir)) {
                        var versionList = versions
                                .filter(Files::isDirectory)
                                .filter(v -> !v.getFileName().toString().equals("current"))
                                .filter(v -> Files.exists(v.resolve("provider.json")))
                                .map(v -> v.getFileName().toString())
                                .sorted(this::compareVersions)
                                .toList();

                        if (!versionList.isEmpty()) {
                            if (!found) {
                                Console.println("Installed providers:");
                                found = true;
                            }
                            // Show highest as current
                            var highest = versionList.get(versionList.size() - 1);
                            Console.println("  " + providerName + " " + formatVersion(highest));
                            for (int i = versionList.size() - 2; i >= 0; i--) {
                                Console.println("    └─ " + formatVersion(versionList.get(i)));
                            }
                        }
                    }

                    // Legacy: provider.json directly in provider dir
                    if (Files.exists(providerDir.resolve("provider.json"))) {
                        if (!found) {
                            Console.println("Installed providers:");
                            found = true;
                        }
                        Console.println("  " + providerName);
                    }
                }

                if (!found) {
                    Console.println("No providers installed");
                }

                return 0;
            } catch (Exception e) {
                Console.error("Error listing providers: " + e.getMessage());
                return 1;
            }
        }

        /**
         * Format version string - strip provider prefix and ensure it starts with 'v'.
         * Handles: aws-v0.1.3 -> v0.1.3, v0.1.3 -> v0.1.3, 0.1.3 -> v0.1.3
         */
        private String formatVersion(String version) {
            // Strip provider prefix (e.g., aws-v0.1.3 -> v0.1.3)
            int dashV = version.indexOf("-v");
            if (dashV > 0) {
                version = version.substring(dashV + 1);
            } else {
                int dash = version.indexOf("-");
                if (dash > 0 && !version.startsWith("v")) {
                    version = version.substring(dash + 1);
                }
            }

            if (version.startsWith("v")) {
                return version;
            }
            return "v" + version;
        }

        /**
         * List other installed versions (not current).
         */
        private void listOtherVersions(Path providerDir, String currentVersion, String providerName) {
            try (var versions = Files.list(providerDir)) {
                var otherVersions = versions
                        .filter(Files::isDirectory)
                        .filter(v -> !v.getFileName().toString().equals("current"))
                        .filter(v -> !v.getFileName().toString().equals(currentVersion))
                        .filter(v -> Files.exists(v.resolve("provider.json")))
                        .map(v -> v.getFileName().toString())
                        .sorted(this::compareVersions)
                        .toList();

                for (var version : otherVersions.reversed()) {
                    Console.println("    └─ " + formatVersion(version));
                }
            } catch (Exception e) {
                // Ignore errors listing other versions
            }
        }

        /**
         * Compare version strings for sorting.
         */
        private int compareVersions(String v1, String v2) {
            String s1 = v1.startsWith("v") ? v1.substring(1) : v1;
            String s2 = v2.startsWith("v") ? v2.substring(1) : v2;

            String[] parts1 = s1.split("\\.");
            String[] parts2 = s2.split("\\.");

            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
                int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
                if (p1 != p2) {
                    return Integer.compare(p1, p2);
                }
            }
            return 0;
        }

        private int parseIntSafe(String s) {
            try {
                return Integer.parseInt(s.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /**
     * Start a provider process that keeps running for fast operations.
     * The provider will auto-stop after 30 minutes of inactivity (configurable).
     */
    @Command(
            name = "start",
            description = "Start a provider process (keeps running for fast operations)",
            footer = {
                    "",
                    "Providers auto-stop after 30 min of inactivity.",
                    "Use 'kite providers status' to see running providers.",
                    "",
                    "Examples:",
                    "  kite providers start name              Start AWS provider (latest version)",
                    "  kite providers start name@0.1.5        Start specific version"
            },
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class StartCommand implements Callable<Integer> {

        private static final Path RUN_DIR = Path.of(System.getProperty("user.home"), ".kite", "run");

        @Parameters(
                index = "0",
                paramLabel = "PROVIDER[@VERSION]",
                description = "Provider name with optional version (e.g., aws@0.1.5)"
        )
        private String providerName;

        @Override
        public Integer call() {
            try {
                // Parse name@version format
                String name;
                String version = "latest";

                if (providerName.contains("@")) {
                    var parts = providerName.split("@", 2);
                    name = parts[0];
                    version = normalizeVersion(parts[1]);
                } else {
                    name = providerName;
                }

                // Find the provider executable and resolve actual version
                var resolved = resolveProvider(name, version);
                if (resolved == null) {
                    Console.error("Provider not found: " + name + "@" + version);
                    Console.println("  Run 'kite providers install " + name + "' first");
                    return 1;
                }

                String pluginId = name + "@" + resolved.version;

                // Check if already running
                if (isProviderRunning(pluginId)) {
                    Console.println("Provider already running: " + pluginId);
                    return 0;
                }

                Console.print("Starting provider " + pluginId + "... ");

                var manifest = PluginManifest.builder()
                        .name(name)
                        .version(resolved.version)
                        .executable(resolved.executable)
                        .build();

                var manager = new PluginProcessManager();
                manager.setPersistentMode(true);
                manager.setIdleTimeout(Duration.ofMinutes(30));

                var plugin = manager.startPlugin(manifest);

                if (plugin.isAlive()) {
                    Console.println("done");
                    Console.println("  Provider will auto-stop after 30 min of inactivity");
                    // Disconnect without stopping (leave running)
                    manager.disconnect();
                    return 0;
                } else {
                    Console.println("failed");
                    Console.error("Provider exited unexpectedly");
                    return 1;
                }
            } catch (Exception e) {
                Console.println("failed");
                Console.error(e.getMessage());
                log.debug("Start failed", e);
                return 1;
            }
        }

        private String normalizeVersion(String version) {
            if (version == null || "latest".equals(version)) return version;
            if ("main".equals(version)) return "latest";
            return version.startsWith("v") ? version : "v" + version;
        }

        private record ResolvedProvider(Path executable, String version) {}

        /**
         * Resolve provider executable and actual version.
         * For "latest", finds the highest installed version.
         */
        private ResolvedProvider resolveProvider(String name, String version) throws IOException {
            Path providersDir = Kitefile.globalProvidersPath().resolve(name);

            if (!Files.exists(providersDir)) {
                return null;
            }

            // For specific version, look in that version directory
            if (!"latest".equals(version)) {
                Path versionDir = providersDir.resolve(name + "-" + version);
                if (!Files.exists(versionDir)) {
                    versionDir = providersDir.resolve(version);
                }
                var exec = findExecutable(versionDir);
                return exec != null ? new ResolvedProvider(exec, version) : null;
            }

            // For latest, find the highest version
            Path currentLink = providersDir.resolve("current");
            if (Files.exists(currentLink)) {
                Path currentVersion;
                if (Files.isSymbolicLink(currentLink)) {
                    currentVersion = providersDir.resolve(Files.readSymbolicLink(currentLink));
                } else {
                    var versionName = Files.readString(currentLink).trim();
                    currentVersion = providersDir.resolve(versionName);
                }
                var exec = findExecutable(currentVersion);
                if (exec != null) {
                    var actualVersion = extractVersion(currentVersion.getFileName().toString(), name);
                    return new ResolvedProvider(exec, actualVersion);
                }
            }

            // Find highest version directory
            try (var stream = Files.list(providersDir)) {
                var highestVersion = stream
                        .filter(Files::isDirectory)
                        .filter(v -> !v.getFileName().toString().equals("current"))
                        .max((a, b) -> compareVersions(a.getFileName().toString(), b.getFileName().toString()));

                if (highestVersion.isPresent()) {
                    var exec = findExecutable(highestVersion.get());
                    if (exec != null) {
                        var actualVersion = extractVersion(highestVersion.get().getFileName().toString(), name);
                        return new ResolvedProvider(exec, actualVersion);
                    }
                }
            }

            return null;
        }

        /**
         * Extract version from directory name (e.g., "aws-v0.1.5" -> "v0.1.5")
         */
        private String extractVersion(String dirName, String providerName) {
            if (dirName.startsWith(providerName + "-")) {
                return dirName.substring(providerName.length() + 1);
            }
            return dirName;
        }

        private Path findExecutable(Path versionDir) {
            if (!Files.exists(versionDir)) return null;

            // Try bin/provider first (new structure)
            Path binProvider = versionDir.resolve("bin/provider");
            if (Files.exists(binProvider) && Files.isExecutable(binProvider)) {
                return binProvider;
            }

            // Try provider directly
            Path provider = versionDir.resolve("provider");
            if (Files.exists(provider) && Files.isExecutable(provider)) {
                return provider;
            }

            return null;
        }

        private int compareVersions(String v1, String v2) {
            String s1 = v1.replaceFirst("^[^0-9]*", "");
            String s2 = v2.replaceFirst("^[^0-9]*", "");

            String[] parts1 = s1.split("\\.");
            String[] parts2 = s2.split("\\.");

            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
                int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
                if (p1 != p2) return Integer.compare(p1, p2);
            }
            return 0;
        }

        private int parseIntSafe(String s) {
            try {
                return Integer.parseInt(s.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private boolean isProviderRunning(String pluginId) {
            var manager = new PluginProcessManager();
            return manager.isProviderRunning(pluginId);
        }
    }

    /**
     * Stop a running provider process.
     */
    @Command(
            name = "stop",
            description = "Stop a running provider process",
            footer = {
                    "",
                    "Examples:",
                    "  kite providers stop name               Stop AWS provider",
                    "  kite providers stop name@0.1.5         Stop specific version",
                    "  kite providers stop --all             Stop all running providers"
            },
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class StopCommand implements Callable<Integer> {

        private static final Path RUN_DIR = Path.of(System.getProperty("user.home"), ".kite", "run");

        @Parameters(
                index = "0",
                paramLabel = "PROVIDER[@VERSION]",
                description = "Provider name with optional version (e.g., aws@0.1.5)",
                arity = "0..1"
        )
        private String providerName;

        @Option(
                names = {"--all", "-a"},
                description = "Stop all running providers"
        )
        private boolean stopAll;

        @Override
        public Integer call() {
            try {
                if (stopAll) {
                    return stopAllProviders();
                }

                if (providerName == null) {
                    Console.error("Provider name required. Use --all to stop all providers.");
                    return 1;
                }

                // Parse name@version format
                String name;
                String version = "latest";

                if (providerName.contains("@")) {
                    var parts = providerName.split("@", 2);
                    name = parts[0];
                    version = normalizeVersion(parts[1]);
                } else {
                    name = providerName;
                }

                // Resolve "latest" to actual version
                String pluginId = resolvePluginId(name, version);
                return stopProvider(pluginId);
            } catch (Exception e) {
                Console.error(e.getMessage());
                log.debug("Stop failed", e);
                return 1;
            }
        }

        /**
         * Resolve plugin ID, converting "latest" to actual installed version.
         */
        private String resolvePluginId(String name, String version) throws IOException {
            if (!"latest".equals(version)) {
                return name + "@" + version;
            }

            // Find the actual installed version
            Path providersDir = Kitefile.globalProvidersPath().resolve(name);
            if (!Files.exists(providersDir)) {
                return name + "@" + version;
            }

            // Check current symlink first
            Path currentLink = providersDir.resolve("current");
            if (Files.exists(currentLink)) {
                String versionDir;
                if (Files.isSymbolicLink(currentLink)) {
                    versionDir = Files.readSymbolicLink(currentLink).toString();
                } else {
                    versionDir = Files.readString(currentLink).trim();
                }
                var actualVersion = extractVersion(versionDir, name);
                return name + "@" + actualVersion;
            }

            // Find highest version directory
            try (var stream = Files.list(providersDir)) {
                var highestVersion = stream
                        .filter(Files::isDirectory)
                        .filter(v -> !v.getFileName().toString().equals("current"))
                        .max((a, b) -> compareVersions(a.getFileName().toString(), b.getFileName().toString()));

                if (highestVersion.isPresent()) {
                    var actualVersion = extractVersion(highestVersion.get().getFileName().toString(), name);
                    return name + "@" + actualVersion;
                }
            }

            return name + "@" + version;
        }

        private String extractVersion(String dirName, String providerName) {
            if (dirName.startsWith(providerName + "-")) {
                return dirName.substring(providerName.length() + 1);
            }
            return dirName;
        }

        private int compareVersions(String v1, String v2) {
            String s1 = v1.replaceFirst("^[^0-9]*", "");
            String s2 = v2.replaceFirst("^[^0-9]*", "");

            String[] parts1 = s1.split("\\.");
            String[] parts2 = s2.split("\\.");

            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
                int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
                if (p1 != p2) return Integer.compare(p1, p2);
            }
            return 0;
        }

        private int parseIntSafe(String s) {
            try {
                return Integer.parseInt(s.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private Integer stopProvider(String pluginId) {
            var manager = new PluginProcessManager();

            if (!manager.isProviderRunning(pluginId)) {
                Console.println("Provider not running: " + pluginId);
                return 0;
            }

            Console.print("Stopping provider " + pluginId + "... ");

            if (manager.stopPluginByPortFile(pluginId)) {
                Console.println("done");
                return 0;
            } else {
                Console.println("failed");
                return 1;
            }
        }

        private Integer stopAllProviders() {
            if (!Files.exists(RUN_DIR)) {
                Console.println("No running providers");
                return 0;
            }

            try (var stream = Files.list(RUN_DIR)) {
                var portFiles = stream
                        .filter(p -> p.toString().endsWith(".port"))
                        .toList();

                if (portFiles.isEmpty()) {
                    Console.println("No running providers");
                    // Still clean stale files (e.g., orphaned .activity files)
                    cleanRunDirectory();
                    return 0;
                }

                int stopped = 0;
                for (var portFile : portFiles) {
                    String pluginId = portFile.getFileName().toString().replace(".port", "");
                    if (stopProvider(pluginId) == 0) {
                        stopped++;
                    }
                }

                // Clean any remaining stale files in the run directory.
                // Individual stopProvider calls clean their own .port/.activity files,
                // but stale files from crashed providers may linger and cause
                // "UNAVAILABLE: io exception" errors on subsequent runs.
                cleanRunDirectory();

                Console.println("Stopped " + stopped + " provider(s)");
                return 0;
            } catch (IOException e) {
                Console.error("Failed to list running providers: " + e.getMessage());
                return 1;
            }
        }

        /**
         * Remove all files from the run directory to eliminate stale provider state.
         * This prevents "UNAVAILABLE: io exception" errors caused by the engine
         * trying to connect to dead provider processes via leftover port/activity files.
         */
        private void cleanRunDirectory() {
            try (var files = Files.list(RUN_DIR)) {
                files.forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.debug("Failed to delete stale run file {}: {}", file, e.getMessage());
                    }
                });
                log.debug("Cleaned run directory: {}", RUN_DIR);
            } catch (IOException e) {
                log.debug("Failed to clean run directory: {}", e.getMessage());
            }
        }

        private String normalizeVersion(String version) {
            if (version == null || "latest".equals(version)) return version;
            if ("main".equals(version)) return "latest";
            return version.startsWith("v") ? version : "v" + version;
        }
    }

    /**
     * Show status of running provider processes.
     */
    @Command(
            name = "status",
            description = "Show running provider processes",
            mixinStandardHelpOptions = true
    )
    @Slf4j
    public static class StatusCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            var manager = new PluginProcessManager();
            var providers = manager.listRunningProviders();

            if (providers.isEmpty()) {
                Console.println("No running providers");
                return 0;
            }

            Console.println("Running providers:");
            Console.println();

            int running = 0;
            for (var pluginId : providers) {
                var status = manager.getProviderStatus(pluginId);

                if (status != null && status.healthy()) {
                    Console.printf("  %-20s port %-5d uptime %s  idle %s%n",
                            pluginId, status.port(), formatDuration(status.uptimeMs()), formatDuration(status.idleMs()));
                    running++;
                } else {
                    Console.printf("  %-20s (not responding)%n", pluginId);
                }
            }

            if (running > 0) {
                Console.println();
                Console.println("Providers auto-stop after idle timeout (default: 30 min)");
            }

            return 0;
        }

        private String formatDuration(long ms) {
            if (ms < 1000) return ms + "ms";
            if (ms < 60_000) return (ms / 1000) + "s";
            if (ms < 3600_000) return (ms / 60_000) + "m " + ((ms % 60_000) / 1000) + "s";
            return (ms / 3600_000) + "h " + ((ms % 3600_000) / 60_000) + "m";
        }
    }
}
