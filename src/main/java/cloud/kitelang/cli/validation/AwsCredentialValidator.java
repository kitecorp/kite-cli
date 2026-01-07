package cloud.kitelang.cli.validation;

import cloud.kitelang.engine.kitefile.Dependencies.Credential;
import cloud.kitelang.cli.util.JacksonMappers;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Validates AWS credentials using the AWS CLI.
 * Calls 'aws sts get-caller-identity' to verify credentials work.
 */
@Slf4j
public class AwsCredentialValidator implements CredentialValidator {

    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public String getProviderType() {
        return "aws";
    }

    @Override
    public boolean isCliAvailable() {
        try {
            var process = new ProcessBuilder("aws", "--version")
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
            return ValidationResult.cliNotFound("aws");
        }

        try {
            var command = buildCommand(credential);
            log.debug("Running: {}", String.join(" ", command));

            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            var output = new String(process.getInputStream().readAllBytes());
            var completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return ValidationResult.failure("AWS CLI timed out after " + TIMEOUT_SECONDS + " seconds");
            }

            if (process.exitValue() != 0) {
                return parseError(output, credential);
            }

            return parseSuccess(output);

        } catch (IOException | InterruptedException e) {
            log.debug("AWS validation failed", e);
            return ValidationResult.failure("Failed to run AWS CLI: " + e.getMessage());
        }
    }

    /**
     * Builds the AWS CLI command with appropriate flags.
     */
    private java.util.List<String> buildCommand(Credential credential) {
        var command = new ArrayList<String>();
        command.add("aws");
        command.add("sts");
        command.add("get-caller-identity");
        command.add("--output");
        command.add("json");

        // Add profile if specified
        if (credential.profile() != null && !credential.profile().isBlank()) {
            command.add("--profile");
            command.add(credential.profile());
        }

        // Add region if specified
        if (credential.region() != null && !credential.region().isBlank()) {
            command.add("--region");
            command.add(credential.region());
        }

        return command;
    }

    /**
     * Parses successful response from get-caller-identity.
     */
    private ValidationResult parseSuccess(String output) {
        try {
            var json = JacksonMappers.json().readTree(output);
            var account = json.path("Account").asText();
            var arn = json.path("Arn").asText();
            var userId = json.path("UserId").asText();

            return ValidationResult.success(
                    "Account: " + account,
                    "ARN: " + arn + ", UserId: " + userId
            );
        } catch (Exception e) {
            log.debug("Failed to parse AWS response: {}", output, e);
            return ValidationResult.success("Authenticated", output.trim());
        }
    }

    /**
     * Parses error response and provides helpful message.
     */
    private ValidationResult parseError(String output, Credential credential) {
        var message = output.trim();

        // Common error patterns
        if (message.contains("could not be found")) {
            var profile = credential.profile() != null ? credential.profile() : "default";
            return ValidationResult.failure(
                    "AWS profile '" + profile + "' not found. " +
                    "Configure it with: aws configure --profile " + profile
            );
        }

        if (message.contains("ExpiredToken")) {
            return ValidationResult.failure(
                    "AWS credentials expired. Refresh with: aws sso login --profile " +
                    (credential.profile() != null ? credential.profile() : "default")
            );
        }

        if (message.contains("InvalidClientTokenId") || message.contains("SignatureDoesNotMatch")) {
            return ValidationResult.failure("AWS credentials are invalid. Check your access key and secret.");
        }

        if (message.contains("Unable to locate credentials")) {
            return ValidationResult.failure(
                    "No AWS credentials found. Configure with: aws configure" +
                    (credential.profile() != null ? " --profile " + credential.profile() : "")
            );
        }

        // Generic error
        return ValidationResult.failure("AWS authentication failed: " + message);
    }
}
