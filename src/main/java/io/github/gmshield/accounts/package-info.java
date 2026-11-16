/**
 * Accounts and sessions (Story 1.9): sign-in with Google, run by the backend itself (OIDC code flow
 * with PKCE behind a 10-minute attempt), sealed accounts whose username, email address and Google id
 * are encrypted through {@code vault}, and a minimal Redis session that sign-out ends. The only module
 * that changes an account's tier, state or credentials (AD-20).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Accounts",
        allowedDependencies = {"shared :: service", "vault :: service"})
package io.github.gmshield.accounts;
