package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.util.MultiProgress;
import cloud.kitelang.engine.distribution.ProviderInstaller;
import cloud.kitelang.packages.install.OfficialProviders;
import cloud.kitelang.packages.install.PackageInstallException;
import cloud.kitelang.packages.install.PackageSpec;
import cloud.kitelang.packages.process.RunDir;
import cloud.kitelang.engine.kitefile.Dependency;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

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
                "  kite providers install aws             Install the latest release of an official provider",
                "  kite providers install aws@0.3.0       Install one release of it",
                "  kite providers install aws --local     Install to project (.kite/providers)",
                "  kite providers install myp --git github.com/org/provider",
                "  kite providers install myp --git github.com/org/repo/tree/dev/aws",
                "  kite providers list                   List global providers",
                "  kite providers list --local           List project providers"
        },
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
        Console.println("  kite providers install aws                           # Official provider, from GitHub Releases");
        Console.println("  kite providers install aws --from-source             # Clone and build from source");
        Console.println("  kite providers install aws@0.3.0                     # Install one release");
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
            description = "Install providers from GitHub releases or git",
            footer = {
                    "",
                    "Examples:",
                    "  kite providers install                Install all from kitefile.yml",
                    "  kite providers install aws             Install the latest release of an official provider",
                    "  kite providers install aws@0.3.0       Install one release of it",
                    "  kite providers install myp --git github.com/org/provider",
                    "  kite providers install myp --git github.com/org/repo/aws",
                    "  kite providers install aws --from-source   Clone and build from source",
                    "  kite providers install aws --local     Install to .kite/providers",
                    "",
                    "Without --git, only the official providers (aws, azure, files, gcp) install by name."
            }
    )
    @Slf4j
    public static class InstallCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "provider[@version]",
                description = "Provider name with optional version (e.g., aws@1.0.0). Installs all from kitefile.yml if not specified.",
                arity = "0..1"
        )
        private String providerName;

        @Option(
                names = {"--git"},
                paramLabel = "url",
                description = "Install from git repository"
        )
        private String gitUrl;

        @Option(
                names = {"--ref"},
                paramLabel = "ref",
                description = "Git ref (branch, tag, or commit)"
        )
        private String gitRef;

        @Option(
                names = {"--path"},
                paramLabel = "path",
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

        /**
         * The package {@code kite providers install <name>[@<version>]} installs: from the
         * {@code --git} repository, or from the official provider's releases.
         * The {@code --ref} and {@code --path} options win over a ref and path in the URL; without
         * either, a pinned version installs the release tagged {@code <name>-v<version>}.
         *
         * @throws PackageInstallException for a provider that is not official and has no {@code --git}
         */
        static PackageSpec specForName(String nameWithVersion, String gitUrl, String gitRef, String gitPath)
                throws PackageInstallException {
            var parts = nameWithVersion.split("@", 2);
            var name = parts[0];
            // Always a version, for the version directory's name
            var version = parts.length > 1 ? parts[1] : "latest";
            // The same spec a kitefile entry with these settings gives
            var spec = new Dependency(name, version, gitUrl, gitRef, gitPath, null).toPackageSpec();
            if (!spec.isGitPackage()) {
                throw new PackageInstallException("Provider " + name + " has no git setting. Pass --git <repository URL>,"
                        + " or add git: <repository URL> to its entry in kitefile.yml; "
                        + OfficialProviders.nameAndVersionRule() + ".");
            }
            if (spec.getRef() == null && !"latest".equals(spec.getVersion())) {
                // aws@0.1.0 (made v0.1.0 above) installs the release tagged aws-v0.1.0
                return spec.toBuilder().ref(name + "-" + spec.getVersion()).build();
            }
            return spec;
        }

        private Integer installProvider(ProviderInstaller installer, String nameWithVersion) throws PackageInstallException {
            var spec = specForName(nameWithVersion, gitUrl, gitRef, gitPath);
            var name = spec.getName();
            Console.println("Installing provider: " + name + "@" + spec.getVersion());

            var progress = new MultiProgress(List.of(name), Console::print, Console.isInteractive(),
                    Console.width(), Console.height());
            try {
                var path = installer.install(spec, progress.listener(name));
                progress.done(name);
                Console.println("Installed " + name + " to " + path);
                return 0;
            } catch (Exception e) {
                progress.failed(name, e.getMessage());
                return 1;
            }
        }

        /** What {@code kite providers install} does with each kitefile provider. */
        record KitefileInstallPlan(List<String> alreadyInstalled, List<PackageSpec> toInstall, List<String> missingGit) {
        }

        /**
         * Sorts the kitefile's providers into those already installed (as {@code name@version}),
         * those to install, and refusals for those that have nothing to install from.
         */
        static KitefileInstallPlan planKitefileInstall(List<Dependency> dependencies, Predicate<PackageSpec> isInstalled) {
            var alreadyInstalled = new ArrayList<String>();
            var toInstall = new ArrayList<PackageSpec>();
            var missingGit = new ArrayList<String>();
            for (var dep : dependencies) {
                // The engine loads Terraform providers through the bridge; they are not installed here
                if (dep.isTerraformProvider()) {
                    continue;
                }
                var spec = dep.toPackageSpec();
                if (isInstalled.test(spec)) {
                    var displayVersion = spec.getVersion() != null ? spec.getVersion()
                            : spec.getRef() != null ? spec.getRef() : "latest";
                    alreadyInstalled.add(dep.name() + "@" + displayVersion);
                } else if (spec.isGitPackage()) {
                    toInstall.add(spec);
                } else {
                    missingGit.add(dep.missingGitMessage());
                }
            }
            return new KitefileInstallPlan(alreadyInstalled, toInstall, missingGit);
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

            var plan = planKitefileInstall(config.dependencies(), installer::isInstalled);
            for (var name : plan.alreadyInstalled()) {
                Console.println("  ✓ " + name + " (already installed)");
            }
            for (var refusal : plan.missingGit()) {
                Console.error(refusal);
            }
            var toInstall = plan.toInstall();
            int alreadyInstalled = plan.alreadyInstalled().size();

            int installFailures = 0;
            if (!toInstall.isEmpty()) {
                Console.println("Installing " + toInstall.size() + " provider(s)...");
                Console.println();
                // All downloads run at once, each redrawing its own line
                var names = toInstall.stream().map(PackageSpec::getName).toList();
                var progress = new MultiProgress(names, Console::print, Console.isInteractive(),
                        Console.width(), Console.height());
                try {
                    installer.installAll(toInstall, spec -> progress.installListener(spec.getName()));
                } catch (PackageInstallException e) {
                    // Each failure already marked its own line; the whole reasons follow the bars
                    Console.println();
                    for (var failure : progress.failures()) {
                        Console.error(failure);
                    }
                }
                installFailures = progress.failures().size();
            }
            int installed = alreadyInstalled + toInstall.size() - installFailures;
            int failed = installFailures + plan.missingGit().size();

            Console.println();
            if (failed == 0) {
                Console.success("All providers installed successfully");
            } else {
                Console.println("Installed: " + installed + ", Failed: " + failed);
            }

            return failed > 0 ? 1 : 0;
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
            }
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
            }
    )
    @Slf4j
    public static class StartCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "provider[@version]",
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
                    version = PackageSpec.normalizeVersion(parts[1]);
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

        private record ResolvedProvider(Path executable, String version) {}

        /**
         * Resolve provider executable and actual version.
         * For "latest", finds the highest installed version.
         */
        private ResolvedProvider resolveProvider(String name, String version) throws IOException {
            new ProviderInstaller(Kitefile.globalProvidersPath()).renameOldStyleVersionDirectories(name);
            Path providersDir = Kitefile.globalProvidersPath().resolve(name);

            if (!Files.exists(providersDir)) {
                return null;
            }

            // For specific version, look in that version directory (the same name every install uses)
            if (!"latest".equals(version)) {
                Path versionDir = providersDir.resolve(PackageSpec.versionDirectory(name, version));
                var exec = findExecutable(versionDir);
                // The id the engine uses too: provider.json holds the bare version
                return exec != null ? new ResolvedProvider(exec, PackageSpec.versionDirectory(name, version)) : null;
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
                    var actualVersion = PackageSpec.versionDirectory(name, currentVersion.getFileName().toString());
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
                        var actualVersion = PackageSpec.versionDirectory(name, highestVersion.get().getFileName().toString());
                        return new ResolvedProvider(exec, actualVersion);
                    }
                }
            }

            return null;
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
            }
    )
    @Slf4j
    public static class StopCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "provider[@version]",
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
                    version = PackageSpec.normalizeVersion(parts[1]);
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
                // The id the engine uses too: provider.json holds the bare version
                return name + "@" + PackageSpec.versionDirectory(name, version);
            }
            new ProviderInstaller(Kitefile.globalProvidersPath()).renameOldStyleVersionDirectories(name);

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
                var actualVersion = PackageSpec.versionDirectory(name, versionDir);
                return name + "@" + actualVersion;
            }

            // Find highest version directory
            try (var stream = Files.list(providersDir)) {
                var highestVersion = stream
                        .filter(Files::isDirectory)
                        .filter(v -> !v.getFileName().toString().equals("current"))
                        .max((a, b) -> compareVersions(a.getFileName().toString(), b.getFileName().toString()));

                if (highestVersion.isPresent()) {
                    var actualVersion = PackageSpec.versionDirectory(name, highestVersion.get().getFileName().toString());
                    return name + "@" + actualVersion;
                }
            }

            return name + "@" + version;
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

        /**
         * Stops every provider recorded in the run directory. The ids come from
         * {@link RunDir}, which writes them as file names: a Terraform provider loaded
         * through the bridge, {@code tf/random@3.9.1}, has files named
         * {@code tf%2Frandom@3.9.1.*}, so a file name is not an id (kitecorp/kite#85).
         */
        private Integer stopAllProviders() {
            var running = new PluginProcessManager().listRunningProviders();
            int stopped = 0;
            for (var pluginId : running) {
                if (stopProvider(pluginId) == 0) {
                    stopped++;
                }
            }

            // A provider that did not answer keeps its record; a stale record makes the
            // next engine run try a dead port first ("UNAVAILABLE: io exception")
            var runDir = RunDir.defaultDir();
            runDir.recordedIds().forEach(runDir::cleanup);

            Console.println(running.isEmpty() ? "No running providers" : "Stopped " + stopped + " provider(s)");
            return 0;
        }
    }

    /**
     * Show status of running provider processes.
     */
    @Command(
            name = "status",
            description = "Show running provider processes"
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
