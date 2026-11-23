package io.github.gmshield.vault.web;

import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test only: reads a value moved to another row, the way a request would meet a tampered row. Outside both APIs.
 * Profile {@code unit}: the integration tests' contexts, which scan the test classes too, leave it out.
 */
@Profile("unit")
@RestController
public class IntegrityTestController {

    public static final String PATH = "/test-only/integrity";
    public static final String SECRET_TEXT = "Secret door behind the waterfall";

    private final Vault vault;

    IntegrityTestController(Vault vault) {
        this.vault = vault;
    }

    @GetMapping(PATH)
    String movedValue() {
        UUID owner = Ids.newId();
        byte[] stored = vault.encryptText(new Binding("note", "body", Ids.newId(), owner), SECRET_TEXT);
        return vault.decryptText(new Binding("note", "body", Ids.newId(), owner), stored);
    }
}
