package cloud.kitelang.cli.validation;

import cloud.kitelang.engine.kitefile.Dependencies.Credential;

/**
 * Validates cloud provider credentials.
 * Each implementation validates credentials for a specific cloud provider
 * by calling the provider's CLI tool (aws, gcloud, az).
 */
public interface CredentialValidator {

    /**
     * Validates the given credential.
     *
     * @param credential the credential to validate
     * @return validation result with success status and details
     */
    ValidationResult validate(Credential credential);

    /**
     * Returns the provider type this validator handles (aws, gcp, azure).
     */
    String getProviderType();

    /**
     * Checks if the required CLI tool is installed.
     */
    boolean isCliAvailable();

    /**
     * Result of credential validation.
     */
    record ValidationResult(
            boolean success,
            String message,
            String identity,  // e.g., AWS account ID, GCP project, Azure subscription
            String details    // Additional info (e.g., ARN, email)
    ) {
        public static ValidationResult success(String identity, String details) {
            return new ValidationResult(true, "Credentials valid", identity, details);
        }

        public static ValidationResult failure(String message) {
            return new ValidationResult(false, message, null, null);
        }

        public static ValidationResult cliNotFound(String cliName) {
            return new ValidationResult(false,
                    cliName + " CLI not found. Install it to validate credentials.",
                    null, null);
        }
    }
}
