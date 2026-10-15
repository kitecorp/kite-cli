package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.commands.ProvidersCommand.InstallCommand.KitefileInstallPlan;
import cloud.kitelang.engine.kitefile.Dependency;
import cloud.kitelang.packages.install.PackageSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitefileInstallPlanTest {

    @Test
    void officialProviderInstallsFromItsReleases_otherWithoutGitIsRefused_installedIsSkipped() {
        var plan = ProvidersCommand.InstallCommand.planKitefileInstall(List.of(
                        new Dependency("aws", "0.3.0"),
                        new Dependency("custom", "1.0.0"),
                        new Dependency("gcp", "0.2.0")),
                spec -> spec.getName().equals("gcp"));

        assertEquals(new KitefileInstallPlan(
                List.of("gcp@v0.2.0"),
                List.of(PackageSpec.builder()
                        .name("aws").version("v0.3.0").git("github.com/kitecorp/kite-providers/aws").path("aws").build()),
                List.of("Provider custom has no git setting in kitefile.yml. Add git: <repository URL> to its entry;"
                        + " only the official providers (aws, azure, files, gcp) install from a name and version.")), plan);
    }
}
