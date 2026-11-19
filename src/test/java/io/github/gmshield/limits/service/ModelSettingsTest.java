package io.github.gmshield.limits.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.jobs.service.ModelChoice;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The model setting (Story 2.10): each settings row of the matrix, without the rest of the application. */
class ModelSettingsTest {

    private static final JobRef JOB = new JobRef(UUID.randomUUID(), UUID.randomUUID(), "test.model", Lane.INTERACTIVE, 1,
            "0".repeat(31) + "1");

    /** The settings as application.yaml gives them with nothing set. */
    private static ModelSettings defaults(String provider) {
        return new ModelSettings(provider, "gpt-6.1-terra", "gpt-6.1-terra", "claude-sonnet-5-5", "claude-haiku-5-5",
                "", "");
    }

    @Test
    void withNoSettingTheProviderIsAzureOpenAiWithGpt61TerraForBothTiers() {
        new ApplicationContextRunner().withUserConfiguration(ModelSettings.class, ModelProviders.class)
                .withPropertyValues("gmshield.models.provider=${GMSHIELD_MODEL_PROVIDER:azure-openai}",
                        "gmshield.models.azure-openai.full=${GMSHIELD_MODEL_AZURE_OPENAI_FULL:gpt-6.1-terra}",
                        "gmshield.models.azure-openai.lightweight=${GMSHIELD_MODEL_AZURE_OPENAI_LIGHTWEIGHT:gpt-6.1-terra}",
                        "gmshield.models.anthropic.full=${GMSHIELD_MODEL_ANTHROPIC_FULL:claude-sonnet-5-5}",
                        "gmshield.models.anthropic.lightweight=${GMSHIELD_MODEL_ANTHROPIC_LIGHTWEIGHT:claude-haiku-5-5}",
                        "gmshield.models.openai.full=${GMSHIELD_MODEL_OPENAI_FULL:}",
                        "gmshield.models.openai.lightweight=${GMSHIELD_MODEL_OPENAI_LIGHTWEIGHT:}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelSettings settings = context.getBean(ModelSettings.class);
                    assertThat(settings.provider()).isEqualTo("azure-openai");
                    assertThat(settings.model(ModelSettings.FULL)).isEqualTo("gpt-6.1-terra");
                    assertThat(settings.model(ModelSettings.LIGHTWEIGHT)).isEqualTo("gpt-6.1-terra");
                    assertThat(context.getBean(ModelProviders.class).resolve(JOB))
                            .isEqualTo(new ModelChoice("azure-openai", "gpt-6.1-terra", "full"));
                });
    }

    @Test
    void theApplicationsOwnSettingsGiveTheSameDefaults() throws Exception {
        java.util.Properties yaml = new java.util.Properties();
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean factory =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        factory.setResources(new org.springframework.core.io.ClassPathResource("application.yaml"));
        yaml.putAll(factory.getObject());

        assertThat(yaml.getProperty("gmshield.models.provider")).isEqualTo("${GMSHIELD_MODEL_PROVIDER:azure-openai}");
        assertThat(yaml.getProperty("gmshield.models.azure-openai.full"))
                .isEqualTo("${GMSHIELD_MODEL_AZURE_OPENAI_FULL:gpt-6.1-terra}");
        assertThat(yaml.getProperty("gmshield.models.azure-openai.lightweight"))
                .isEqualTo("${GMSHIELD_MODEL_AZURE_OPENAI_LIGHTWEIGHT:gpt-6.1-terra}");
        assertThat(yaml.getProperty("gmshield.models.anthropic.full"))
                .isEqualTo("${GMSHIELD_MODEL_ANTHROPIC_FULL:claude-sonnet-5-5}");
        assertThat(yaml.getProperty("gmshield.models.anthropic.lightweight"))
                .isEqualTo("${GMSHIELD_MODEL_ANTHROPIC_LIGHTWEIGHT:claude-haiku-5-5}");
        assertThat(yaml.getProperty("gmshield.models.openai.full")).isEqualTo("${GMSHIELD_MODEL_OPENAI_FULL:}");
        assertThat(yaml.getProperty("gmshield.models.openai.lightweight")).isEqualTo("${GMSHIELD_MODEL_OPENAI_LIGHTWEIGHT:}");
    }

    @Test
    void anthropicSelectedResolvesItsFullModel() {
        ModelSettings settings = defaults("anthropic");

        assertThat(settings.model(ModelSettings.LIGHTWEIGHT)).isEqualTo("claude-haiku-5-5");
        assertThat(new ModelProviders(settings).resolve(JOB))
                .isEqualTo(new ModelChoice("anthropic", "claude-sonnet-5-5", "full"));
    }

    @Test
    void openAiWithoutModelIdsStopsTheStartNamingOnlyItsSettings() {
        assertThatThrownBy(() -> defaults("openai"))
                .hasMessage("setting.invalid: GMSHIELD_MODEL_OPENAI_FULL, GMSHIELD_MODEL_OPENAI_LIGHTWEIGHT");
        ModelSettings withIds = new ModelSettings("openai", "", "", "", "", "gpt-x", "gpt-x-mini");
        assertThat(new ModelProviders(withIds).resolve(JOB)).isEqualTo(new ModelChoice("openai", "gpt-x", "full"));
    }

    @Test
    void anUnknownProviderStopsTheStart() {
        for (String provider : new String[] {"bedrock", "", " ", "Azure-OpenAI", null}) {
            assertThatThrownBy(() -> defaults(provider)).as(String.valueOf(provider))
                    .hasMessage("setting.invalid: GMSHIELD_MODEL_PROVIDER");
        }
    }

    @Test
    void aMissingOrInvalidIdOfTheActiveProviderStopsTheStartAndAnInactiveOnesDoesNot() {
        assertThatThrownBy(() -> new ModelSettings("azure-openai", "gpt 6", "gpt-6.1-terra", "", "", "", ""))
                .hasMessage("setting.invalid: GMSHIELD_MODEL_AZURE_OPENAI_FULL");
        assertThatThrownBy(() -> new ModelSettings("anthropic", "", "", "claude-sonnet-5-5", "x".repeat(129), "", ""))
                .hasMessage("setting.invalid: GMSHIELD_MODEL_ANTHROPIC_LIGHTWEIGHT");
        assertThatThrownBy(() -> new ModelSettings("azure-openai", null, "-dash", "", "", "", ""))
                .hasMessage("setting.invalid: GMSHIELD_MODEL_AZURE_OPENAI_FULL, GMSHIELD_MODEL_AZURE_OPENAI_LIGHTWEIGHT");
        // Spaces around a value are trimmed; the inactive providers' ids are never read.
        ModelSettings trimmed = new ModelSettings(" azure-openai ", " gpt-6.1-terra ", "mini", "", "bad id", "", "");
        assertThat(trimmed.provider()).isEqualTo("azure-openai");
        assertThat(trimmed.model(ModelSettings.FULL)).isEqualTo("gpt-6.1-terra");
        assertThat(trimmed.model(ModelSettings.LIGHTWEIGHT)).isEqualTo("mini");
        assertThatThrownBy(() -> trimmed.model("other")).isInstanceOf(IllegalArgumentException.class);
    }
}
