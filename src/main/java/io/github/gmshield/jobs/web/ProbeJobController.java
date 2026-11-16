package io.github.gmshield.jobs.web;

import io.github.gmshield.jobs.service.ProbeJobs;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.StartupSettings;
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
 * Test mode only: creates a probe job ({@code {lane, workMs, failTimes}}) and shows its state. Registered only
 * under the {@code test-mode} profile, left out of both OpenAPI documents, and, like every test-mode route, the one
 * part of {@code /internal/v1} that needs no worker credential.
 */
@Hidden
@RestController
@Profile(StartupSettings.TEST_MODE_PROFILE)
@RequestMapping(ProbeJobController.PATH)
class ProbeJobController {

    static final String PATH = "/internal/v1/test-mode/probe-jobs";

    private final ProbeJobs probes;

    ProbeJobController(ProbeJobs probes) {
        this.probes = probes;
    }

    record ProbeRequest(String lane, Integer workMs, Integer failTimes) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> create(@RequestBody ProbeRequest request) {
        UUID id = probes.create(request.lane(), request.workMs(), request.failTimes());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(Map.of("id", id.toString()));
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProbeJobs.Status> status(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(probes.status(id).orElseThrow(() -> new ApiException(ApiProblem.forStatus(404))));
    }
}
