package io.github.gmshield.jobs.service;

/**
 * The model one attempt of a job calls (Story 2.10, AD-18): the provider ({@code azure-openai}, {@code anthropic} or
 * {@code openai}), the provider's model id (for Azure OpenAI, the deployment name) and the tier ({@code full} or
 * {@code lightweight}). Resolved again at every claim and recorded on the job row; the worker never chooses one.
 */
public record ModelChoice(String provider, String model, String tier) {
}
