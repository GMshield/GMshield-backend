package io.github.gmshield.accounts.service;

/**
 * Google's side of the sign-in (OIDC code flow with PKCE, scopes {@code openid email}). Outside test mode
 * it is Google itself ({@link GoogleOidcClient}); in test mode a stand-in ({@link GoogleStandIn}).
 */
public interface GoogleSignIn {

    /** Where the browser goes to sign in; it comes back to the frontend's callback with the state and a code. */
    String authorizationUrl(String state, String nonce, String codeChallenge);

    /**
     * Exchanges the code for a checked identity, or throws the problem {@code request.sign_in_refused}
     * ({@link AccountProblems#SIGN_IN_REFUSED}). Never logs the code, a token, or the identity.
     */
    GoogleIdentity exchange(String code, String codeVerifier, String nonce);
}
