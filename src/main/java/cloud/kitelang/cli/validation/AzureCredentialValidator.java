package cloud.kitelang.cli.validation;

import cloud.kitelang.engine.kitefile.Dependencies.Credential;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Validates Azure credentials using the Azure CLI.
 * Calls 'az account show' to verify credentials work.
 */
@Slf4j
public class AzureCredentialValidator implements CredentialValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public String getProviderType() {
        return "azure";
    }

    @Override
    public boolean isCliAvailable() {
        try {
            var process = new ProcessBuilder("az", "--version")
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
            return ValidationResult.cliNotFound("az");
        }

        try {
            // Check current account/subscription
            var command = buildCommand(credential);
            log.debug("Running: {}", String.join(" ", command));

            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            var output = new String(process.getInputStream().readAllBytes());
            var completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return ValidationResult.failure("Azure CLI timed out after " + TIMEOUT_SECONDS + " seconds");
            }

            if (process.exitValue() != 0) {
                return parseError(output, credential);
            }

            return parseSuccess(output, credential);

        } catch (IOException | InterruptedException e) {
            log.debug("Azure validation failed", e);
            return ValidationResult.failure("Failed to run Azure CLI: " + e.getMessage());
        }
    }

    /**
     * Builds the Azure CLI command.
     */
    private java.util.List<String> buildCommand(Credential credential) {
        var command = new ArrayList<String>();
        command.add("az");
        command.add("account");
        command.add("show");
        command.add("--output");
        command.add("json");

        // If subscription specified, check that specific one
        if (credential.subscription() != null && !credential.subscription().isBlank()) {
            command.add("--subscription");
            command.add(credential.subscription());
        }

        return command;
    }

    /**
     * Parses successful response from az account show.
     */
    private ValidationResult parseSuccess(String output, Credential credential) {
        try {
            var json = MAPPER.readTree(output);
            var subscriptionId = json.path("id").asText();
            var subscriptionName = json.path("name").asText();
            var tenantId = json.path("tenantId").asText();
            var user = json.path("user").path("name").asText();

            // If a specific subscription was requested, verify it matches
            if (credential.subscription() != null && !credential.subscription().isBlank()) {
                if (!subscriptionId.equals(credential.subscription()) &&
                    !subscriptionName.equalsIgnoreCase(credential.subscription())) {
                    return ValidationResult.failure(
                            "Subscription '" + credential.subscription() + "' not found. " +
                            "Current subscription: " + subscriptionName
                    );
                }
            }

            return ValidationResult.success(
                    "Subscription: " + subscriptionName,
                    "User: " + user + ", Tenant: " + tenantId
            );
        } catch (Exception e) {
            log.debug("Failed to parse Azure response: {}", output, e);
            return ValidationResult.success("Authenticated", output.trim());
        }
    }

    /**
     * Parses error response and provides helpful message.
     */
    private ValidationResult parseError(String output, Credential credential) {
        var message = output.trim();

        // Common error patterns
        if (message.contains("Please run 'az login'") || message.contains("AADSTS")) {
            return ValidationResult.failure(
                    "Not logged in to Azure. Run: az login"
            );
        }

        if (message.contains("subscription") && message.contains("not found")) {
            return ValidationResult.failure(
                    "Azure subscription '" + credential.subscription() + "' not found. " +
                    "List subscriptions with: az account list"
            );
        }

        if (message.contains("AuthorizationFailed") || message.contains("does not have authorization")) {
            return ValidationResult.failure(
                    "No permission to access Azure subscription. Check your role assignments."
            );
        }

        // Generic error
        return ValidationResult.failure("Azure authentication failed: " + message);
    }
}
