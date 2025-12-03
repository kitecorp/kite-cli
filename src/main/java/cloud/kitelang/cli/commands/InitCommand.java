package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.generator.ProjectStructureGenerator;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine.Command;
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
    description = "Initialize a new Kite project with multi-cloud structure",
    mixinStandardHelpOptions = true
)
@Log4j2
public class InitCommand implements Callable<Integer> {

    @Parameters(
        index = "0",
        description = "Project name (defaults to current directory name)",
        arity = "0..1"
    )
    private String projectName;

    @Option(
        names = {"-d", "--directory"},
        description = "Target directory (defaults to current directory or ./project-name)"
    )
    private File targetDirectory;

    @Option(
        names = {"-p", "--providers"},
        description = "Cloud providers to configure (comma-separated: aws,gcp,azure)",
        split = ",",
        defaultValue = "aws"
    )
    private String[] providers;

    @Option(
        names = {"-e", "--environments"},
        description = "Environments to create (comma-separated: dev,staging,prod)",
        split = ",",
        defaultValue = "dev,staging,prod"
    )
    private String[] environments;

    @Option(
        names = {"-f", "--force"},
        description = "Force initialization even if directory is not empty"
    )
    private boolean force;

    @Override
    public Integer call() {
        try {
            // Determine project name and target directory
            var projectDir = determineProjectDirectory();
            var name = determineProjectName(projectDir);

            log.info("Initializing Kite project: {}", name);
            log.info("Target directory: {}", projectDir.toAbsolutePath());

            // Create the project structure
            var generator = new ProjectStructureGenerator();
            generator.generate(projectDir, name, providers, environments, force);

            System.out.println("\n✓ Successfully initialized Kite project: " + name);
            System.out.println("\nNext steps:");
            System.out.println("  1. cd " + projectDir.getFileName());
            System.out.println("  2. Edit kite.yaml to configure your project");
            System.out.println("  3. Add your infrastructure in resources/main.kite");
            System.out.println("  4. Run 'kite validate' to check your configuration");
            System.out.println("  5. Run 'kite plan' to preview changes");
            System.out.println("  6. Run 'kite apply' to provision resources");

            return 0;
        } catch (Exception e) {
            log.error("Failed to initialize project", e);
            System.err.println("Error: " + e.getMessage());
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
