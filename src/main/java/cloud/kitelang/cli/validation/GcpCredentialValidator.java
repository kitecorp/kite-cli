package cloud.kitelang.cli.validation;

import cloud.kitelang.engine.kitefile.Dependencies.Credential;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Validates GCP credentials using the gcloud CLI.
 * Calls 'gcloud auth list' and 'gcloud config get project' to verify credentials work.
 */
@Slf4j
public class GcpCredentialValidator implements CredentialValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public String getProviderType() {
        return "gcp";
    }

    @Override
    public boolean isCliAvailable() {
        try {
            var process = new ProcessBuilder("gcloud", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public ValidationResult validate(Credential credential) {
        if (!isCliAvailable()) {
            return ValidationResult.cliNotFound("gcloud");
        }

        try {
            // First check if there's an active account
            var authResult = checkAuth();
            if (!authResult.success()) {
                return authResult;
            }

            // Then check the project
            var projectResult = checkProject(credential);
            if (!projectResult.success()) {
                return projectResult;
            }

            return ValidationResult.success(
                    projectResult.identity(),
                    authResult.details()
            );

        } catch (IOException | InterruptedException e) {
            log.debug("GCP validation failed", e);
            return ValidationResult.failure("Failed to run gcloud CLI: " + e.getMessage());
        }
    }

    /**
     * Checks if gcloud has an active authenticated account.
     */
    private ValidationResult checkAuth() throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("gcloud");
        command.add("auth");
        command.add("list");
        command.add("--filter=status:ACTIVE");
        command.add("--format=value(account)");

        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        var output = new String(process.getInputStream().readAllBytes()).trim();
        var completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            return ValidationResult.failure("gcloud CLI timed out");
        }

        if (process.exitValue() != 0 || output.isBlank()) {
            return ValidationResult.failure(
                    "No active GCP account. Run: gcloud auth login"
            );
        }

        return ValidationResult.success(null, "Account: " + output);
    }

    /**
     * Checks if the configured project exists and is accessible.
     */
    private ValidationResult checkProject(Credential credential) throws IOException, InterruptedException {
        String project = credential.project();

        // If no project specified, get the default
        if (project == null || project.isBlank()) {
            var getProject = new ProcessBuilder("gcloud", "config", "get", "project")
                    .redirectErrorStream(true)
                    .start();
            project = new String(getProject.getInputStream().readAllBytes()).trim();
            getProject.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (project.isBlank()) {
                return ValidationResult.failure(
                        "No GCP project configured. Run: gcloud config set project PROJECT_ID"
                );
            }
        }

        // Verify project is accessible
        var command = new ArrayList<String>();
        command.add("gcloud");
        command.add("projects");
        command.add("describe");
        command.add(project);
        command.add("--format=value(projectId)");

        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        var output = new String(process.getInputStream().readAllBytes()).trim();
        var completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            return ValidationResult.failure("gcloud CLI timed out");
        }

        if (process.exitValue() != 0) {
            if (output.contains("not found") || output.contains("does not exist")) {
                return ValidationResult.failure(
                        "GCP project '" + project + "' not found or not accessible"
                );
            }
            if (output.contains("permission denied") || output.contains("PERMISSION_DENIED")) {
                return ValidationResult.failure(
                        "No permission to access GCP project '" + project + "'"
                );
            }
            return ValidationResult.failure("GCP project check failed: " + output);
        }

        return ValidationResult.success("Project: " + project, null);
    }
}
