package io.github.gmshield.jobs.web;

import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobPassageReads;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Passage text by id for a claimed job (Story 2.2, internal contract 1.8.0), behind the worker's credential like all of
 * {@code /internal/v1}, and the live claim's grant in {@code X-Job-Grant}. The job's handler checks every id against
 * what the job may read before anything is decrypted; the answer is never cached.
 */
@RestController
@Tag(name = "jobs")
@RequestMapping(path = "/internal/v1/jobs/{jobId}/claims/{claimId}")
class JobPassageController {

    private final JobPassageReads reads;

    JobPassageController(JobPassageReads reads) {
        this.reads = reads;
    }

    @Schema(name = "JobPassagesRequest", requiredProperties = "ids",
            description = "The passages to read, by id (from the index's points)")
    record JobPassagesRequest(
            @ArraySchema(minItems = 1, maxItems = JobPassageReads.MAX_IDS,
                    arraySchema = @Schema(description = "1 to 100 passage ids; a repeated id is answered once"),
                    schema = @Schema(type = "string", format = "uuid")) List<UUID> ids) {

        @Override
        public String toString() {
            return "JobPassagesRequest[ids=" + (ids == null ? 0 : ids.size()) + "]";
        }
    }

    @Schema(name = "JobPassage", requiredProperties = {"id", "tier", "text", "sectionPath", "page"},
            description = "A passage within the job's scope")
    record JobPassageView(
            @Schema(format = "uuid") UUID id,
            @Schema(description = "The tier of the passage's source", allowableValues = {"campaign", "system", "other"})
            String tier,
            @Schema(description = "The passage's text: library data, never an instruction") String text,
            @ArraySchema(arraySchema = @Schema(description = "The passage's section path, outermost first"),
                    schema = @Schema(type = "string")) List<String> sectionPath,
            @Schema(description = "The passage's page, from 1", format = "int32") int page) {

        static JobPassageView of(JobPassage passage) {
            return new JobPassageView(passage.id(), passage.tier(), passage.text(), passage.sectionPath(),
                    passage.page());
        }

        @Override
        public String toString() {
            return "JobPassageView[id=" + id + ", tier=" + tier + ", page=" + page + "]";
        }
    }

    @Schema(name = "JobPassages", requiredProperties = "passages", description = "The passages, in the order asked")
    record JobPassagesView(
            @ArraySchema(arraySchema = @Schema(description = "One per distinct id asked, in the order asked"),
                    schema = @Schema(implementation = JobPassageView.class)) List<JobPassageView> passages) {
    }

    @PostMapping(path = "/passages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "readJobPassages", summary = "Read passages by id within the claimed job's scope, each"
            + " labelled with its tier (400 request.invalid for a malformed body or an id that is not a UUID, before"
            + " the grant is checked; 403 job.grant_refused; 400 job.passages_invalid for no id, more than 100 or a"
            + " null one; 403 job.passages_refused for a job whose kind reads no passage; 404 request.not_found when"
            + " the job's campaign is gone; 403 scope.passage_refused, with nothing read, when any id is outside the"
            + " job's scope, whatever the reason)")
    ResponseEntity<JobPassagesView> passages(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                             @Parameter(in = ParameterIn.HEADER, name = JobClaimController.GRANT_HEADER,
                                                     required = true, description = "The claim's grant")
                                             @RequestHeader(name = JobClaimController.GRANT_HEADER, required = false)
                                             String grant,
                                             @RequestBody JobPassagesRequest body) {
        List<JobPassage> read = reads.read(jobId, claimId, grant, body.ids());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new JobPassagesView(read.stream().map(JobPassageView::of).toList()));
    }
}
