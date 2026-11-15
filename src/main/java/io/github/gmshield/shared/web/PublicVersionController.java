package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.ContractVersions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the frontend which version of the public contract this backend serves. */
@RestController
@Tag(name = "contract")
public class PublicVersionController {

    @GetMapping(path = "/api/v1/version", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPublicContractVersion", summary = "The version of the public API contract")
    ContractVersion version() {
        return new ContractVersion(ContractVersions.PUBLIC_API);
    }
}
