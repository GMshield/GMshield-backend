package io.github.gmshield;

import io.github.gmshield.shared.service.StartupSettings;
import java.io.PrintStream;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The GMshield backend: one Spring modular monolith. Each direct sub-package is a module, split
 * into {@code web}, {@code service} and {@code data}; another module may use {@code service} only.
 */
@SpringBootApplication
public class GmshieldApplication {

    /** Exit status when the mode and the settings do not allow a start. */
    static final int EXIT_REFUSED = 78;

    public static void main(String[] args) {
        Optional<SpringApplication> application = prepare(System.getenv(), System.out);
        if (application.isEmpty()) {
            System.exit(EXIT_REFUSED);
        }
        boolean testMode = application.get().getAdditionalProfiles().contains(StartupSettings.TEST_MODE_PROFILE);
        application.get().run(args);
        LoggerFactory.getLogger(GmshieldApplication.class).atInfo()
                .addKeyValue("mode", testMode ? StartupSettings.TEST_MODE : "production")
                .log("application.started");
    }

    /**
     * Decides from the environment whether to start. A refused start prints the refusal line and
     * gives nothing back: Spring is never created. Test mode adds the {@code test-mode} profile;
     * production adds {@code redis-tls} unless the development setup switched it off.
     */
    static Optional<SpringApplication> prepare(Map<String, String> environment, PrintStream out) {
        StartupSettings settings = StartupSettings.check(environment);
        if (settings.refused()) {
            // Printed before Spring and its logging exist: same one-line JSON shape, names only.
            out.println(settings.refusalLine());
            return Optional.empty();
        }
        SpringApplication application = new SpringApplication(GmshieldApplication.class);
        application.setAdditionalProfiles(settings.profiles().toArray(String[]::new));
        return Optional.of(application);
    }
}
