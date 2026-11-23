package io.github.gmshield.library.service;

import io.github.gmshield.library.data.CampaignChange;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>Story 3.1 part B: a system being deleted ({@link SystemDeletion}) and its campaigns are "not found" to every read
 * and write here, from the commit of the GM's delete: the system list, {@link #system}, {@link #changeSystem},
 * {@link #campaigns}, {@link #campaignIdsOf}, {@link #campaign} and {@link #currentCampaign}. When the deleted system
 * held the owner's only campaign, there is no current campaign.
 *
 * <p>Story 3.2: the GM lists, creates, renames, archives, restores and chooses campaigns. A campaign's name is 1 to
 * {@value #NAME_MAX} code points after trimming ({@code request.campaign_name_invalid}, 400), encrypted under the field
 * {@code name}; its system is set at creation and never changes. Two campaigns of one system cannot share a name (Dalton,
 * 2026-11-20): the campaign's {@code name_fingerprint}, unique per owner and system, is the fingerprint of the name's
 * {@link #uniqueForm}; a duplicate is {@code request.campaign_name_taken} (409). Rows from before V23 have no fingerprint
 * until {@link #fillCampaignFingerprints} runs for their owner ({@link CampaignFingerprints}, at start), so a new or
 * renamed campaign is also compared with the system's campaigns that have none yet. A new campaign starts with the
 * starting scope (nothing is stored for it) and becomes the current one. An archived campaign keeps every row, leaves the
 * lists and the current position, still opens by id ({@link #campaign}) and can be restored; a rename, an archive or a
 * restore names the version it was made on ({@code request.stale_version}, 409, when it is stale). The current campaign
 * is the ready, not archived campaign the GM chose last ({@link #chooseCampaign}), else the oldest ready, not archived
 * one; none when there is none. Choosing never raises a campaign's version, and archiving forgets that it was chosen.
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
    /** The furthest position a list page may start at (a page's {@code offset}): far past any library. */
    public static final int MAX_OFFSET = 100_000;
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

    /** A system with its name, edition and description, decrypted (null when none), and its version. */
    public record GameSystem(UUID id, String name, String edition, String description, long version) {
        @Override
        public String toString() {
            return "GameSystem[id=" + id + ", version=" + version + "]";
        }
    }

    /** A campaign with its system, whether it is archived, and its version (Story 3.2). */
    public record Campaign(UUID id, String name, GameSystem system, boolean archived, long version) {
        @Override
        public String toString() {
            return "Campaign[id=" + id + ", system=" + system + ", archived=" + archived + ", version=" + version + "]";
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

    /** One page of systems, the cursor of the next one (null on the last), and how many systems the list has in all. */
    public record SystemPage(List<GameSystem> items, UUID nextCursor, long total) {
    }

    /**
     * One page of campaigns, the cursor of the next one (null on the last), and how many campaigns the list has in all
     * (for the same system and the same {@code archived}).
     */
    public record CampaignPage(List<Campaign> items, UUID nextCursor, long total) {
    }

    /** Campaigns (each with its system) and systems by id (Story 3.3), as {@link #places} finds them. */
    public record Places(Map<UUID, Campaign> campaigns, Map<UUID, GameSystem> systems) {
        public static final Places NONE = new Places(Map.of(), Map.of());
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
                vault.encryptText(binding(CAMPAIGN, FIELD_NAME, campaignId, owner), names[1]),
                campaignFingerprint(names[1]), null, null, 0, now));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("systemId", systemId).addKeyValue("campaignId", campaignId)
                .log("library.starter_created");
    }

    /**
     * The owner's current campaign with its system (Story 3.2): of the ready, not archived campaigns, the one chosen
     * last, else the oldest; empty when there is none.
     */
    public Optional<Campaign> currentCampaign() {
        return reading(() -> store.currentCampaign().map(this::open));
    }

    /**
     * The owner's campaign with this id, with its system, archived or not; empty for an unknown id, another owner's or a
     * campaign of a system being deleted.
     */
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
        return systems(cursor, 0, limit);
    }

    /**
     * As {@link #systems(UUID, int)}, from a position instead of a cursor when {@code offset} is above zero: the
     * systems after the {@code offset} newest ones (0 to {@value #MAX_OFFSET}), so a client can show numbered pages. A
     * cursor and an offset together are refused. The page also says how many systems the list has in all.
     */
    public SystemPage systems(UUID cursor, int offset, int limit) {
        checkLimit(limit);
        checkOffset(cursor, offset);
        return reading(() -> {
            List<SystemRow> found = offset > 0 ? store.systemsFrom(offset, limit + 1) : store.systems(cursor, limit + 1);
            List<SystemRow> page = found.size() > limit ? found.subList(0, limit) : found;
            UUID next = found.size() > limit ? page.getLast().id() : null;
            return new SystemPage(page.stream().map(this::open).toList(), next, store.systemCount());
        });
    }

    /**
     * The owner's campaigns (Story 3.2), of the system {@code systemId} only when it is given, the archived ones when
     * {@code archived} is true, else the others; newest first, after the campaign {@code cursor} when it is given, at
     * most {@code limit} (1 to {@value #MAX_LIMIT}); empty for an unknown system or another owner's.
     */
    public Optional<CampaignPage> campaigns(UUID systemId, boolean archived, UUID cursor, int limit) {
        return campaigns(systemId, archived, cursor, 0, limit);
    }

    /**
     * As {@link #campaigns(UUID, boolean, UUID, int)}, from a position instead of a cursor when {@code offset} is above
     * zero (0 to {@value #MAX_OFFSET}); a cursor and an offset together are refused. The page also says how many
     * campaigns the list has in all.
     */
    public Optional<CampaignPage> campaigns(UUID systemId, boolean archived, UUID cursor, int offset, int limit) {
        checkLimit(limit);
        checkOffset(cursor, offset);
        return reading(() -> {
            if (systemId != null && store.findSystem(systemId).isEmpty()) {
                return Optional.<CampaignPage>empty();
            }
            List<CampaignRow> found = offset > 0 ? store.campaignsFrom(systemId, archived, offset, limit + 1)
                    : store.campaigns(systemId, archived, cursor, limit + 1);
            List<CampaignRow> page = found.size() > limit ? found.subList(0, limit) : found;
            UUID next = found.size() > limit ? page.getLast().id() : null;
            Map<UUID, GameSystem> systems = new HashMap<>();
            return Optional.of(new CampaignPage(page.stream().map(row -> open(row, systems.computeIfAbsent(row.systemId(),
                    this::systemOf))).toList(), next, store.campaignCount(systemId, archived)));
        });
    }

    /**
     * The owner's campaigns {@code campaignIds} and systems {@code systemIds} (Story 3.3: where a page of materials
     * belongs, or the targets of a change), from one read of the campaigns and one of the systems, theirs included:
     * the ready ones only, a campaign archived or not with its system, each by id. An unknown id, another owner's, a
     * deleting one, or a campaign of a deleting system is left out.
     */
    public Places places(Collection<UUID> campaignIds, Collection<UUID> systemIds) {
        if (campaignIds.isEmpty() && systemIds.isEmpty()) {
            OwnerContext.require();
            return Places.NONE;
        }
        return reading(() -> {
            List<CampaignRow> campaignRows = campaignIds.isEmpty() ? List.of()
                    : store.findCampaigns(Set.copyOf(campaignIds));
            Set<UUID> wanted = new HashSet<>(systemIds);
            campaignRows.forEach(row -> wanted.add(row.systemId()));
            Map<UUID, GameSystem> systems = new HashMap<>();
            store.findSystems(wanted).forEach(row -> systems.put(row.id(), open(row)));
            Map<UUID, Campaign> campaigns = new HashMap<>();
            for (CampaignRow row : campaignRows) {
                GameSystem system = systems.get(row.systemId());
                if (system != null) {
                    campaigns.put(row.id(), open(row, system));
                }
            }
            systems.keySet().retainAll(Set.copyOf(systemIds));
            return new Places(Map.copyOf(campaigns), Map.copyOf(systems));
        });
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
        String name = name(fields.name(), LibraryProblems.SYSTEM_NAME_INVALID);
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
        String newName = fields.name() == null ? null : name(fields.name(), LibraryProblems.SYSTEM_NAME_INVALID);
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

    /**
     * A new campaign of the owner's system {@code systemId} (Story 3.2), named {@code typedName}: it becomes the current
     * campaign. Empty, and nothing is stored, for an unknown system, another owner's or one being deleted. Throws
     * {@code request.campaign_name_invalid} (400) for a name outside its limits and {@code request.campaign_name_taken}
     * (409) when another campaign of the system has the same name; nothing is stored then.
     */
    public Optional<Campaign> createCampaign(UUID systemId, String typedName) {
        String name = name(typedName, LibraryProblems.CAMPAIGN_NAME_INVALID);
        UUID owner = OwnerContext.require();
        UUID id = Ids.newId();
        byte[] fingerprint = campaignFingerprint(name);
        Optional<Campaign> created = writing(() -> store.findSystem(systemId).map(system -> {
            if (campaignNameTaken(system.id(), name, fingerprint, id)) {
                throw new ApiException(LibraryProblems.CAMPAIGN_NAME_TAKEN);
            }
            Instant now = now();
            CampaignRow row = new CampaignRow(id, owner, system.id(),
                    vault.encryptText(binding(CAMPAIGN, FIELD_NAME, id, owner), name), fingerprint, null, now, 0, now);
            conflicts(() -> {
                store.insertCampaign(row);
                return row;
            });
            return new Campaign(id, name, open(system), false, row.version());
        }));
        created.ifPresent(campaign -> log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", id)
                .addKeyValue("systemId", systemId).log("library.campaign_created"));
        return created;
    }

    /**
     * Changes the owner's campaign {@code id} (Story 3.2) on the version {@code version}: a {@code typedName} that is not
     * null renames it (checked for uniqueness), an {@code archived} that is not null archives or restores it; without a
     * name the stored name and fingerprint are kept as they are. Answers the campaign as stored, its
     * version raised by one; empty for an unknown campaign, another owner's or one of a system being deleted. Throws
     * {@code request.campaign_name_invalid} (400), {@code request.stale_version} (409) when the campaign is no longer at
     * {@code version}, and {@code request.campaign_name_taken} (409) when another campaign of its system has the new
     * name; nothing changes then.
     */
    public Optional<Campaign> changeCampaign(UUID id, long version, String typedName, Boolean archived) {
        String newName = typedName == null ? null : name(typedName, LibraryProblems.CAMPAIGN_NAME_INVALID);
        Optional<Campaign> changed = writing(() -> store.findCampaign(id).map(row -> {
            if (row.version() != version) {
                throw new ApiException(LibraryProblems.STALE_VERSION);
            }
            boolean archive = archived == null ? row.archived() : archived;
            CampaignChange change;
            if (newName == null) {
                // No rename: the stored name and fingerprint (null on a row the fill has not reached) stay as they are.
                change = new CampaignChange(id, version, row.name(), row.nameFingerprint(), archive, now());
            } else {
                byte[] fingerprint = campaignFingerprint(newName);
                if (campaignNameTaken(row.systemId(), newName, fingerprint, id)) {
                    throw new ApiException(LibraryProblems.CAMPAIGN_NAME_TAKEN);
                }
                change = new CampaignChange(id, version,
                        vault.encryptText(binding(CAMPAIGN, FIELD_NAME, id, row.ownerId()), newName), fingerprint, archive, now());
            }
            CampaignRow stored = conflicts(() -> store.updateCampaign(change))
                    .orElseThrow(() -> new IllegalStateException("library.campaign_vanished"));
            return open(stored, systemOf(stored.systemId()));
        }));
        changed.ifPresent(campaign -> log.atInfo().addKeyValue("ownerId", OwnerContext.require()).addKeyValue("campaignId", id)
                .addKeyValue("version", campaign.version()).addKeyValue("archived", campaign.archived())
                .log("library.campaign_changed"));
        return changed;
    }

    /**
     * The owner's campaign {@code id} becomes the current one (Story 3.2); its version stays. Answers it; empty, and
     * nothing changes, for an archived campaign, an unknown one, another owner's or one of a system being deleted.
     */
    public Optional<Campaign> chooseCampaign(UUID id) {
        Optional<Campaign> chosen = writing(() -> store.chooseCampaign(id, now())
                ? store.findCampaign(id).map(this::open) : Optional.<Campaign>empty());
        chosen.ifPresent(campaign -> log.atInfo().addKeyValue("ownerId", OwnerContext.require()).addKeyValue("campaignId", id)
                .log("library.campaign_chosen"));
        return chosen;
    }

    /**
     * Fills the name fingerprint of the owner's campaigns that have none yet (rows from before V23, Story 3.2), in one
     * transaction. A campaign whose name another campaign of its system already holds keeps none (logged by id), so the
     * fill never fails on the key. Answers how many it filled.
     */
    public int fillCampaignFingerprints() {
        UUID owner = OwnerContext.require();
        int filled = writing(() -> {
            int count = 0;
            for (CampaignRow row : store.campaignsWithoutFingerprint(null)) {
                byte[] fingerprint = campaignFingerprint(campaignName(row));
                if (store.campaignNameTaken(row.systemId(), fingerprint, row.id())) {
                    log.atWarn().addKeyValue("ownerId", owner).addKeyValue("campaignId", row.id())
                            .log("library.campaign_fingerprint_duplicate");
                } else if (store.fillCampaignFingerprint(row.id(), fingerprint)) {
                    count++;
                }
            }
            return count;
        });
        if (filled > 0) {
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaigns", filled).log("library.campaign_fingerprints_filled");
        }
        return filled;
    }

    /**
     * Whether another campaign of the system has this name: by its fingerprint, or, for a campaign that has none yet, by
     * its decrypted name's unique form.
     */
    private boolean campaignNameTaken(UUID systemId, String name, byte[] fingerprint, UUID except) {
        if (store.campaignNameTaken(systemId, fingerprint, except)) {
            return true;
        }
        String form = uniqueForm(name);
        return store.campaignsWithoutFingerprint(systemId).stream()
                .anyMatch(row -> !row.id().equals(except) && uniqueForm(campaignName(row)).equals(form));
    }

    /** A campaign's name fingerprint (Story 3.2): of the name's unique form. */
    byte[] campaignFingerprint(String name) {
        return vault.fingerprint(CAMPAIGN, FIELD_NAME, uniqueForm(name));
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

    /** A system's or a campaign's name, trimmed, or {@code invalid}. */
    private static String name(String typed, ApiProblem invalid) {
        String name = typed == null ? "" : typed.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > NAME_MAX) {
            throw new ApiException(invalid);
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

    /** A list starts at a cursor or at a position, never both, and no position is below zero or past the cap. */
    private static void checkOffset(UUID cursor, int offset) {
        if (offset < 0 || offset > MAX_OFFSET || (offset > 0 && cursor != null)) {
            throw new IllegalArgumentException("library.offset_invalid");
        }
    }

    private byte[] encrypt(String field, UUID id, UUID owner, String value) {
        return value == null ? null : vault.encryptText(binding(SYSTEM, field, id, owner), value);
    }

    private String decrypt(String field, SystemRow row, byte[] stored) {
        return stored == null ? null : vault.decryptText(binding(SYSTEM, field, row.id(), row.ownerId()), stored);
    }

    private Campaign open(CampaignRow row) {
        return open(row, systemOf(row.systemId()));
    }

    private Campaign open(CampaignRow row, GameSystem system) {
        return new Campaign(row.id(), campaignName(row), system, row.archived(), row.version());
    }

    /** A campaign's system: the database keeps it within its owner, so a missing one is a broken row, not a 404. */
    private GameSystem systemOf(UUID systemId) {
        return open(store.findSystem(systemId).orElseThrow(() -> new IllegalStateException("library.campaign_without_system")));
    }

    private String campaignName(CampaignRow row) {
        return vault.decryptText(binding(CAMPAIGN, FIELD_NAME, row.id(), row.ownerId()), row.name());
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
            throw new ApiException(switch (conflict.reason()) {
                case NAME_TAKEN -> LibraryProblems.SYSTEM_NAME_TAKEN;
                case CAMPAIGN_NAME_TAKEN -> LibraryProblems.CAMPAIGN_NAME_TAKEN;
                case STALE_VERSION -> LibraryProblems.STALE_VERSION;
            });
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
