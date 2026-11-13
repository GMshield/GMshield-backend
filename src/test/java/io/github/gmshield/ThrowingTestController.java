package io.github.gmshield;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test only: a handler that fails with a message no client may ever see. Outside both APIs. */
@RestController
public class ThrowingTestController {

    public static final String SECRET_MESSAGE = "select * from accounts where secret = 'do-not-leak'";

    @GetMapping("/test-only/unexpected")
    String unexpected() {
        throw new IllegalStateException(SECRET_MESSAGE);
    }
}
