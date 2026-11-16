package io.github.gmshield.library.service;

import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * unknown one. Names are encrypted for the owner, bound to the entity, the field {@code name} and the row; a
 * system's name also has a fingerprint of its NFC lower-case, trimmed form, unique per owner.
 *
 * <p>Epic 3 adds creating, renaming, deleting and switching. Until then each owner has the one system and the
 * one campaign of their first sign-in, and the current campaign is the oldest.
 */
@Service
public class Library {

    static final String SYSTEM = "system";
    static final String CAMPAIGN = "campaign";
    static final String FIELD_NAME = "name";
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

    /** A system or a campaign by its id and its name, decrypted. */
    public record Named(UUID id, String name) {
        @Override
        public String toString() {
            return "Named[id=" + id + "]";
        }
    }

    /** A campaign with its system. */
    public record Campaign(UUID id, String name, Named system) {
        @Override
        public String toString() {
            return "Campaign[id=" + id + ", system=" + system + "]";
        }
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
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID systemId = Ids.newId();
        store.insertSystem(new SystemRow(systemId, owner, vault.encryptText(binding(SYSTEM, systemId, owner), names[0]),
                vault.fingerprint(SYSTEM, FIELD_NAME, uniqueForm(names[0])), now));
        UUID campaignId = Ids.newId();
        store.insertCampaign(new CampaignRow(campaignId, owner, systemId,
                vault.encryptText(binding(CAMPAIGN, campaignId, owner), names[1]), now));
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
    public Optional<Named> system(UUID id) {
        return reading(() -> store.findSystem(id).map(this::open));
    }

    /** The form whose fingerprint is unique per owner: trimmed, NFC, lower case in the root locale. */
    public static String uniqueForm(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private Campaign open(CampaignRow row) {
        // The database keeps a campaign's system within its owner: a missing one is a broken row, not a 404.
        SystemRow system = store.findSystem(row.systemId())
                .orElseThrow(() -> new IllegalStateException("library.campaign_without_system"));
        return new Campaign(row.id(), vault.decryptText(binding(CAMPAIGN, row.id(), row.ownerId()), row.name()), open(system));
    }

    private Named open(SystemRow row) {
        return new Named(row.id(), vault.decryptText(binding(SYSTEM, row.id(), row.ownerId()), row.name()));
    }

    /** One read-only transaction for the reads of one answer, so they come from one session. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    private static Binding binding(String entity, UUID rowId, UUID owner) {
        return new Binding(entity, FIELD_NAME, rowId, owner);
    }
}
