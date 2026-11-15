package io.github.gmshield.vault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.data.WrappedDataKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The vault's values, fingerprints and token hashes (I/O matrix of Story 1.7). */
class VaultTest {

    private static final String SECRET_TEXT = "The lich hides in the third crypt";

    private final InMemoryDataKeyStore store = new InMemoryDataKeyStore();
    private final VaultKeys keys = VaultFixtures.keys();
    private final Vault vault = VaultFixtures.vault(store, keys);
    private final UUID owner = Ids.newId();
    private final UUID row = Ids.newId();
    private final Binding binding = new Binding("note", "body", row, owner);

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLog() {
        logged.start();
        rootLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        rootLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void roundTripGivesTheOriginalValue() {
        byte[] stored = vault.encryptText(binding, SECRET_TEXT);

        assertThat(vault.decryptText(binding, stored)).isEqualTo(SECRET_TEXT);
        assertThat(vault.decrypt(binding, vault.encrypt(binding, new byte[0]))).isEmpty();
    }

    @Test
    void storedValueStartsWithTheFormatAndTheDataKeyVersionAndHidesTheText() {
        byte[] stored = vault.encryptText(binding, SECRET_TEXT);

        assertThat(stored[0]).isEqualTo((byte) 1);
        assertThat(Arrays.copyOfRange(stored, 1, 5)).containsExactly(0, 0, 0, 1);
        assertThat(stored).hasSize(5 + 12 + SECRET_TEXT.length() + 16);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(SECRET_TEXT);
        assertThat(vault.encryptText(binding, SECRET_TEXT)).isNotEqualTo(stored);   // a new nonce each time
    }

    @Test
    void movedValueGivesNoValue() {
        byte[] stored = vault.encryptText(binding, SECRET_TEXT);
        UUID otherOwner = Ids.newId();
        vault.encryptText(new Binding("note", "body", row, otherOwner), "the other owner has a data key too");

        for (Binding moved : List.of(new Binding("note", "body", Ids.newId(), owner),
                new Binding("note", "title", row, owner), new Binding("campaign", "body", row, owner),
                new Binding("note", "body", row, otherOwner))) {
            assertThatThrownBy(() -> vault.decrypt(moved, stored)).isInstanceOf(IntegrityException.class);
        }
    }

    @Test
    void lengthPrefixesKeepTheBindingUnambiguous() {
        byte[] stored = vault.encryptText(new Binding("ab", "c", row, owner), SECRET_TEXT);

        assertThatThrownBy(() -> vault.decrypt(new Binding("a", "bc", row, owner), stored))
                .isInstanceOf(IntegrityException.class);
    }

    @Test
    void anyChangedByteTruncationOrVersionGivesNoValue() {
        byte[] stored = vault.encryptText(binding, SECRET_TEXT);

        for (int i = 0; i < stored.length; i++) {
            byte[] changed = stored.clone();
            changed[i] ^= 0x01;
            assertThatThrownBy(() -> vault.decrypt(binding, changed)).as("byte %d", i).isInstanceOf(IntegrityException.class);
        }
        for (int length = 0; length < stored.length; length++) {
            byte[] truncated = Arrays.copyOf(stored, length);
            assertThatThrownBy(() -> vault.decrypt(binding, truncated)).isInstanceOf(IntegrityException.class);
        }
        byte[] unknownFormat = stored.clone();
        unknownFormat[0] = 2;
        byte[] unknownKeyVersion = stored.clone();
        unknownKeyVersion[4] = 2;
        byte[] zeroKeyVersion = stored.clone();
        zeroKeyVersion[4] = 0;
        for (byte[] value : List.of(unknownFormat, unknownKeyVersion, zeroKeyVersion)) {
            assertThatThrownBy(() -> vault.decrypt(binding, value)).isInstanceOf(IntegrityException.class);
        }
        assertThatThrownBy(() -> vault.decrypt(binding, null)).isInstanceOf(IntegrityException.class);
    }

    @Test
    void dataKeyWrappedUnderAnotherMasterKeyGivesNoValue() {
        byte[] stored = vault.encryptText(binding, SECRET_TEXT);
        Vault otherMaster = VaultFixtures.vault(store, VaultFixtures.keys());

        assertThatThrownBy(() -> otherMaster.decrypt(binding, stored)).isInstanceOf(IntegrityException.class);
        assertThatThrownBy(() -> otherMaster.encryptText(binding, SECRET_TEXT)).isInstanceOf(IntegrityException.class);
    }

    @Test
    void sameValueForTwoOwnersGivesDifferentCiphertextsAndTheSameFingerprint() {
        Binding second = new Binding("account", "email", Ids.newId(), Ids.newId());
        Binding first = new Binding("account", "email", Ids.newId(), owner);

        assertThat(vault.encryptText(first, "gm@example.org")).isNotEqualTo(vault.encryptText(second, "gm@example.org"));
        assertThat(vault.fingerprint("account", "email", "gm@example.org"))
                .isEqualTo(vault.fingerprint("account", "email", "gm@example.org"))
                .hasSize(32)
                .isNotEqualTo(vault.fingerprint("account", "username", "gm@example.org"))
                .isNotEqualTo(vault.fingerprint("note", "email", "gm@example.org"));
        assertThat(store.rows).hasSize(2);   // one data key per owner
    }

    @Test
    void fingerprintsDependOnTheIndexKey() {
        Vault otherIndex = VaultFixtures.vault(store, VaultFixtures.keys());

        assertThat(otherIndex.fingerprint("account", "email", "gm@example.org"))
                .isNotEqualTo(vault.fingerprint("account", "email", "gm@example.org"));
    }

    @Test
    void tokenHashIsStableAndDiffersFromItsFingerprint() {
        String token = "f3b2c1d0e9a8";

        assertThat(vault.tokenHash("session", token)).isEqualTo(vault.tokenHash("session", token))
                .isNotEqualTo(vault.fingerprint("session", "token", token))
                .isNotEqualTo(vault.tokenHash("grant", token));
    }

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        HexFormat hex = HexFormat.of();
        byte[] okm = VaultCrypto.hkdf(hex.parseHex("0b".repeat(22)), hex.parseHex("000102030405060708090a0b0c"),
                hex.parseHex("f0f1f2f3f4f5f6f7f8f9"));

        assertThat(hex.formatHex(okm)).isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf");
    }

    @Test
    void wrappedDataKeyIsBoundToItsOwnerAndVersion() {
        vault.encryptText(binding, SECRET_TEXT);
        WrappedDataKey wrapped = store.latest(owner).orElseThrow();
        UUID thief = Ids.newId();
        store.put(new WrappedDataKey(thief, 1, wrapped.wrapped()));
        store.put(new WrappedDataKey(owner, 2, wrapped.wrapped()));

        Vault fresh = VaultFixtures.vault(store, keys);    // nothing cached
        assertThatThrownBy(() -> fresh.encryptText(new Binding("note", "body", row, thief), "x"))
                .isInstanceOf(IntegrityException.class);
        assertThatThrownBy(() -> fresh.encryptText(binding, "x")).isInstanceOf(IntegrityException.class);
        assertThat(new String(wrapped.wrapped(), StandardCharsets.ISO_8859_1)).doesNotContain("note");
    }

    @Test
    void integrityFailureLogsTheIdsOnlyAndItsMessageIsTheCode() {
        byte[] stored = vault.encryptText(binding, SECRET_TEXT);
        Binding moved = new Binding("note", "body", Ids.newId(), owner);

        assertThatThrownBy(() -> vault.decrypt(moved, stored))
                .isInstanceOf(IntegrityException.class).hasMessage("integrity.failed").hasNoCause()
                .satisfies(e -> assertThat(((IntegrityException) e).problem().params()).isEmpty());

        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        String lines = String.join("", logged.list.stream().map(formatter::format).toList());
        assertThat(lines).contains("vault.integrity_failed", moved.rowId().toString(), owner.toString(), "\"entity\":\"note\"")
                .doesNotContain(SECRET_TEXT)
                .doesNotContain(Base64.getEncoder().encodeToString(stored))
                .doesNotContain(HexFormat.of().formatHex(stored));
    }

    @Test
    void keysNeverShowInTextAndTheIndexKeyIsHandedOutAsACopy() {
        assertThat(keys.toString()).isEqualTo("VaultKeys[hidden]");
        byte[] copy = keys.index();
        Arrays.fill(copy, (byte) 0);
        assertThat(keys.index()).isNotEqualTo(copy);
    }

    @Test
    void forgettingAnOwnersKeysReloadsThemFromTheStore() {
        DataKeys dataKeys = new DataKeys(store, keys);
        Vault cold = new Vault(dataKeys, keys);
        byte[] stored = cold.encryptText(binding, SECRET_TEXT);
        int finds = store.finds.get();

        dataKeys.forget(owner);

        assertThat(cold.decryptText(binding, stored)).isEqualTo(SECRET_TEXT);
        assertThat(store.finds.get()).isEqualTo(finds + 1);
    }

    @Test
    void bindingNeedsEveryPart() {
        assertThatThrownBy(() -> new Binding(" ", "f", row, owner)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Binding("e", null, row, owner)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Binding("e", "f", null, owner)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Binding("e", "f", row, null)).isInstanceOf(NullPointerException.class);
    }
}
