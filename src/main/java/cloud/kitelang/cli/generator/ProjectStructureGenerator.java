package cloud.kitelang.cli.generator;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Generates a new Kite project by copying templates from resources.
 */
@Log4j2
public class ProjectStructureGenerator {

    private static final String TEMPLATES_PATH = "templates";

    public void generate(Path projectDir, String projectName, String[] providers, String[] environments, boolean force)
            throws IOException {

        if (Files.exists(projectDir) && !isEmpty(projectDir) && !force) {
            throw new IllegalStateException(
                    "Directory is not empty. Use --force to initialize anyway."
            );
        }

        Files.createDirectories(projectDir);

        var variables = Map.of(
                "projectName", projectName
        );

        // Copy base templates
        copyTemplates(projectDir, variables, providers, environments);

        log.debug("Project structure created successfully");
    }

    private void copyTemplates(Path projectDir, Map<String, String> variables,
                               String[] providers, String[] environments) throws IOException {
        var providerSet = Set.of(providers);
        var envSet = Set.of(environments);

        // Get the templates directory from resources
        var templatesUri = getTemplatesUri();

        if (templatesUri.getScheme().equals("jar")) {
            copyFromJar(templatesUri, projectDir, variables, providerSet, envSet);
        } else {
            copyFromFileSystem(Paths.get(templatesUri), projectDir, variables, providerSet, envSet);
        }
    }

    private URI getTemplatesUri() throws IOException {
        try {
            var resource = getClass().getClassLoader().getResource(TEMPLATES_PATH);
            if (resource == null) {
                throw new IOException("Templates not found in resources");
            }
            return resource.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid templates URI", e);
        }
    }

    private void copyFromJar(URI jarUri, Path projectDir, Map<String, String> variables,
                             Set<String> providers, Set<String> environments) throws IOException {
        var parts = jarUri.toString().split("!");
        try (var fs = FileSystems.newFileSystem(URI.create(parts[0]), Collections.emptyMap())) {
            var templatesRoot = fs.getPath(parts[1]);
            copyDirectory(templatesRoot, projectDir, variables, providers, environments);
        }
    }

    private void copyFromFileSystem(Path templatesRoot, Path projectDir, Map<String, String> variables,
                                    Set<String> providers, Set<String> environments) throws IOException {
        copyDirectory(templatesRoot, projectDir, variables, providers, environments);
    }

    private void copyDirectory(Path source, Path target, Map<String, String> variables,
                               Set<String> providers, Set<String> environments) throws IOException {
        try (var stream = Files.walk(source)) {
            for (var path : stream.toList()) {
                var relativePath = source.relativize(path).toString();

                // Skip if not applicable for selected providers/environments
                if (!shouldInclude(relativePath, providers, environments)) {
                    continue;
                }

                // Rename special files (gitignore.template -> .gitignore)
                var targetRelPath = renameSpecialFiles(relativePath);
                var targetPath = target.resolve(targetRelPath);

                if (Files.isDirectory(path)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    var content = Files.readString(path);
                    content = replaceVariables(content, variables);
                    Files.writeString(targetPath, content);
                    log.debug("Created: {}", targetPath);
                }
            }
        }
    }

    private String renameSpecialFiles(String path) {
        // Gradle excludes .gitignore, so we rename it
        return path.replace("gitignore.template", ".gitignore");
    }

    private boolean shouldInclude(String path, Set<String> providers, Set<String> environments) {
        // Cloud provider filtering
        if (path.startsWith("cloud/aws") && !providers.contains("aws")) return false;
        if (path.startsWith("cloud/gcp") && !providers.contains("gcp")) return false;
        if (path.startsWith("cloud/azure") && !providers.contains("azure")) return false;

        // Mixin filtering
        if (path.equals("mixins/aws.kite") && !providers.contains("aws")) return false;
        if (path.equals("mixins/gcp.kite") && !providers.contains("gcp")) return false;
        if (path.equals("mixins/azure.kite") && !providers.contains("azure")) return false;

        // Component mixin filtering
        if (path.contains("/mixins/aws.kite") && !providers.contains("aws")) return false;
        if (path.contains("/mixins/gcp.kite") && !providers.contains("gcp")) return false;
        if (path.contains("/mixins/azure.kite") && !providers.contains("azure")) return false;

        // Environment filtering
        if (path.startsWith("environments/dev") && !environments.contains("dev")) return false;
        if (path.startsWith("environments/staging") && !environments.contains("staging")) return false;
        if (path.startsWith("environments/prod") && !environments.contains("prod")) return false;

        return true;
    }

    private String replaceVariables(String content, Map<String, String> variables) {
        var result = content;
        for (var entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private boolean isEmpty(Path directory) throws IOException {
        if (!Files.exists(directory)) return true;
        try (var stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        }
    }
}
