package io.github.gmshield.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** The version of one API's OpenAPI document. */
@Schema(name = "ContractVersion", requiredProperties = "version")
public record ContractVersion(@Schema(description = "Version of the OpenAPI document", example = "1.0.0") String version) {
}
