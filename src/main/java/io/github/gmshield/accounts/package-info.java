/**
 * Accounts and sessions: sign-in with Google, run by the backend itself (OIDC code flow with PKCE behind
 * a 10-minute attempt; Story 1.9), sealed accounts whose username, email address and Google id are
 * encrypted through {@code vault}, and Redis sessions that rotate, time out and end on token reuse, which
 * is written to {@code audit} (Story 1.10). A first sign-in creates the account and its starter library
 * ({@code library}) in one transaction, and the library routes take their owner from the session here
 * (Story 1.11). The only module that changes an account's tier, state or credentials (AD-20).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Accounts",
        allowedDependencies = {"shared :: service", "vault :: service", "audit :: service", "library :: service"})
package io.github.gmshield.accounts;
