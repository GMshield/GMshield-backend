package io.github.gmshield.vault.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.data.WrappedDataKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Data keys: one per owner, created on first use, kept unwrapped in memory only. */
class DataKeysTest {

    private final InMemoryDataKeyStore store = new InMemoryDataKeyStore();
    private final VaultKeys keys = VaultFixtures.keys();

    @Test
    void createdOnFirstUseThenServedFromMemory() {
        DataKeys dataKeys = new DataKeys(store, keys);
        UUID owner = Ids.newId();

        DataKeys.Current first = dataKeys.current(owner);
        int reads = store.reads.get();
        DataKeys.Current again = dataKeys.current(owner);

        assertThat(first.version()).isEqualTo(1);
        assertThat(again.key()).isSameAs(first.key());
        assertThat(store.reads.get()).isEqualTo(reads);
        assertThat(store.rows).hasSize(1);
        WrappedDataKey stored = store.rows.values().iterator().next();
        assertThat(stored.wrapped()).hasSize(1 + 12 + 32 + 16).startsWith(1);
        assertThat(java.util.HexFormat.of().formatHex(stored.wrapped()))
                .doesNotContain(java.util.HexFormat.of().formatHex(first.key().getEncoded()));
    }

    @Test
    void whenAnotherRequestStoredAKeyFirstThatKeyIsUsed() {
        UUID owner = Ids.newId();
        DataKeys winner = new DataKeys(store, keys);
        DataKeys.Current stored = winner.current(owner);
        InMemoryDataKeyStore racing = new InMemoryDataKeyStore() {
            private boolean first = true;

            @Override
            public Optional<WrappedDataKey> latest(UUID ownerId) {
                if (first) {
                    first = false;
                    return Optional.empty();        // looked before the winner stored its key
                }
                return store.latest(ownerId);
            }

            @Override
            public void insertIfAbsent(WrappedDataKey key) {
                store.insertIfAbsent(key);           // the winner's row stays
            }
        };

        DataKeys.Current loser = new DataKeys(racing, keys).current(owner);

        assertThat(loser.key().getEncoded()).isEqualTo(stored.key().getEncoded());
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void eachOwnerHasItsOwnKey() {
        DataKeys dataKeys = new DataKeys(store, keys);

        assertThat(dataKeys.current(Ids.newId()).key().getEncoded())
                .isNotEqualTo(dataKeys.current(Ids.newId()).key().getEncoded());
    }
}
