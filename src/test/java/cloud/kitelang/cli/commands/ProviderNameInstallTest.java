package cloud.kitelang.cli.commands;

import cloud.kitelang.packages.install.PackageInstallException;
import cloud.kitelang.packages.install.PackageSpec;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code kite providers install <name>[@<version>]}, without a kitefile. */
class ProviderNameInstallTest {

    @Test
    void officialNameAndVersion_installsItsReleaseFromTheProvidersRepository() throws Exception {
        var spec = ProvidersCommand.InstallCommand.specForName("aws@0.3.0", null, null, null);

        assertEquals(PackageSpec.builder()
                .name("aws").version("v0.3.0").git("github.com/kitecorp/kite-providers/aws").ref("aws-v0.3.0").path("aws")
                .build(), spec);
    }

    @Test
    void otherNameWithGit_installsFromThatRepository() throws Exception {
        var spec = ProvidersCommand.InstallCommand.specForName("foo@1.0", "github.com/org/foo", null, null);

        assertEquals(PackageSpec.builder()
                .name("foo").version("v1.0").git("github.com/org/foo").ref("foo-v1.0")
                .build(), spec);
    }

    @Test
    void otherNameWithoutGit_isRefusedNamingTheGitSetting() {
        var ex = assertThrows(PackageInstallException.class,
                () -> ProvidersCommand.InstallCommand.specForName("foo@1.0", null, null, null));

        assertEquals("Provider foo has no git setting. Pass --git <repository URL>, or add git: <repository URL>"
                + " to its entry in kitefile.yml; only the official providers (aws, azure, files, gcp) install"
                + " from a name and version.", ex.getMessage());
    }

    @Test
    void otherNameWithoutGit_exitsWithOne() {
        var exitCode = new CommandLine(new ProvidersCommand.InstallCommand()).execute("foo@1.0");

        assertEquals(1, exitCode);
    }
}
