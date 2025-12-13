package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.generator.ProjectStructureGenerator;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    @Option(
            names = {"-i", "--interactive"},
            description = "Interactive mode - prompts for project configuration"
    )
    private boolean interactive;

    @Override
    public Integer call() {
        try {
            if (interactive) {
                runInteractiveMode();
            }

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

    /**
     * Runs interactive mode to gather project configuration from user input.
     */
    private void runInteractiveMode() throws IOException {
        var reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("Kite Project Setup");
        System.out.println("==================");
        System.out.println();

        // Project name
        String defaultName = projectName != null ? projectName : Paths.get(".").toAbsolutePath().getFileName().toString();
        System.out.print("Project name [" + defaultName + "]: ");
        String inputName = reader.readLine().trim();
        if (!inputName.isEmpty()) {
            projectName = inputName;
        } else if (projectName == null) {
            projectName = defaultName;
        }

        // Providers selection
        System.out.println();
        System.out.println("Select cloud providers (comma-separated):");
        System.out.println("  1. aws (Amazon Web Services)");
        System.out.println("  2. gcp (Google Cloud Platform)");
        System.out.println("  3. azure (Microsoft Azure)");
        System.out.println("  4. files (Local filesystem - for testing)");
        System.out.print("Providers [aws]: ");
        String inputProviders = reader.readLine().trim();
        if (!inputProviders.isEmpty()) {
            providers = parseProviders(inputProviders);
        }

        // Environments selection
        System.out.println();
        System.out.println("Select environments (comma-separated):");
        System.out.println("  Common: dev, staging, prod");
        System.out.print("Environments [dev,staging,prod]: ");
        String inputEnvs = reader.readLine().trim();
        if (!inputEnvs.isEmpty()) {
            environments = inputEnvs.split(",");
            for (int i = 0; i < environments.length; i++) {
                environments[i] = environments[i].trim();
            }
        }

        System.out.println();
    }

    /**
     * Parses provider input, handling both names and numbers.
     */
    private String[] parseProviders(String input) {
        var providerMap = java.util.Map.of(
            "1", "aws",
            "2", "gcp",
            "3", "azure",
            "4", "files"
        );

        List<String> result = new ArrayList<>();
        for (String part : input.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (providerMap.containsKey(trimmed)) {
                result.add(providerMap.get(trimmed));
            } else if (Arrays.asList("aws", "gcp", "azure", "files").contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? new String[]{"aws"} : result.toArray(new String[0]);
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
