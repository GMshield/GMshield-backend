package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Outside test mode, Redis is reached with its own account over TLS verified against the private
 * authority's certificate (Story 1.6); without that certificate the backend does not start.
 */
class ProductionConnectionSettingsTest {

    private static final String CA_FILE = "file:/run/gmshield/certs/ca.crt";

    @Test
    void redisUsesItsOwnAccount() throws IOException {
        PropertySource<?> yaml = load("application.yaml");

        assertThat(yaml.getProperty("spring.data.redis.username")).hasToString("${GMSHIELD_REDIS_USERNAME}");
        assertThat(yaml.getProperty("spring.data.redis.password")).hasToString("${GMSHIELD_REDIS_PASSWORD}");
    }

    @Test
    void theRedisTlsProfileVerifiesAgainstThePrivateAuthority() throws IOException {
        PropertySource<?> yaml = load("application-redis-tls.yaml");

        assertThat(yaml.getProperty("spring.data.redis.ssl.enabled")).hasToString("true");
        assertThat(yaml.getProperty("spring.data.redis.ssl.bundle")).hasToString("gmshield-ca");
        assertThat(yaml.getProperty("spring.ssl.bundle.pem.gmshield-ca.truststore.certificate")).hasToString(CA_FILE);
    }

    @Test
    void testModeKeepsPlainConnections() throws IOException {
        PropertySource<?> yaml = load("application-test-mode.yaml");

        assertThat(yaml.getProperty("spring.data.redis.ssl.enabled")).hasToString("false");
        assertThat(yaml.getProperty("spring.datasource.url")).hasToString("jdbc:postgresql://postgres:5432/gmshield");
    }

    @Test
    void withoutTheAuthorityCertificateTheBackendDoesNotStart() {
        // Unit tests run where /run/gmshield/certs does not exist: the bundle must refuse, never fall back.
        assertThatThrownBy(() -> new SpringApplicationBuilder(GmshieldApplication.class)
                        .profiles("unit", "redis-tls")
                        .properties("server.port=0")
                        .run()
                        .close())
                .hasStackTraceContaining("ca.crt");
    }

    private static PropertySource<?> load(String name) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(name, new ClassPathResource(name));
        assertThat(sources).hasSize(1);
        return sources.getFirst();
    }
}
