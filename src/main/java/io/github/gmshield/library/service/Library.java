package io.github.gmshield.library.service;

import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.LibraryConflict;
import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.library.data.SystemChange;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner's systems and campaigns (Story 1.11). Everything here acts for the owner of the work
 * ({@link OwnerContext}): the store sees that owner's rows only, so another owner's id reads exactly like an
 * unknown one. Names are encrypted for the owner, bound to the entity, the field {@code name} and the row.
 *
 * <p>Story 3.1: the GM lists, creates and edits systems. A system has a name (1 to {@value #NAME_MAX} code points
 * after trimming), and optionally an edition (at most {@value #EDITION_MAX}) and a description (at most
 * {@value #DESCRIPTION_MAX}), each encrypted under its own field ({@code edition}, {@code description}). Two systems
 * of one owner are the same when their name and edition match (Dalton, 2026-11-20): the system's
 * {@code name_fingerprint}, unique per owner, is the fingerprint of the name's {@link #uniqueForm} alone when there is
 * no edition (what a starter system has always stored), else of the name's and the edition's unique forms joined by
 * U+0000. A duplicate is {@code request.system_name_taken} (409). An edit names the version it was made on; an
 * earlier one is {@code request.stale_version} (409) and changes nothing. A system's campaigns are listed newest
 * first. Logs carry ids, counts and a system's version only.
 *
 * <p>Still to come in Epic 3: deleting a system (Story 3.1 part B), creating, renaming and switching campaigns (Story
 * 3.2). Until then the current campaign is the owner's oldest.
 */
@Service
public class Library {

    static final String SYSTEM = "system";
    static final String CAMPAIGN = "campaign";
    static final String FIELD_NAME = "name";
    static final String FIELD_EDITION = "edition";
    static final String FIELD_DESCRIPTION = "description";
    /** A system's limits, in code points after trimming (Story 3.1). */
    public static final int NAME_MAX = 120;
    public static final int EDITION_MAX = 60;
    public static final int DESCRIPTION_MAX = 2000;
    /** A list page's size: {@value #DEFAULT_LIMIT} unless asked, at most {@value #MAX_LIMIT}. */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    /** The starter library's names, decided by Dalton on 2026-11-16. */
    static final Map<Language, String[]> STARTER_NAMES = Map.of(
            Language.PT_BR, new String[] {"Sistema sem nome", "Primeira campanha"},
            Language.EN, new String[] {"Untitled system", "First campaign"});
    private static final Logger log = LoggerFactory.getLogger(Library.class);

    private final LibraryStore store;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Library(LibraryStore store, Vault vault, ObjectProvider<PlatformTransactionManager> transactions,
            ObjectProvider<Clock> clock) {
        this.store = store;
        this.vault = vault;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A campaign by its id and its name, decrypted. */
    public record Named(UUID id, String name) {
        @Override
        public String toString() {
            return "Named[id=" + id + "]";
        }
    }

    /** A system with its name, edition and description, decrypted (null when none), and its version. */
    public record GameSystem(UUID id, String name, String edition, String description, long version) {
        @Override
        public String toString() {
            return "GameSystem[id=" + id + ", version=" + version + "]";
        }
    }

    /** A campaign with its system. */
    public record Campaign(UUID id, String name, GameSystem system) {
        @Override
        public String toString() {
            return "Campaign[id=" + id + ", system=" + system + "]";
        }
    }

    /**
     * What the GM typed for a system (Story 3.1), as it arrived. On a creation a null edition or description is none;
     * on an edit a null field is unchanged, and a blank edition or description is removed.
     */
    public record SystemFields(String name, String edition, String description) {
        @Override
        public String toString() {
            return "SystemFields[]";
        }
    }

    /** One page of systems, and the cursor of the next one (null on the last). */
    public record SystemPage(List<GameSystem> items, UUID nextCursor) {
    }

    /** One page of a system's campaigns, and the cursor of the next one (null on the last). */
    public record CampaignPage(List<Named> items, UUID nextCursor) {
    }

    /**
     * A first sign-in's starter library: one system and one campaign of it, named in the account's language,
     * for the owner of the work. Runs only inside the caller's transaction, so that the account and its starter
     * library exist together or not at all.
     */
    public void createStarter(Language language) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("library.transaction_required");
        }
        UUID owner = OwnerContext.require();
        String[] names = STARTER_NAMES.get(language == null ? Language.DEFAULT : language);
        Instant now = now();
        UUID systemId = Ids.newId();
        store.insertSystem(new SystemRow(systemId, owner, vault.encryptText(binding(SYSTEM, FIELD_NAME, systemId, owner),
                names[0]), nameFingerprint(names[0], null), now));
        UUID campaignId = Ids.newId();
        store.insertCampaign(new CampaignRow(campaignId, owner, systemId,
                vault.encryptText(binding(CAMPAIGN, FIELD_NAME, campaignId, owner), names[1]), now));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("systemId", systemId).addKeyValue("campaignId", campaignId)
                .log("library.starter_created");
    }

    /** The owner's current campaign with its system: in this release, the owner's oldest campaign. */
    public Optional<Campaign> currentCampaign() {
        return reading(() -> store.firstCampaign().map(this::open));
    }

    /** The owner's campaign with this id, with its system; empty for an unknown id or another owner's. */
    public Optional<Campaign> campaign(UUID id) {
        return reading(() -> store.findCampaign(id).map(this::open));
    }

    /** The owner's system with this id; empty for an unknown id or another owner's. */
    public Optional<GameSystem> system(UUID id) {
        return reading(() -> store.findSystem(id).map(this::open));
    }

    /**
     * The owner's systems, newest first, after the system {@code cursor} when it is given, at most {@code limit} (1 to
     * {@value #MAX_LIMIT}). Newest first because encrypted names cannot be sorted by the database.
     */
    public SystemPage systems(UUID cursor, int limit) {
        checkLimit(limit);
        return reading(() -> {
            List<SystemRow> found = store.systems(cursor, limit + 1);
            List<SystemRow> page = found.size() > limit ? found.subList(0, limit) : found;
            UUID next = found.size() > limit ? page.getLast().id() : null;
            return new SystemPage(page.stream().map(this::open).toList(), next);
        });
    }

    /**
     * The campaigns of the owner's system {@code systemId}, newest first, after the campaign {@code cursor} when it is
     * given, at most {@code limit} (1 to {@value #MAX_LIMIT}); empty for an unknown system or another owner's.
     */
    public Optional<CampaignPage> campaignsOf(UUID systemId, UUID cursor, int limit) {
        checkLimit(limit);
        return reading(() -> store.findSystem(systemId).map(system -> {
            List<CampaignRow> found = store.campaignsOf(system.id(), cursor, limit + 1);
            List<CampaignRow> page = found.size() > limit ? found.subList(0, limit) : found;
            UUID next = found.size() > limit ? page.getLast().id() : null;
            return new CampaignPage(page.stream().map(row -> new Named(row.id(),
                    vault.decryptText(binding(CAMPAIGN, FIELD_NAME, row.id(), row.ownerId()), row.name()))).toList(), next);
        }));
    }

    /**
     * The ids of every campaign of the owner's system {@code systemId} (Story 3.1: the materials of a system are those
     * attached to it or to one of these); empty for an unknown system or another owner's.
     */
    public Optional<List<UUID>> campaignIdsOf(UUID systemId) {
        return reading(() -> store.findSystem(systemId).map(system -> store.campaignIdsOf(system.id())));
    }

    /**
     * A new system of the owner (Story 3.1), with the GM's name, edition and description. Throws
     * {@code request.system_name_invalid}, {@code request.system_edition_invalid} or
     * {@code request.system_description_invalid} (400) for a field outside its limits, and
     * {@code request.system_name_taken} (409) when another system of the owner has the same name and edition;
     * nothing is stored then.
     */
    public GameSystem createSystem(SystemFields fields) {
        String name = name(fields.name());
        String edition = optional(fields.edition(), EDITION_MAX, LibraryProblems.SYSTEM_EDITION_INVALID);
        String description = optional(fields.description(), DESCRIPTION_MAX, LibraryProblems.SYSTEM_DESCRIPTION_INVALID);
        UUID owner = OwnerContext.require();
        UUID id = Ids.newId();
        byte[] fingerprint = nameFingerprint(name, edition);
        GameSystem created = writing(() -> {
            if (store.systemNameTaken(fingerprint, id)) {
                throw new ApiException(LibraryProblems.SYSTEM_NAME_TAKEN);
            }
            SystemRow row = new SystemRow(id, owner, vault.encryptText(binding(SYSTEM, FIELD_NAME, id, owner), name),
                    fingerprint, encrypt(FIELD_EDITION, id, owner, edition), encrypt(FIELD_DESCRIPTION, id, owner, description),
                    0, now());
            conflicts(() -> {
                store.insertSystem(row);
                return row;
            });
            return new GameSystem(id, name, edition, description, row.version());
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("systemId", id).log("library.system_created");
        return created;
    }

    /**
     * Changes the owner's system {@code id} (Story 3.1): each field of {@code fields} that is not null replaces the
     * stored one (a blank edition or description removes it), on the version {@code version}. Answers the system as
     * stored, its version raised by one; empty for an unknown system or another owner's. Throws the field problems
     * (400) as a creation does, {@code request.stale_version} (409) when the system is no longer at {@code version},
     * and {@code request.system_name_taken} (409) when another system of the owner has the new name and edition;
     * nothing changes then.
     */
    public Optional<GameSystem> changeSystem(UUID id, long version, SystemFields fields) {
        String newName = fields.name() == null ? null : name(fields.name());
        String newEdition = fields.edition() == null ? null
                : optional(fields.edition(), EDITION_MAX, LibraryProblems.SYSTEM_EDITION_INVALID);
        String newDescription = fields.description() == null ? null
                : optional(fields.description(), DESCRIPTION_MAX, LibraryProblems.SYSTEM_DESCRIPTION_INVALID);
        Optional<GameSystem> changed = writing(() -> store.findSystem(id).map(row -> {
            if (row.version() != version) {
                throw new ApiException(LibraryProblems.STALE_VERSION);
            }
            GameSystem current = open(row);
            String name = newName == null ? current.name() : newName;
            String edition = fields.edition() == null ? current.edition() : newEdition;
            String description = fields.description() == null ? current.description() : newDescription;
            byte[] fingerprint = nameFingerprint(name, edition);
            if (store.systemNameTaken(fingerprint, id)) {
                throw new ApiException(LibraryProblems.SYSTEM_NAME_TAKEN);
            }
            UUID owner = row.ownerId();
            SystemChange change = new SystemChange(id, version, vault.encryptText(binding(SYSTEM, FIELD_NAME, id, owner), name),
                    fingerprint, encrypt(FIELD_EDITION, id, owner, edition), encrypt(FIELD_DESCRIPTION, id, owner, description),
                    now());
            SystemRow stored = conflicts(() -> store.updateSystem(change))
                    .orElseThrow(() -> new IllegalStateException("library.system_vanished"));
            return new GameSystem(id, name, edition, description, stored.version());
        }));
        changed.ifPresent(system -> log.atInfo().addKeyValue("ownerId", OwnerContext.require()).addKeyValue("systemId", id)
                .addKeyValue("version", system.version()).log("library.system_changed"));
        return changed;
    }

    /** The form whose fingerprint is unique per owner: trimmed, NFC, lower case in the root locale. */
    public static String uniqueForm(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /**
     * A system's name fingerprint (Story 3.1): of the name's unique form alone without an edition (the form every
     * starter system stores), else of the name's and the edition's unique forms joined by U+0000.
     */
    byte[] nameFingerprint(String name, String edition) {
        String form = edition == null || edition.isBlank() ? uniqueForm(name) : uniqueForm(name) + "\u0000" + uniqueForm(edition);
        return vault.fingerprint(SYSTEM, FIELD_NAME, form);
    }

    /** A system's name, trimmed, or {@code request.system_name_invalid}. */
    private static String name(String typed) {
        String name = typed == null ? "" : typed.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > NAME_MAX) {
            throw new ApiException(LibraryProblems.SYSTEM_NAME_INVALID);
        }
        return name;
    }

    /** An optional field, trimmed; null when absent or blank; {@code invalid} past {@code max} code points. */
    private static String optional(String typed, int max, ApiProblem invalid) {
        String value = typed == null ? "" : typed.strip();
        if (value.codePointCount(0, value.length()) > max) {
            throw new ApiException(invalid);
        }
        return value.isEmpty() ? null : value;
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("library.limit_invalid");
        }
    }

    private byte[] encrypt(String field, UUID id, UUID owner, String value) {
        return value == null ? null : vault.encryptText(binding(SYSTEM, field, id, owner), value);
    }

    private String decrypt(String field, SystemRow row, byte[] stored) {
        return stored == null ? null : vault.decryptText(binding(SYSTEM, field, row.id(), row.ownerId()), stored);
    }

    private Campaign open(CampaignRow row) {
        // The database keeps a campaign's system within its owner: a missing one is a broken row, not a 404.
        SystemRow system = store.findSystem(row.systemId())
                .orElseThrow(() -> new IllegalStateException("library.campaign_without_system"));
        return new Campaign(row.id(), vault.decryptText(binding(CAMPAIGN, FIELD_NAME, row.id(), row.ownerId()), row.name()),
                open(system));
    }

    private GameSystem open(SystemRow row) {
        return new GameSystem(row.id(), vault.decryptText(binding(SYSTEM, FIELD_NAME, row.id(), row.ownerId()), row.name()),
                decrypt(FIELD_EDITION, row, row.edition()), decrypt(FIELD_DESCRIPTION, row, row.description()), row.version());
    }

    /** A store write whose conflict becomes its problem: a taken name, or a version that moved meanwhile. */
    private static <T> T conflicts(Supplier<T> write) {
        try {
            return write.get();
        } catch (LibraryConflict conflict) {
            throw new ApiException(conflict.reason() == LibraryConflict.Reason.NAME_TAKEN
                    ? LibraryProblems.SYSTEM_NAME_TAKEN : LibraryProblems.STALE_VERSION);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** One read-only transaction for the reads of one answer, so they come from one session. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    /** One transaction for a write and its checks: a problem thrown inside rolls everything back. */
    private <T> T writing(Supplier<T> write) {
        OwnerContext.require();
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    private static Binding binding(String entity, String field, UUID rowId, UUID owner) {
        return new Binding(entity, field, rowId, owner);
    }
}
