package io.github.gmshield.limits.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The model setting (Story 2.10), Spring properties that the environment overrides ({@code application.yaml}): the
 * active provider ({@code GMSHIELD_MODEL_PROVIDER}: {@code azure-openai}, the default, {@code anthropic} or
 * {@code openai}) and each provider's model id per tier ({@code GMSHIELD_MODEL_<AZURE_OPENAI|ANTHROPIC|OPENAI>_<FULL|
 * LIGHTWEIGHT>}). Azure OpenAI's ids are deployment names ({@code gpt-6.1-terra} for both tiers by default); Anthropic's
 * default to {@code claude-sonnet-5-5} and {@code claude-haiku-5-5}; OpenAI has none, so it cannot be selected until
 * both are set. An unknown provider, or a missing or invalid id of the active provider, stops the start with
 * {@code setting.invalid}, naming the settings only. The other providers' ids are not checked: they are not used.
 */
@Component
public class ModelSettings {

    public static final String AZURE_OPENAI = "azure-openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String OPENAI = "openai";
    public static final String FULL = "full";
    public static final String LIGHTWEIGHT = "lightweight";
    static final String PROVIDER_SETTING = "GMSHIELD_MODEL_PROVIDER";
    /** A model id or Azure deployment name: letters, digits, dot, underscore, colon and hyphen, at most 128. */
    static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final String provider;
    private final String fullModel;
    private final String lightweightModel;

    ModelSettings(@Value("${gmshield.models.provider}") String provider,
                  @Value("${gmshield.models.azure-openai.full}") String azureFull,
                  @Value("${gmshield.models.azure-openai.lightweight}") String azureLightweight,
                  @Value("${gmshield.models.anthropic.full}") String anthropicFull,
                  @Value("${gmshield.models.anthropic.lightweight}") String anthropicLightweight,
                  @Value("${gmshield.models.openai.full}") String openaiFull,
                  @Value("${gmshield.models.openai.lightweight}") String openaiLightweight) {
        Map<String, List<String>> ids = Map.of(
                AZURE_OPENAI, List.of(nonNull(azureFull), nonNull(azureLightweight)),
                ANTHROPIC, List.of(nonNull(anthropicFull), nonNull(anthropicLightweight)),
                OPENAI, List.of(nonNull(openaiFull), nonNull(openaiLightweight)));
        String active = provider == null ? "" : provider.strip();
        List<String> models = ids.get(active);
        if (models == null) {
            throw new IllegalStateException("setting.invalid: " + PROVIDER_SETTING);
        }
        String prefix = "GMSHIELD_MODEL_" + active.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
        List<String> invalid = new ArrayList<>();
        if (!MODEL_ID.matcher(models.get(0).strip()).matches()) {
            invalid.add(prefix + "FULL");
        }
        if (!MODEL_ID.matcher(models.get(1).strip()).matches()) {
            invalid.add(prefix + "LIGHTWEIGHT");
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("setting.invalid: " + String.join(", ", invalid));
        }
        this.provider = active;
        this.fullModel = models.get(0).strip();
        this.lightweightModel = models.get(1).strip();
    }

    /** The active provider. */
    public String provider() {
        return provider;
    }

    /** The active provider's model id for {@code tier} ({@link #FULL} or {@link #LIGHTWEIGHT}). */
    public String model(String tier) {
        return switch (tier) {
            case FULL -> fullModel;
            case LIGHTWEIGHT -> lightweightModel;
            default -> throw new IllegalArgumentException("limits.tier_unknown");
        };
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
