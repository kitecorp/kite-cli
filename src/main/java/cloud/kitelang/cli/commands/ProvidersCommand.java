package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.util.ProgressBar;
import cloud.kitelang.engine.distribution.ProviderInstaller;
import cloud.kitelang.engine.distribution.ProviderSpec;
import cloud.kitelang.engine.kitefile.KiteInjector;
import cloud.kitelang.engine.kitefile.Kitefile;
import lombok.extern.log4j.Log4j2;
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
@Log4j2
public class ProvidersCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Usage: kite providers <command>");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  install    Install providers");
        System.out.println("  list       List installed providers");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  kite providers install                              # Install from kitefile.yml");
        System.out.println("  kite providers install aws                          # Download from GitHub Releases");
        System.out.println("  kite providers install aws --from-source            # Clone and build from source");
        System.out.println("  kite providers install aws@1.0.0                    # Install specific version");
        System.out.println("  kite providers install myp --git github.com/org/p   # Install from git repo");
        System.out.println("  kite providers list                                 # List global providers");
        System.out.println("  kite providers list --local                         # List project providers");
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
    @Log4j2
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
                System.err.println("✗ Error: " + e.getMessage());
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

            System.out.println("Installing provider: " + name + "@" + version);

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
                System.err.println("✗ Failed to install " + name + ": " + e.getMessage());
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
                System.err.println("✗ No kitefile.yml found in current directory");
                System.err.println("  Run 'kite new' to create a project, or specify a provider name");
                return 1;
            }

            var kitefile = KiteInjector.createkitefile();
            var config = kitefile.config();

            if (config.providers().isEmpty()) {
                System.out.println("No providers configured in kitefile.yml");
                return 0;
            }

            System.out.println("Installing " + config.providers().size() + " provider(s)...");
            System.out.println();

            int installed = 0;
            int failed = 0;

            for (var dep : config.providers()) {
                // Determine display version (version, ref, or "latest")
                String displayVersion = dep.version() != null ? dep.version()
                        : dep.ref() != null ? dep.ref() : "latest";

                var specBuilder = ProviderSpec.builder()
                        .name(dep.name())
                        .version(dep.version())
                        .git(dep.git());

                // Parse GitHub URLs automatically to extract ref and path
                if (dep.git() != null) {
                    var parsed = ProviderSpec.parseGitHubUrl(dep.name(), dep.git());
                    // Use parsed values unless explicitly overridden in kitefile
                    specBuilder.ref(dep.ref() != null && !dep.ref().equals("main") ? dep.ref() : parsed.getRef());
                    specBuilder.path(dep.path() != null ? dep.path() : parsed.getPath());
                } else {
                    specBuilder.ref(dep.ref());
                    specBuilder.path(dep.path());
                }

                var spec = specBuilder.build();

                if (installer.isInstalled(spec)) {
                    System.out.println("  ✓ " + dep.name() + "@" + displayVersion + " (already installed)");
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
                    System.out.println("  ✗ " + dep.name() + "@" + displayVersion + " - " + e.getMessage());
                    failed++;
                }
            }

            System.out.println();
            if (failed == 0) {
                System.out.println("✓ All providers installed successfully");
            } else {
                System.out.println("Installed: " + installed + ", Failed: " + failed);
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
    @Log4j2
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

            System.out.println("Providers directory: " + providersPath);
            System.out.println();

            if (!Files.exists(providersPath)) {
                System.out.println("No providers installed");
                return 0;
            }

            try (var stream = Files.list(providersPath)) {
                var providers = stream
                        .filter(Files::isDirectory)
                        .filter(p -> Files.exists(p.resolve("provider.json")))
                        .toList();

                if (providers.isEmpty()) {
                    System.out.println("No providers installed");
                    return 0;
                }

                System.out.println("Installed providers:");
                for (var provider : providers) {
                    System.out.println("  " + provider.getFileName());
                }

                return 0;
            } catch (Exception e) {
                System.err.println("✗ Error listing providers: " + e.getMessage());
                return 1;
            }
        }
    }
}
