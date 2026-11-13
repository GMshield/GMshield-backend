package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test mode's built-in connection settings, as documented in README.md ("Settings"). They resolve
 * with no GMSHIELD_* variable set, because test mode reads none.
 */
@SpringBootTest
@ActiveProfiles({"test-mode", "unit"})
class TestModeSettingsTest {

    @Autowired
    private Environment environment;

    @Test
    void connectionSettingsAreTheDocumentedThrowawayValues() {
        assertThat(System.getenv().keySet()).noneMatch(name -> name.startsWith("GMSHIELD_"));

        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://postgres:5432/gmshield");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("gmshield");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("gmshield-test-mode");
        assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("redis");
        assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6379");
        assertThat(environment.getProperty("spring.data.redis.password")).isEmpty();
    }
}
