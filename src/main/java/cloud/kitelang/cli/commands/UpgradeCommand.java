package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Upgrade Kite CLI to the latest or specified version.
 *
 * <p>This is a convenience command that wraps 'kite version install --use'.</p>
 */
@Command(
        name = "upgrade",
        description = "Upgrade Kite CLI to the latest or specified version",
        footer = {
                "",
                "Examples:",
                "  kite upgrade                          Upgrade to latest version",
                "  kite upgrade 1.2.0                    Upgrade to specific version"
        },
        mixinStandardHelpOptions = true
)
@Slf4j
public class UpgradeCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            paramLabel = "VERSION",
            description = "Version to upgrade to (default: latest)",
            arity = "0..1",
            defaultValue = "latest"
    )
    private String version;

    @Override
    public Integer call() {
        try {
            // Resolve 'latest' to actual version
            var targetVersion = version;
            if ("latest".equalsIgnoreCase(version)) {
                targetVersion = VersionCommand.fetchLatestVersion();
                if (targetVersion == null) {
                    Console.error("Could not determine latest version");
                    return 1;
                }
            }

            // Check if already on this version
            var currentVersion = VersionCommand.getCurrentVersion();
            if (targetVersion.equals(currentVersion)) {
                Console.success("Already on version " + targetVersion);
                return 0;
            }

            // Check if version is already installed
            var installedVersions = VersionCommand.getInstalledVersions();
            if (installedVersions.contains(targetVersion)) {
                Console.println("Version " + targetVersion + " is already installed, switching...");
                return VersionCommand.switchToVersion(targetVersion);
            }

            // Install and switch to the new version
            Console.println("Upgrading to Kite " + targetVersion + "...");
            Console.println();

            var exitCode = VersionCommand.runInstallScript(targetVersion);

            if (exitCode == 0) {
                Console.println();
                Console.success("Upgraded to version " + targetVersion);
                Console.println();
                Console.println("Run 'kite version' to verify the upgrade.");
            }

            return exitCode;
        } catch (Exception e) {
            Console.error("Upgrade failed: " + e.getMessage());
            log.debug("Upgrade failed", e);
            return 1;
        }
    }
}
