package io.github.gmshield.vault.web;

import io.github.gmshield.shared.service.StartupSettings;
import io.github.gmshield.vault.service.Nfr1Probe;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The NFR-1 release gate's seed (GMshield-pipeline, {@code [nfr1]} in {@code .gmshield/pipeline.toml}).
 * Registered only under the {@code test-mode} profile and left out of both OpenAPI documents.
 */
@Hidden
@RestController
@Profile(StartupSettings.TEST_MODE_PROFILE)
@RequestMapping(Nfr1ProbeController.PATH)
class Nfr1ProbeController {

    static final String PATH = "/internal/v1/test-mode/nfr1-probe";

    private final Nfr1Probe probe;

    Nfr1ProbeController(Nfr1Probe probe) {
        this.probe = probe;
    }

    record SeedRequest(String text) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> seed(@RequestBody SeedRequest request) {
        UUID id = probe.seed(request.text());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(Map.of("id", id.toString()));
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Nfr1Probe.ReadBack> readBack(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(probe.readBack(id));
    }
}
