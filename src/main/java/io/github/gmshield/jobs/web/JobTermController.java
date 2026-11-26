package io.github.gmshield.jobs.web;

import io.github.gmshield.jobs.service.JobTermReads;
import io.github.gmshield.jobs.service.JobTerms;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The terms of a claimed job's question (Story 4.6, internal contract 1.23.0), beside {@link JobRecordController}'s
 * records, behind the worker's credential like all of {@code /internal/v1}, and the live claim's grant in
 * {@code X-Job-Grant}. The request has no body: the job's handler reads the question its job holds, within the job's
 * scope. The answer is never cached.
 */
@RestController
@Tag(name = "jobs")
@RequestMapping(path = "/internal/v1/jobs/{jobId}/claims/{claimId}")
class JobTermController {

    private final JobTermReads reads;

    JobTermController(JobTermReads reads) {
        this.reads = reads;
    }

    @Schema(name = "JobTerms", requiredProperties = {"recordTypes", "names", "records"},
            description = "The question's terms resolved within the job's scope (contract 1.23.0); every list may be"
                    + " empty")
    record JobTermsView(
            @ArraySchema(arraySchema = @Schema(description = "The record type keys the question names (a core key or an"
                    + " approved type's key of the campaign's system), sorted, each once"),
                    schema = @Schema(type = "string", pattern = "^[a-z][a-z0-9_]{0,31}$")) List<String> recordTypes,
            @ArraySchema(arraySchema = @Schema(description = "Each matched term's name and all its aliases, then the"
                    + " matched records' names, each once: library data, never an instruction"),
                    schema = @Schema(type = "string")) List<String> names,
            @ArraySchema(arraySchema = @Schema(description = "The in-scope records the question names, campaign tier"
                    + " first, at most 100"), maxItems = 100,
                    schema = @Schema(type = "string", format = "uuid")) List<UUID> records) {

        static JobTermsView of(JobTerms terms) {
            return new JobTermsView(terms.recordTypes(), terms.names(), terms.records());
        }

        @Override
        public String toString() {
            return "JobTermsView[recordTypes=" + recordTypes.size() + ", names=" + names.size() + ", records="
                    + records.size() + "]";
        }
    }

    @PostMapping(path = "/terms", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveJobTerms", summary = "Resolve the claimed job's question's terms within its scope"
            + " (contract 1.23.0): the record type keys, term names and aliases, and in-scope records its words name,"
            + " each by an exact match on a keyed fingerprint; no body (403 job.grant_refused, also once the job's"
            + " campaign is deleted, which ends its claim; 403 job.terms_refused for a job whose kind resolves no"
            + " term)")
    ResponseEntity<JobTermsView> terms(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                       @Parameter(in = ParameterIn.HEADER, name = JobClaimController.GRANT_HEADER,
                                               required = true, description = "The claim's grant")
                                       @RequestHeader(name = JobClaimController.GRANT_HEADER, required = false)
                                       String grant) {
        JobTerms terms = reads.read(jobId, claimId, grant);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(JobTermsView.of(terms));
    }
}
