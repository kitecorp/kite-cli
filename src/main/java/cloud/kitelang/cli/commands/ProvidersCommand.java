package cloud.kitelang.cli.commands;

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
        System.out.println("  kite providers install aws                          # Install latest version");
        System.out.println("  kite providers install aws@1.0.0                    # Install specific version");
        System.out.println("  kite providers install myp --git github.com/org/p   # Install from git");
        System.out.println("  kite providers list --global                        # List global providers");
        return 0;
    }

    /**
     * Install providers from kitefile.yml or by name.
     */
    @Command(
            name = "install",
            description = "Install providers from registry or git",
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
                names = {"--global"},
                description = "Install to global providers directory (~/.kite/providers)"
        )
        private boolean global;

        @Override
        public Integer call() {
            try {
                Path providersPath = global
                        ? Kitefile.globalProvidersPath()
                        : Kitefile.localProvidersPath();

                var installer = new ProviderInstaller(providersPath);

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

            if (gitUrl != null) {
                specBuilder.git(gitUrl);
                if (gitRef != null) {
                    specBuilder.ref(gitRef);
                }
            } else {
                specBuilder.version(version);
            }

            var spec = specBuilder.build();

            try {
                var path = installer.install(spec);
                System.out.println("✓ Installed " + name + " to " + path);
                return 0;
            } catch (Exception e) {
                System.err.println("✗ Failed to install " + name + ": " + e.getMessage());
                return 1;
            }
        }

        private Integer installFromKitefile(ProviderInstaller installer) throws Exception {
            Path kitefilePath = Path.of("kitefile.yml");

            if (!Files.exists(kitefilePath)) {
                System.err.println("✗ No kitefile.yml found in current directory");
                System.err.println("  Run 'kite init' to create a project, or specify a provider name");
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
                var spec = ProviderSpec.builder()
                        .name(dep.name())
                        .version(dep.version())
                        .git(dep.uri())
                        .build();

                if (installer.isInstalled(spec)) {
                    System.out.println("  ✓ " + dep.name() + " (already installed)");
                    installed++;
                    continue;
                }

                try {
                    installer.install(spec);
                    System.out.println("  ✓ " + dep.name() + "@" + dep.version());
                    installed++;
                } catch (Exception e) {
                    System.out.println("  ✗ " + dep.name() + " - " + e.getMessage());
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
            mixinStandardHelpOptions = true
    )
    @Log4j2
    public static class ListCommand implements Callable<Integer> {

        @Option(
                names = {"--global"},
                description = "List global providers (~/.kite/providers)"
        )
        private boolean global;

        @Override
        public Integer call() {
            Path providersPath = global
                    ? Kitefile.globalProvidersPath()
                    : Kitefile.localProvidersPath();

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
