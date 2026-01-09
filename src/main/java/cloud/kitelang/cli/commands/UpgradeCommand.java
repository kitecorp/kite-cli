package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Upgrade Kite CLI to the latest or specified version.
 *
 * <p>This command automatically detects the installation method (JBang or install script)
 * and uses the appropriate upgrade mechanism.</p>
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
            // Detect installation method and use appropriate upgrade
            if (VersionCommand.isJBangInstallation()) {
                return upgradeViaJBang();
            } else {
                return upgradeViaInstallScript();
            }
        } catch (Exception e) {
            Console.error("Upgrade failed: " + e.getMessage());
            log.debug("Upgrade failed", e);
            return 1;
        }
    }

    private int upgradeViaJBang() throws Exception {
        var currentVersion = VersionCommand.getCurrentVersion();

        // Resolve 'latest' to actual version for display
        var targetVersion = version;
        if ("latest".equalsIgnoreCase(version)) {
            targetVersion = VersionCommand.fetchLatestVersion();
            if (targetVersion == null) {
                Console.warning("Could not determine latest version, proceeding with upgrade...");
                targetVersion = "latest";
            }
        }

        // Check if already on this version
        if (!"latest".equals(targetVersion) && targetVersion.equals(currentVersion)) {
            Console.success("Already on version " + targetVersion);
            return 0;
        }

        Console.println("Upgrading via JBang...");
        if (!"latest".equals(targetVersion)) {
            Console.println("Target version: " + targetVersion);
        }
        Console.println();

        var exitCode = VersionCommand.runJBangUpgrade(version);

        if (exitCode == 0) {
            Console.println();
            Console.success("Upgrade complete!");
            Console.println();
            Console.println("Run 'kite --version' to verify.");
        }

        return exitCode;
    }

    private int upgradeViaInstallScript() throws Exception {
        // Resolve 'latest' to actual version
        var targetVersion = version;
        if ("latest".equalsIgnoreCase(version)) {
            targetVersion = VersionCommand.fetchLatestVersion();
            if (targetVersion == null) {
                Console.error("Could not determine latest version (GitHub API rate limit?)");
                Console.println("  Try: kite upgrade <version>  (e.g., kite upgrade 0.3.1)");
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
    }
}
