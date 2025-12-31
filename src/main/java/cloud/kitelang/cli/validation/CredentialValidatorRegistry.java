package cloud.kitelang.cli.validation;

import cloud.kitelang.engine.kitefile.Dependencies.Credential;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of credential validators for different cloud providers.
 */
public class CredentialValidatorRegistry {

    private static final Map<String, CredentialValidator> VALIDATORS = new HashMap<>();

    static {
        register(new AwsCredentialValidator());
        register(new GcpCredentialValidator());
        register(new AzureCredentialValidator());
    }

    private static void register(CredentialValidator validator) {
        VALIDATORS.put(validator.getProviderType().toLowerCase(), validator);
    }

    /**
     * Gets the validator for a provider type.
     *
     * @param providerType the provider type (aws, gcp, azure)
     * @return the validator, or null if not found
     */
    public static CredentialValidator getValidator(String providerType) {
        return VALIDATORS.get(providerType.toLowerCase());
    }

    /**
     * Validates a credential using the appropriate validator.
     *
     * @param credential the credential to validate
     * @return validation result
     */
    public static CredentialValidator.ValidationResult validate(Credential credential) {
        var validator = getValidator(credential.type());
        if (validator == null) {
            return CredentialValidator.ValidationResult.failure(
                    "Unknown provider type: " + credential.type()
            );
        }
        return validator.validate(credential);
    }

    /**
     * Checks if a validator exists for the given provider type.
     */
    public static boolean hasValidator(String providerType) {
        return VALIDATORS.containsKey(providerType.toLowerCase());
    }

    /**
     * Returns all registered provider types.
     */
    public static Iterable<String> getProviderTypes() {
        return VALIDATORS.keySet();
    }
}
