package io.github.gmshield;

import io.github.gmshield.shared.service.StartupSettings;
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
        StartupSettings settings = StartupSettings.check(System.getenv());
        if (settings.refused()) {
            // Printed before Spring and its logging exist: same one-line JSON shape, names only.
            System.out.println(settings.refusalLine());
            System.exit(EXIT_REFUSED);
        }
        SpringApplication application = new SpringApplication(GmshieldApplication.class);
        if (settings.testMode()) {
            application.setAdditionalProfiles(StartupSettings.TEST_MODE_PROFILE);
        }
        application.run(args);
        LoggerFactory.getLogger(GmshieldApplication.class).atInfo()
                .addKeyValue("mode", settings.testMode() ? StartupSettings.TEST_MODE : "production")
                .log("application.started");
    }
}
