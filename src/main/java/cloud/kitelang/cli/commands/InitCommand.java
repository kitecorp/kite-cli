package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.generator.ProjectStructureGenerator;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Initialize a new Kite project with the standard multi-cloud structure.
 */
@Command(
        name = "init",
        description = "Initialize a new Kite project with multi-cloud structure"
)
@Log4j2
public class InitCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            paramLabel = "NAME",
            description = "Project name (defaults to current directory name)",
            arity = "0..1"
    )
    private String projectName;

    @Option(
            names = {"-d", "--directory"},
            paramLabel = "DIR",
            description = "Target directory (default: ./<NAME>)"
    )
    private File targetDirectory;

    @Option(
            names = {"-p", "--providers"},
            paramLabel = "CLOUDS",
            arity = "1",
            description = "Providers: aws,gcp,azure (default: aws)",
            split = ",",
            defaultValue = "aws"
    )
    private String[] providers;

    @Option(
            names = {"-e", "--env"},
            paramLabel = "ENVS",
            arity = "1",
            description = "Environments: dev,staging,prod (default: all)",
            split = ",",
            defaultValue = "dev,staging,prod"
    )
    private String[] environments;

    @Option(
            names = {"-f", "--force"},
            description = "Force init even if directory is not empty"
    )
    private boolean force;

    @Override
    public Integer call() {
        try {
            var projectDir = determineProjectDirectory();
            var name = determineProjectName(projectDir);

            System.out.println("Creating project: " + name);
            System.out.println("         Location: " + projectDir.toAbsolutePath());
            System.out.println();

            var generator = new ProjectStructureGenerator();
            generator.generate(projectDir, name, providers, environments, force);

            System.out.println("✓ Project initialized successfully");
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  cd " + projectDir.getFileName());
            System.out.println("  kite validate    # Check configuration");
            System.out.println("  kite plan        # Preview changes");
            System.out.println("  kite apply       # Provision resources");

            return 0;
        } catch (Exception e) {
            System.err.println("✗ Error: " + e.getMessage());
            log.debug("Failed to initialize project", e);
            return 1;
        }
    }

    private Path determineProjectDirectory() {
        if (targetDirectory != null) {
            return targetDirectory.toPath();
        }

        if (projectName != null && !projectName.isBlank()) {
            return Paths.get(projectName);
        }

        return Paths.get(".");
    }

    private String determineProjectName(Path projectDir) {
        if (projectName != null && !projectName.isBlank()) {
            return projectName;
        }

        return projectDir.toAbsolutePath().getFileName().toString();
    }
}
