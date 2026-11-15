package io.github.gmshield.shared.service;

/**
 * The version of each OpenAPI document (openapi/public.json and openapi/internal.json). Raise one
 * together with its document: the frontend and the worker compare it with the version their
 * generated types came from, and report unhealthy against an older backend.
 */
public final class ContractVersions {

    public static final String PUBLIC_API = "1.0.0";
    public static final String INTERNAL_API = "1.0.0";

    private ContractVersions() {
    }
}
