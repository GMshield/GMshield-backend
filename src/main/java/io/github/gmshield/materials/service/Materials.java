package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.NewestVersion;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner's materials as the interface shows them (Story 1.14): each with its name, decrypted, and the state of its
 * newest version, which is the material's state; while that version is {@code uploading}, the upload's progress too.
 * Everything acts for the owner of the work ({@link OwnerContext}): another owner's material reads like an unknown one.
 * Nothing here logs a name.
 */
@Service
public class Materials {

    /** Every state a version may be in, as the tables write them. */
    public static final Set<String> STATES = Set.of("uploading", "waiting", "processing", "needs_review", "ready",
            "superseded", "failed", "discarded");
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final MaterialStore materials;
    private final UploadStorage uploads;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    Materials(MaterialStore materials, UploadStorage uploads, Vault vault,
              ObjectProvider<PlatformTransactionManager> transactions) {
        this.materials = materials;
        this.uploads = uploads;
        this.vault = vault;
        this.transactions = transactions;
    }

    /** An unfinished upload: its id, the bytes verified and stored, its length and when it expires. */
    public record Upload(UUID id, long offset, long length, Instant expiresAt) {
    }

    /** A material: {@code kind} may be null, and {@code upload} is there only while the state is {@code uploading}. */
    public record Material(UUID id, String name, String state, String kind, Upload upload) {
        @Override
        public String toString() {
            return "Material[id=" + id + ", state=" + state + "]";
        }
    }

    /** One page of materials, and the cursor of the next one (null when this is the last). */
    public record Page(List<Material> items, UUID nextCursor) {
    }

    /**
     * The owner's materials whose newest version is in one of {@code states}, newest first, after the material
     * {@code cursor} when it is given, at most {@code limit} (1 to {@value #MAX_LIMIT}).
     */
    public Page list(Collection<String> states, UUID cursor, int limit) {
        if (states.isEmpty() || !STATES.containsAll(states) || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("materials.list_invalid");
        }
        return reading(() -> {
            List<NewestVersion> found = materials.newestIn(Set.copyOf(states), cursor, limit + 1);
            List<NewestVersion> page = found.size() > limit ? found.subList(0, limit) : found;
            UUID next = found.size() > limit ? page.getLast().material().id() : null;
            return new Page(views(page), next);
        });
    }

    /** The owner's material with this id; empty for an unknown id or another owner's. */
    public Optional<Material> find(UUID id) {
        return reading(() -> materials.newest(id).map(found -> views(List.of(found)).getFirst()));
    }

    private List<Material> views(List<NewestVersion> rows) {
        UUID owner = OwnerContext.require();
        List<UUID> uploading = rows.stream().map(NewestVersion::version)
                .filter(version -> Uploads.UPLOADING.equals(version.state())).map(version -> version.id()).toList();
        Map<UUID, UploadRow> open = uploads.joined().openForVersions(owner, uploading).stream()
                .collect(Collectors.toMap(UploadRow::versionId, Function.identity(), (first, second) -> first));
        return rows.stream().map(row -> {
            UploadRow upload = Uploads.UPLOADING.equals(row.version().state()) ? open.get(row.version().id()) : null;
            return new Material(row.material().id(),
                    vault.decryptText(new Binding(Uploads.MATERIAL, Uploads.FIELD_NAME, row.material().id(),
                            row.material().ownerId()), row.material().name()),
                    row.version().state(), row.material().kind(),
                    upload == null ? null : new Upload(upload.id(), upload.offset(), upload.length(), upload.expiresAt()));
        }).toList();
    }

    /** One read-only transaction for the reads of one answer. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
