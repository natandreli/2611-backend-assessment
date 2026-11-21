package com.meridian.payments.intake.stub;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.meridian.payments.intake.port.SecretProvider;

/**
 * Stub of Key Vault. Secrets come from configuration ({@code stub.secrets.<name>}), which in
 * this repository is only set by the tests, with the providers' fictional test secrets.
 * The production implementation reads Key Vault with a managed identity; secrets are never
 * written in code or configuration files (NFR6).
 */
@Component
public class ConfiguredSecretProvider implements SecretProvider {

    private final StubSecrets stubSecrets;

    public ConfiguredSecretProvider(StubSecrets stubSecrets) {
        this.stubSecrets = stubSecrets;
    }

    @Override
    public List<String> activeSecrets(String secretName) {
        Map<String, List<String>> secrets = stubSecrets.secrets();
        List<String> values = secrets == null ? null : secrets.get(secretName);
        return values == null ? List.of() : List.copyOf(values);
    }

    @ConfigurationProperties("stub")
    public record StubSecrets(Map<String, List<String>> secrets) {
    }
}
