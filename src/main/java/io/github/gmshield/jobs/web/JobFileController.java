package io.github.gmshield.jobs.web;

import io.github.gmshield.jobs.service.JobFiles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The files of a claimed job (Story 1.15), behind the worker's credential like all of {@code /internal/v1}, and the
 * live claim's grant in {@code X-Job-Grant}: an input the job's handler opens, streamed as it is read, and an output
 * the worker sends back, streamed into the encrypted file store. Neither is ever held whole in memory.
 */
@RestController
@Tag(name = "jobs")
@RequestMapping(path = "/internal/v1/jobs/{jobId}/claims/{claimId}")
class JobFileController {

    private final JobFiles files;

    JobFileController(JobFiles files) {
        this.files = files;
    }

    @Schema(description = "A file the worker sent back, stored encrypted under an id the backend minted. The job's"
            + " handler adopts it when the job's done outcome names it; otherwise it is removed once the job ended.")
    record JobOutputStored(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Name it in the job's result") UUID fileId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Bytes stored", format = "int64") long size,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The SHA-256 of the bytes stored, in lower-case hexadecimal",
                    pattern = "^[0-9a-f]{64}$") String sha256) {
    }

    @GetMapping(path = "/inputs/{name}")
    @Operation(operationId = "openJobInput", summary = "Stream one of the claimed job's inputs, as its handler opens it"
            + " (403 job.grant_refused, 404 request.not_found for an input the job does not have)",
            responses = @ApiResponse(responseCode = "200", description = "The input's bytes",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary"))))
    void input(@PathVariable UUID jobId, @PathVariable UUID claimId,
               @Parameter(description = "The input's name, as the claim's input gives it") @PathVariable String name,
               @Parameter(in = ParameterIn.HEADER, name = JobClaimController.GRANT_HEADER, required = true,
                       description = "The claim's grant")
               @RequestHeader(name = JobClaimController.GRANT_HEADER, required = false) String grant,
               @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        JobFiles.Opened opened = files.open(jobId, claimId, grant, name);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(opened.input().size());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        try (OutputStream out = response.getOutputStream()) {
            opened.input().writeTo(out);
        }
    }

    @PostMapping(path = "/outputs", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "storeJobOutput", summary = "Send a file back for the claimed job, streamed into the"
            + " encrypted file store (403 job.grant_refused, also when the claim ends while it arrives;"
            + " 413 job.output_too_large past the output's or the claim's limit; 400 request.invalid for an empty or"
            + " cut body). Nothing of a refused output remains.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            responses = @ApiResponse(responseCode = "201", description = "Stored",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = JobOutputStored.class))))
    ResponseEntity<JobOutputStored> output(@PathVariable UUID jobId, @PathVariable UUID claimId,
                                           @Parameter(in = ParameterIn.HEADER, name = JobClaimController.GRANT_HEADER,
                                                   required = true, description = "The claim's grant")
                                           @RequestHeader(name = JobClaimController.GRANT_HEADER, required = false)
                                           String grant,
                                           @Parameter(hidden = true) HttpServletRequest request) throws IOException {
        JobFiles.Stored stored = files.store(jobId, claimId, grant, request.getContentLengthLong(),
                request.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new JobOutputStored(stored.fileId(), stored.size(), stored.sha256()));
    }
}
