package io.github.gmshield.vault.service;

import io.github.gmshield.shared.service.StartupSettings;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Where the vault's keys and file folder come from. Outside test mode the keys are the settings
 * {@code GMSHIELD_VAULT_MASTER_KEY} and {@code GMSHIELD_VAULT_INDEX_KEY}, already checked by
 * {@link StartupSettings}; in test mode they are random for each start and live only in memory.
 */
@Configuration
class VaultConfiguration {

    @Bean
    @Profile("!" + StartupSettings.TEST_MODE_PROFILE)
    VaultKeys configuredVaultKeys(@Value("${gmshield.vault.master-key}") String master,
                                  @Value("${gmshield.vault.index-key}") String index) {
        byte[] masterKey = StartupSettings.vaultKey(master);
        byte[] indexKey = StartupSettings.vaultKey(index);
        if (masterKey == null || indexKey == null) {
            // Names only, never a value (StartupSettings refuses this before Spring starts).
            throw new IllegalStateException(StartupSettings.REASON_INVALID + ": " + StartupSettings.VAULT_MASTER_KEY
                    + " or " + StartupSettings.VAULT_INDEX_KEY);
        }
        return new VaultKeys(masterKey, indexKey);
    }

    /**
     * Random keys, only when the pipeline really started test mode: a {@code test-mode} profile
     * switched on by other means would otherwise encrypt with keys that vanish at the next start.
     */
    @Bean
    @Profile(StartupSettings.TEST_MODE_PROFILE)
    VaultKeys randomVaultKeys(Environment environment) {
        if (!StartupSettings.TEST_MODE.equals(environment.getProperty(StartupSettings.MODE))) {
            throw new IllegalStateException("the test-mode profile needs " + StartupSettings.MODE + "=test");
        }
        return VaultKeys.random();
    }

    @Bean
    FileStore fileStore(Vault vault, @Value("${gmshield.files.dir}") Path directory) {
        return new FileStore(vault, directory);
    }
}
