package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import cloud.kitelang.cli.util.ProgressBar;
import cloud.kitelang.engine.distribution.ProviderInstaller;
import cloud.kitelang.engine.distribution.ProviderSpec;
import cloud.kitelang.engine.kitefile.KiteInjector;
import cloud.kitelang.engine.kitefile.Kitefile;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
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
                "  kite providers install aws            Install latest AWS provider",
                "  kite providers install aws@1.0.0      Install specific version",
                "  kite providers install myp --git github.com/org/provider",
                "  kite providers install myp --git github.com/org/repo/tree/dev/aws",
                "  kite providers list                   List global providers",
                "  kite providers list --local           List project providers"
        },
        mixinStandardHelpOptions = true,
        subcommands = {
                ProvidersCommand.InstallCommand.class,
                ProvidersCommand.ListCommand.class
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
        Console.println();
        Console.println("Examples:");
        Console.println("  kite providers install                              # Install from kitefile.yml");
        Console.println("  kite providers install aws                          # Download from GitHub Releases");
        Console.println("  kite providers install aws --from-source            # Clone and build from source");
        Console.println("  kite providers install aws@1.0.0                    # Install specific version");
        Console.println("  kite providers install myp --git github.com/org/p   # Install from git repo");
        Console.println("  kite providers list                                 # List global providers");
        Console.println("  kite providers list --local                         # List project providers");
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
                    "  kite providers install aws            Install latest (from GitHub Releases)",
                    "  kite providers install aws@1.0.0      Install specific version",
                    "  kite providers install myp --git github.com/org/provider",
                    "  kite providers install myp --git github.com/org/repo/aws",
                    "  kite providers install aws --from-source   Clone and build from source",
                    "  kite providers install aws --local    Install to .kite/providers"
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
                version = parts[1];
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
                    specBuilder.ref(name + "-v" + version);
                }

                if (gitPath != null) {
                    specBuilder.path(gitPath);
                } else if (parsed.getPath() != null) {
                    specBuilder.path(parsed.getPath());
                }
            } else {
                specBuilder.version(version);
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
                // Determine display version (version, ref, or "latest")
                String displayVersion = dep.version() != null ? dep.version()
                        : dep.ref() != null ? dep.ref() : "latest";

                // Resolve official provider URLs if no git specified
                String gitUrl = dep.git();
                if (gitUrl == null) {
                    gitUrl = getOfficialProviderUrl(dep.name());
                }

                var specBuilder = ProviderSpec.builder()
                        .name(dep.name())
                        .version(dep.version())
                        .git(gitUrl);

                // Parse GitHub URLs automatically to extract ref and path
                if (gitUrl != null) {
                    var parsed = ProviderSpec.parseGitHubUrl(dep.name(), gitUrl);
                    // Use parsed values unless explicitly overridden in kitefile
                    specBuilder.ref(dep.ref() != null && !dep.ref().equals("main") ? dep.ref() : parsed.getRef());
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
                var providers = stream
                        .filter(Files::isDirectory)
                        .filter(p -> Files.exists(p.resolve("provider.json")))
                        .toList();

                if (providers.isEmpty()) {
                    Console.println("No providers installed");
                    return 0;
                }

                Console.println("Installed providers:");
                for (var provider : providers) {
                    Console.println("  " + provider.getFileName());
                }

                return 0;
            } catch (Exception e) {
                Console.error("Error listing providers: " + e.getMessage());
                return 1;
            }
        }
    }
}
