/**
 * Limits (Story 2.10, AD-18): the one owner of the model setting. It reads the active provider and its model id per
 * tier from the deployment's settings ({@link io.github.gmshield.limits.service.ModelSettings}) and resolves the
 * provider, model and tier of each attempt of a job that calls a model
 * ({@link io.github.gmshield.limits.service.ModelProviders}, the {@code jobs} module's {@code ModelChoices}). Every job
 * uses the full tier until Epic 9, which adds account tiers, usage and prices here. There is no fallback provider.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Limits",
        allowedDependencies = {"shared :: service", "jobs :: service"})
package io.github.gmshield.limits;
