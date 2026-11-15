package io.github.gmshield.vault.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.StartupSettings;
import io.github.gmshield.vault.data.ProbeRow;
import io.github.gmshield.vault.data.ProbeStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Test mode only: stores the NFR-1 gate's known text through the vault, as one {@code vault_probe}
 * row and one file piece, and reads both back. Later stories add their real routes to the seed.
 */
@Service
@Profile(StartupSettings.TEST_MODE_PROFILE)
public class Nfr1Probe {

    static final String ENTITY = "vault_probe";
    static final String FIELD = "text";
    public static final int MAX_TEXT_LENGTH = 1024;

    private final Vault vault;
    private final FileStore files;
    private final ProbeStore store;
    private final DataKeys dataKeys;

    Nfr1Probe(Vault vault, FileStore files, ProbeStore store, DataKeys dataKeys) {
        this.vault = vault;
        this.files = files;
        this.store = store;
        this.dataKeys = dataKeys;
    }

    public record ReadBack(String text, String fileText) {
    }

    /** Stores the text, reads it back, and gives the probe's id. */
    public UUID seed(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw new ApiException(ApiProblem.forStatus(400));
        }
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        try {
            UUID owner = Ids.newId();
            UUID id = Ids.newId();
            UUID fileId = Ids.newId();
            store.insert(new ProbeRow(id, owner, vault.encrypt(binding(id, owner), plain),
                    vault.fingerprint(ENTITY, FIELD, plain), fileId));
            files.writePiece(owner, fileId, 0, plain);
            ReadBack back = readBack(id);
            if (!text.equals(back.text()) || !text.equals(back.fileText())) {
                throw new IllegalStateException("the probe did not read back what it stored");
            }
            return id;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Reads with a cold key cache for the probe's owner, so the wrapped data key comes from the store. */
    public ReadBack readBack(UUID id) {
        ProbeRow row = store.find(id).orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
        dataKeys.forget(row.ownerId());
        String text = vault.decryptText(binding(row.id(), row.ownerId()), row.text());
        String fileText = new String(files.readPiece(row.ownerId(), row.fileId(), 0), StandardCharsets.UTF_8);
        return new ReadBack(text, fileText);
    }

    private static Binding binding(UUID id, UUID owner) {
        return new Binding(ENTITY, FIELD, id, owner);
    }
}
