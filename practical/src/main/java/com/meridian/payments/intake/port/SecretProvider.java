package com.meridian.payments.intake.port;

import java.util.List;

/**
 * Webhook secrets by name (NFR6). Production: Azure Key Vault with a managed identity.
 * Here: {@code stub.ConfiguredSecretProvider}.
 */
public interface SecretProvider {

    /**
     * All active versions of the secret. During a rotation there are two, and a signature made
     * with either is accepted. Never logged.
     */
    List<String> activeSecrets(String secretName);
}
