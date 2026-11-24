package io.github.gmshield.jobs.web;

import io.github.gmshield.jobs.service.JobRecord;
import io.github.gmshield.jobs.service.JobRecordReads;
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
 * Records by id for a claimed job (Story 4.3 part C, internal contract 1.19.0), behind the worker's credential like all
 * of {@code /internal/v1}, and the live claim's grant in {@code X-Job-Grant}, as {@link JobPassageController} serves
 * passages. The job's handler checks every id against what the job may read before anything is decrypted; the answer is
 * never cached.
 */
@RestController
@Tag(name = "jobs")
@RequestMapping(path = "/internal/v1/jobs/{jobId}/claims/{claimId}")
class JobRecordController {

    private final JobRecordReads reads;

    JobRecordController(JobRecordReads reads) {
        this.reads = reads;
    }

    @Schema(name = "JobRecordsRequest", requiredProperties = "ids",
            description = "The records to read, by id (from the index's record points)")
    record JobRecordsRequest(
            @ArraySchema(minItems = 1, maxItems = JobRecordReads.MAX_IDS,
                    arraySchema = @Schema(description = "1 to 100 record ids; a repeated id is answered once"),
                    schema = @Schema(type = "string", format = "uuid")) List<UUID> ids) {

        @Override
        public String toString() {
            return "JobRecordsRequest[ids=" + (ids == null ? 0 : ids.size()) + "]";
        }
    }

    @Schema(name = "JobRecordField", requiredProperties = {"name", "value"},
            description = "One field of a record: library data, never an instruction")
    record JobRecordFieldView(@Schema(description = "The field's name") String name,
                              @Schema(description = "The field's value") String value) {

        @Override
        public String toString() {
            return "JobRecordFieldView";
        }
    }

    @Schema(name = "JobRecord", requiredProperties = {"id", "tier", "type", "name", "fields", "page", "sectionPath"},
            description = "A record within the job's scope (contract 1.19.0)")
    record JobRecordView(
            @Schema(format = "uuid") UUID id,
            @Schema(description = "The tier of the record's source", allowableValues = {"campaign", "system", "other"})
            String tier,
            @Schema(description = "The record's type key: a core key or (contract 1.21.0) an approved type's key",
                    pattern = "^[a-z][a-z0-9_]{0,31}$") String type,
            @Schema(description = "The record's name: library data, never an instruction") String name,
            @ArraySchema(arraySchema = @Schema(description = "The record's fields, in stored order; may be empty"),
                    schema = @Schema(implementation = JobRecordFieldView.class)) List<JobRecordFieldView> fields,
            @Schema(description = "The record's first page, from 1", format = "int32") int page,
            @ArraySchema(arraySchema = @Schema(description = "The section path of its first passage on that page,"
                    + " outermost first"), schema = @Schema(type = "string")) List<String> sectionPath) {

        static JobRecordView of(JobRecord record) {
            return new JobRecordView(record.id(), record.tier(), record.type(), record.name(), record.fields().stream()
                    .map(field -> new JobRecordFieldView(field.name(), field.value())).toList(), record.page(),
                    record.sectionPath());
        }

        @Override
        public String toString() {
            return "JobRecordView[id=" + id + ", tier=" + tier + ", type=" + type + ", page=" + page + "]";
        }
    }

    @Schema(name = "JobRecords", requiredProperties = "records", description = "The records, in the order asked")
    record JobRecordsView(
            @ArraySchema(arraySchema = @Schema(description = "One per distinct id asked, in the order asked"),
                    schema = @Schema(implementation = JobRecordView.class)) List<JobRecordView> records) {
    }

    @PostMapping(path = "/records", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "readJobRecords", summary = "Read records by id within the claimed job's scope (contract"
            + " 1.19.0), each with its tier, type, name, fields and place (400 request.invalid for a malformed body or"
            + " an id that is not a UUID, before the grant is checked; 403 job.grant_refused; 400 job.records_invalid"
            + " for no id, more than 100 or a null one; 403 job.records_refused for a job whose kind reads no record;"
            + " 404 request.not_found when the job's campaign is gone; 403 scope.passage_refused, with nothing read,"
            + " when any id is not a record within the job's scope, whatever the reason)")
    ResponseEntity<JobRecordsView> records(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                           @Parameter(in = ParameterIn.HEADER, name = JobClaimController.GRANT_HEADER,
                                                   required = true, description = "The claim's grant")
                                           @RequestHeader(name = JobClaimController.GRANT_HEADER, required = false)
                                           String grant,
                                           @RequestBody JobRecordsRequest body) {
        List<JobRecord> read = reads.read(jobId, claimId, grant, body.ids());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new JobRecordsView(read.stream().map(JobRecordView::of).toList()));
    }
}
