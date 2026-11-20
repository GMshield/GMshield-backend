package io.github.gmshield.limits.service;

import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.ModelChoice;
import io.github.gmshield.jobs.service.ModelChoices;
import org.springframework.stereotype.Service;

/**
 * The model of each attempt of a job that calls one (Story 2.10, AD-18): the active provider, with its model for the
 * job's tier. Every job uses the full tier until Epic 9 sets tiers by account and kind. Asked again at every claim, so a
 * retry after a change of setting (and a restart) follows the new one.
 */
@Service
public class ModelProviders implements ModelChoices {

    private final ModelSettings settings;

    ModelProviders(ModelSettings settings) {
        this.settings = settings;
    }

    @Override
    public ModelChoice resolve(JobRef job) {
        return new ModelChoice(settings.provider(), settings.model(ModelSettings.FULL), ModelSettings.FULL);
    }
}
