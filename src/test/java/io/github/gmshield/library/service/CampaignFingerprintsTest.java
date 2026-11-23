package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.InMemoryFingerprintBackfill;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.2: campaigns from before V23 have no name fingerprint. Until the fill at start reaches their owner, a new
 * campaign is still compared with them; the fill gives each its fingerprint without raising its version, leaves a
 * duplicate without one, and keeps an owner whose fill failed listed for the next start.
 */
class CampaignFingerprintsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final InMemoryLibraryStore store = new InMemoryLibraryStore();
    private final InMemoryFingerprintBackfill backfill = new InMemoryFingerprintBackfill();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final Vault vault = TestVaults.vault();
    private final Library library;

    CampaignFingerprintsTest() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        library = new Library(store, vault, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
    }

    @Test
    void aCampaignWithoutAFingerprintHoldsItsNameUntilTheFillGivesItOne() {
        UUID owner = Ids.newId();
        UUID system = system(owner);
        UUID old = oldCampaign(owner, system, "Velha mesa");
        UUID twin = oldCampaign(owner, system, " VELHA mesa");
        UUID other = oldCampaign(owner, system, "Outra");
        UUID empty = Ids.newId();
        backfill.owners.add(owner);
        backfill.owners.add(empty);

        // Before the fill, its name is taken all the same.
        assertThatThrownBy(() -> OwnerContext.callAs(owner, () -> library.createCampaign(system, "velha MESA ")))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.problem().code()).isEqualTo("request.campaign_name_taken"));

        assertThat(new CampaignFingerprints(backfill, library).run()).isEqualTo(2);

        assertThat(store.campaigns.get(other).nameFingerprint()).isEqualTo(vault.fingerprint("campaign", "name", "outra"));
        // Of the two with one name, the first filled takes it; the duplicate keeps none, rather than failing the key.
        assertThat(java.util.Arrays.asList(store.campaigns.get(old).nameFingerprint(), store.campaigns.get(twin).nameFingerprint()))
                .containsExactlyInAnyOrder(null, vault.fingerprint("campaign", "name", "velha mesa"));
        // No version moved.
        assertThat(store.campaigns.values()).allSatisfy(row -> assertThat(row.version()).isZero());
        assertThat(backfill.owners).isEmpty();
        // After it, the key itself holds the name; a repeat does nothing.
        assertThatThrownBy(() -> OwnerContext.callAs(owner, () -> library.createCampaign(system, "OUTRA")))
                .isInstanceOf(ApiException.class);
        assertThat(new CampaignFingerprints(backfill, library).run()).isZero();
    }

    @Test
    void aCampaignWithoutAFingerprintWhoseTwinHoldsItsNameIsArchivedAndRestoredAsItIs() {
        UUID owner = Ids.newId();
        UUID system = system(owner);
        UUID one = oldCampaign(owner, system, "Velha mesa");
        UUID two = oldCampaign(owner, system, "VELHA mesa");
        backfill.owners.add(owner);
        new CampaignFingerprints(backfill, library).run();
        UUID twin = store.campaigns.get(one).nameFingerprint() == null ? one : two;
        byte[] name = store.campaigns.get(twin).name();
        assertThat(store.campaigns.get(twin).nameFingerprint()).isNull();

        // No name sent: no uniqueness check, and the stored name and missing fingerprint stay as they are.
        Library.Campaign archived = OwnerContext.callAs(owner, () -> library.changeCampaign(twin, 0, null, true)).orElseThrow();
        assertThat(archived.archived()).isTrue();
        Library.Campaign restored = OwnerContext.callAs(owner, () -> library.changeCampaign(twin, 1, null, false)).orElseThrow();
        assertThat(restored.archived()).isFalse();
        assertThat(restored.version()).isEqualTo(2);
        assertThat(store.campaigns.get(twin).nameFingerprint()).isNull();
        assertThat(store.campaigns.get(twin).name()).isEqualTo(name);
        // A rename to the name its twin holds is still refused.
        assertThatThrownBy(() -> OwnerContext.callAs(owner, () -> library.changeCampaign(twin, 2, "velha MESA", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.problem().code()).isEqualTo("request.campaign_name_taken"));
    }

    @Test
    void anOwnerWhoseFillFailsStaysListedAndTheOthersAreFilled() {
        UUID broken = Ids.newId();
        UUID system = system(broken);
        UUID unreadable = Ids.newId();
        OwnerContext.runAs(broken, () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                store.insertCampaign(new CampaignRow(unreadable, broken, system, new byte[] {1, 2, 3}, NOW))));
        UUID fine = Ids.newId();
        UUID fineSystem = system(fine);
        UUID campaign = oldCampaign(fine, fineSystem, "Mesa");
        backfill.owners.add(broken);
        backfill.owners.add(fine);

        assertThat(new CampaignFingerprints(backfill, library).run()).isEqualTo(1);

        assertThat(backfill.owners).containsExactly(broken);
        assertThat(store.campaigns.get(unreadable).nameFingerprint()).isNull();
        assertThat(store.campaigns.get(campaign).nameFingerprint()).isNotNull();
    }

    private UUID system(UUID owner) {
        UUID id = Ids.newId();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                store.insertSystem(new SystemRow(id, owner, vault.encryptText(new Binding("system", "name", id, owner), "S"),
                        vault.fingerprint("system", "name", "s"), NOW))));
        return id;
    }

    /** A campaign as V23 found it: no fingerprint. */
    private UUID oldCampaign(UUID owner, UUID system, String name) {
        UUID id = Ids.newId();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                store.insertCampaign(new CampaignRow(id, owner, system,
                        vault.encryptText(new Binding("campaign", "name", id, owner), name), NOW))));
        return id;
    }
}
