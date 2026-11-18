package io.github.gmshield.accounts.service;

import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.StartupSettings;
import io.github.gmshield.vault.service.Nfr1ProbeSeeded;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Test mode only: the NFR-1 gate's known text also becomes a real account row, through the same encryption,
 * fingerprints and seal as a sign-in. The text is the Google id, and the start of the email address. The
 * account is read back at once; a difference fails the seed.
 */
@Component
@Profile(StartupSettings.TEST_MODE_PROFILE)
class Nfr1AccountSeed {

    private final Accounts accounts;

    Nfr1AccountSeed(Accounts accounts) {
        this.accounts = accounts;
    }

    @EventListener
    void seed(Nfr1ProbeSeeded seeded) {
        UUID id = Ids.newId();
        String email = seeded.text() + "@nfr1.example.test";
        String username = "nfr1-" + id.toString().substring(24);
        accounts.create(id, accounts.encryptGoogleId(id, seeded.text()), accounts.encryptEmail(id, email), username,
                SignIn.TERMS_VERSION, Instant.now());
        Account back = accounts.byGoogleId(seeded.text())
                .orElseThrow(() -> new IllegalStateException("the NFR-1 account was not found by its Google id"));
        if (!back.id().equals(id) || !seeded.text().equals(back.googleId()) || !email.equals(back.email())
                || !username.equals(back.username())) {
            throw new IllegalStateException("the NFR-1 account did not read back what it stored");
        }
    }
}
