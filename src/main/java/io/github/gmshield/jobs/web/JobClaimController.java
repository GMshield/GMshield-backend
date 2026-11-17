package io.github.gmshield.jobs.web;

import io.github.gmshield.jobs.service.JobClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker's job routes (Story 1.12), behind the worker's credential like all of {@code /internal/v1}. A claim
 * answers the grant once; a renewal and an outcome need it in {@code X-Job-Grant}.
 */
@RestController
@Tag(name = "jobs")
@RequestMapping(path = "/internal/v1/jobs/{jobId}/claims")
class JobClaimController {

    static final String GRANT_HEADER = "X-Job-Grant";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JobClaims claims;

    JobClaimController(JobClaims claims) {
        this.claims = claims;
    }

    @Schema(description = "A live claim of a job. The grant is given once and never stored: only its keyed hash is.")
    record JobClaim(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID claimId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 for the first claim") int attempt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Renew the claim at this time") Instant renewAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Without a renewal the claim lapses then")
            Instant lapsesAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send it in X-Job-Grant") String grant,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The handler's input for this attempt")
            Map<String, Object> input,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "W3C traceparent of the job's trace: log under it and send it on every call")
            String traceparent) {
        @Override
        public String toString() {
            return "JobClaim[claimId=" + claimId + ", attempt=" + attempt + "]";
        }
    }

    @Schema(description = "The live claim's new times.")
    record JobRenewal(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant renewAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant lapsesAt) {
    }

    @Schema(description = "How the claimed attempt ended.")
    record JobOutcome(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"done", "failed"}) String outcome,
            @Schema(description = "For failed only: category.reason in lower case") String code,
            @Schema(description = "For failed only: false ends the job at once, without another attempt (an input no"
                    + " attempt can change); absent or true lets the backend retry while attempts remain") Boolean retry,
            @Schema(description = "For done only: the result for the job's handler") Map<String, Object> result) {
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "claimJob", summary = "Claim an offered job (409 job.claimed, 409 job.not_offered)")
    ResponseEntity<JobClaim> claim(@PathVariable UUID jobId) {
        JobClaims.Claim claim = claims.claim(jobId);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new JobClaim(claim.claimId(), claim.attempt(), claim.renewAt(), claim.lapsesAt(), claim.grant(),
                        claim.kind(), JSON.convertValue(claim.input(), OBJECT), claim.traceparent()));
    }

    @PostMapping(path = "/{claimId}/renewals", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renewJobClaim", summary = "Renew a live claim (403 job.grant_refused)")
    ResponseEntity<JobRenewal> renew(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                     @Parameter(in = ParameterIn.HEADER, name = GRANT_HEADER, required = true,
                                             description = "The claim's grant")
                                     @RequestHeader(name = GRANT_HEADER, required = false) String grant) {
        JobClaims.Renewal renewal = claims.renew(jobId, claimId, grant);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new JobRenewal(renewal.renewAt(), renewal.lapsesAt()));
    }

    @PostMapping(path = "/{claimId}/outcome", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "reportJobOutcome", summary = "Report how the claimed attempt ended (403 job.grant_refused,"
            + " 400 job.result_invalid when the job's handler cannot record the result)")
    ResponseEntity<Void> outcome(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                 @Parameter(in = ParameterIn.HEADER, name = GRANT_HEADER, required = true,
                                         description = "The claim's grant")
                                 @RequestHeader(name = GRANT_HEADER, required = false) String grant,
                                 @RequestBody JobOutcome body) {
        JsonNode result = body.result() == null ? null : JSON.valueToTree(body.result());
        claims.report(jobId, claimId, grant, body.outcome(), body.code(), body.retry(), result);
        return ResponseEntity.noContent().build();
    }
}
