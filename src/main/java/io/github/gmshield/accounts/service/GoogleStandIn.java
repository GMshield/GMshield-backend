package io.github.gmshield.accounts.service;

import io.github.gmshield.shared.service.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test mode's Google (AD-28): no internet and no secret. Its authorization URL is the frontend's own
 * callback, relative to the frontend's origin, with the attempt's real state and the code
 * {@value #DEFAULT_CODE}. Any code {@code stand-in.<sub>.<email>} gives that identity, so API tests choose
 * identities freely; any other code is refused as Google would refuse a bad one.
 */
final class GoogleStandIn implements GoogleSignIn {

    static final String CALLBACK_PATH = "/auth/google/callback";
    static final String DEFAULT_CODE = "stand-in.1.gm@example.test";
    private static final Pattern CODE = Pattern.compile("stand-in\\.([A-Za-z0-9_-]{1,64})\\.([^@\\s]{1,64}@[^@\\s]{1,190})");

    @Override
    public String authorizationUrl(String state, String nonce, String codeChallenge) {
        return CALLBACK_PATH + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode(DEFAULT_CODE, StandardCharsets.UTF_8);
    }

    @Override
    public GoogleIdentity exchange(String code, String codeVerifier, String nonce) {
        Matcher matcher = code == null ? null : CODE.matcher(code);
        if (matcher == null || !matcher.matches()) {
            throw new ApiException(AccountProblems.SIGN_IN_REFUSED);
        }
        return new GoogleIdentity(matcher.group(1), matcher.group(2));
    }
}
