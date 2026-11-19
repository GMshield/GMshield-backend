package io.github.gmshield.jobs.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.jobs.service.CallUsage;
import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobEvent;
import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.jobs.service.ModelChoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
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
            String traceparent,
            @Schema(description = "For a kind that calls a model (contract 1.9.0): the model of this attempt, resolved again"
                    + " at every claim. Absent for every other kind") @JsonInclude(JsonInclude.Include.NON_NULL)
            JobModel model) {
        @Override
        public String toString() {
            return "JobClaim[claimId=" + claimId + ", attempt=" + attempt + "]";
        }
    }

    @Schema(description = "The model one attempt calls (contract 1.9.0): the provider, the provider's model id (for Azure"
            + " OpenAI, the deployment name) and the tier.")
    record JobModel(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z0-9-]{1,32}$", maxLength = 32,
                    description = "azure-openai, anthropic or openai; an open word, so a new provider needs no new"
                            + " contract") String provider,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128) String model,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "full or lightweight") String tier) {

        static JobModel of(ModelChoice choice) {
            return choice == null ? null : new JobModel(choice.provider(), choice.model(), choice.tier());
        }
    }

    @Schema(description = "What the worker states when it claims (contract 1.7.0). Every field is optional; a claim may"
            + " send no body at all.")
    record JobClaimRequest(
            @Schema(description = "The worker's index recipe (AD-10): when it differs from the last one stated, the"
                    + " backend re-indexes every ready version recorded under another one", pattern = "^[A-Za-z0-9._-]{1,32}$",
                    maxLength = 32) String indexRecipe,
            @ArraySchema(maxItems = 8, arraySchema = @Schema(description = "The providers the worker holds a key for"
                    + " (contract 1.9.0). A kind that calls a model is refused (409 job.not_offered, the job ends failed"
                    + " with job.provider_unavailable, no attempt used) unless its resolved provider is stated; absent"
                    + " means none"),
                    schema = @Schema(pattern = "^[a-z0-9-]{1,32}$", maxLength = 32)) List<String> providers) {
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

    @Schema(name = "JobStreamEvent", requiredProperties = "type", description = "One answer event (contract 1.10.0): a"
            + " delta with its display text, or a citation of a given passage id at a code-point offset of the answer's"
            + " text")
    record JobStreamEvent(
            @Schema(allowableValues = {"delta", "citation"}) String type,
            @Schema(description = "For delta only: 1 to 16,384 code points of display text, citation markers removed",
                    maxLength = JobClaims.MAX_DELTA) String text,
            @Schema(description = "For citation only: the cited passage's id", format = "uuid") UUID passageId,
            @Schema(description = "For citation only: where the citation stands, in code points of the answer's text",
                    minimum = "0", format = "int32") Integer offset) {

        JobEvent event() {
            return new JobEvent(type, text, passageId, offset);
        }

        @Override
        public String toString() {
            return "JobStreamEvent[type=" + type + "]";
        }
    }

    @Schema(name = "JobCallUsage", requiredProperties = {"callId", "input", "cacheRead", "cacheWrite", "output",
            "reasoning"}, description = "One model call's tokens (contract 1.10.0), recorded once by its call id")
    record JobCallUsage(
            @Schema(description = "The worker's id of the call (a UUIDv7)", format = "uuid") UUID callId,
            @Schema(description = "The provider's response id, when it sent one", maxLength = 256) String responseId,
            @Schema(description = "Input tokens, cache reads and writes included", minimum = "0", format = "int64")
            long input,
            @Schema(minimum = "0", format = "int64") long cacheRead,
            @Schema(minimum = "0", format = "int64") long cacheWrite,
            @Schema(description = "Output tokens, reasoning included", minimum = "0", format = "int64") long output,
            @Schema(minimum = "0", format = "int64") long reasoning) {

        CallUsage usage() {
            return new CallUsage(callId, responseId, input, cacheRead, cacheWrite, output, reasoning);
        }
    }

    @Schema(name = "JobEvents", requiredProperties = {"batch", "events"}, description = "A numbered batch of answer"
            + " events of the claim (contract 1.10.0), with the usage of the model calls that ended since the last one")
    record JobEventsRequest(
            @Schema(description = "From 1 per claim, one more each time; a repeated number is accepted and stored once",
                    minimum = "1", format = "int32") Integer batch,
            @ArraySchema(maxItems = JobClaims.MAX_EVENTS, arraySchema = @Schema(description = "In order; may be empty"),
                    schema = @Schema(implementation = JobStreamEvent.class)) List<JobStreamEvent> events,
            @ArraySchema(maxItems = JobClaims.MAX_USAGE, arraySchema = @Schema(description = "Absent means none"),
                    schema = @Schema(implementation = JobCallUsage.class)) List<JobCallUsage> usage) {

        @Override
        public String toString() {
            return "JobEventsRequest[batch=" + batch + "]";
        }
    }

    @Schema(name = "JobEventsReply", requiredProperties = "stop", description = "Whether the worker must stop the"
            + " answer (always false until the GM can stop one)")
    record JobEventsReply(boolean stop) {
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "claimJob", summary = "Claim an offered job (409 job.claimed, 409 job.not_offered, 400 for"
            + " an index recipe or providers that are not ones)")
    ResponseEntity<JobClaim> claim(@PathVariable UUID jobId,
                                   @RequestBody(required = false) JobClaimRequest body) {
        JobClaims.Claim claim = body == null ? claims.claim(jobId)
                : claims.claim(jobId, body.indexRecipe(), body.providers());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new JobClaim(claim.claimId(), claim.attempt(), claim.renewAt(), claim.lapsesAt(), claim.grant(),
                        claim.kind(), JSON.convertValue(claim.input(), OBJECT), claim.traceparent(),
                        JobModel.of(claim.model())));
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

    @PostMapping(path = "/{claimId}/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "postJobEvents", summary = "Post a numbered batch of answer events of the live claim and"
            + " the usage of its model calls (400 request.invalid for a malformed body, before the grant is checked; 403"
            + " job.grant_refused; 400 job.events_invalid; 403 job.events_refused for a kind that streams no events; 409"
            + " job.batch_out_of_order, with nothing stored, for a number past the next one of the claim)")
    ResponseEntity<JobEventsReply> events(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                          @Parameter(in = ParameterIn.HEADER, name = GRANT_HEADER, required = true,
                                                  description = "The claim's grant")
                                          @RequestHeader(name = GRANT_HEADER, required = false) String grant,
                                          @RequestBody JobEventsRequest body) {
        List<JobEvent> events = body.events() == null ? null
                : body.events().stream().map(event -> event == null ? null : event.event()).toList();
        JobEventBatch batch = body.batch() == null || events == null ? null
                : new JobEventBatch(claimId, body.batch(), events.stream().filter(java.util.Objects::nonNull).toList());
        if (events != null && events.contains(null)) {
            batch = null;
        }
        List<CallUsage> usage = body.usage() == null ? List.of()
                : body.usage().stream().map(call -> call == null ? null : call.usage()).toList();
        boolean stop = claims.events(jobId, claimId, grant, batch, usage);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new JobEventsReply(stop));
    }
}
