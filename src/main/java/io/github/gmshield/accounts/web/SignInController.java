package io.github.gmshield.accounts.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.accounts.service.Sessions;
import io.github.gmshield.accounts.service.SignIn;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in with Google, called by the frontend only: start, the relayed callback, and the username step of a
 * first sign-in. Tokens travel in the JSON bodies and never in a URL; every answer is {@code no-store}.
 */
@RestController
@Tag(name = "sign-in")
class SignInController {

    static final String START = "/api/v1/sign-in/google";
    static final String CALLBACK = "/api/v1/sign-in/google/callback";
    static final String COMPLETE = "/api/v1/sign-in/complete";

    private final SignIn signIn;

    SignInController(SignIn signIn) {
        this.signIn = signIn;
    }

    @Schema(name = "SignInStarted", requiredProperties = {"attemptToken", "authorizationUrl", "expiresInSeconds"})
    record SignInStarted(
            @Schema(description = "The attempt token: the frontend keeps it in a cookie and sends it with the callback",
                    example = "example-attempt-token") String attemptToken,
            @Schema(description = "Where the browser goes next: Google's authorization endpoint, or in test mode the"
                    + " frontend's own callback (a path)",
                    example = "/auth/google/callback?state=example&code=stand-in.1.gm%40example.test") String authorizationUrl,
            @Schema(description = "Lifetime of the attempt", example = "600") long expiresInSeconds) {
    }

    @Schema(name = "GoogleCallback", requiredProperties = "attemptToken")
    record GoogleCallback(
            @Schema(description = "The attempt token of the visitor's cookie") String attemptToken,
            @Schema(description = "The state parameter of Google's redirect") String state,
            @Schema(description = "The code parameter of Google's redirect") String code,
            @Schema(description = "The error parameter of Google's redirect, when Google refused") String error) {
    }

    @Schema(name = "SessionGrant", requiredProperties = {"sessionToken", "expiresInSeconds", "renewAfterSeconds"})
    record SessionGrant(
            @Schema(description = "The session token: the frontend keeps it in a cookie and sends it as X-Session-Token",
                    example = "example-session-token") String sessionToken,
            @Schema(description = "What is left of the session's absolute lifetime", example = "604800")
            long expiresInSeconds,
            @Schema(description = "After this many seconds the frontend renews the token (POST /api/v1/session/renewal)",
                    example = "900") long renewAfterSeconds) {

        static SessionGrant of(Sessions.Grant grant) {
            return new SessionGrant(grant.token(), grant.expiresIn().toSeconds(), grant.renewAfter().toSeconds());
        }

        @Override
        public String toString() {
            return "SessionGrant[expiresInSeconds=" + expiresInSeconds + ", renewAfterSeconds=" + renewAfterSeconds + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "SignInOutcome", requiredProperties = "outcome")
    record SignInOutcome(
            @Schema(description = "signed_in: a known account, with its session; username_required: a first sign-in,"
                    + " whose username step comes next", allowableValues = {"signed_in", "username_required"},
                    example = "signed_in") String outcome,
            @Schema(description = "Present when signed in") SessionGrant session) {
    }

    @Schema(name = "CompleteSignIn", requiredProperties = {"attemptToken", "username", "termsAccepted"})
    record CompleteSignIn(
            @Schema(description = "The attempt token of the visitor's cookie") String attemptToken,
            @Schema(description = "3 to 24 characters: letters a to z in upper or lower case, digits, \"_\", \".\" and"
                    + " \"-\"; unique whatever the case, shown as typed", example = "mestre_ana") String username,
            @Schema(description = "The visitor accepted the terms of use and the privacy notice") Boolean termsAccepted,
            @Schema(description = "The interface's language: the new account's first system and campaign are named in"
                    + " it. Optional, pt-BR when absent", allowableValues = {"pt-BR", "en"}, defaultValue = "pt-BR",
                    example = "pt-BR") String language) {
    }

    @PostMapping(path = START, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "startGoogleSignIn", summary = "Start a sign-in with Google: a 10-minute attempt")
    ResponseEntity<SignInStarted> start() {
        SignIn.Started started = signIn.start();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new SignInStarted(started.attemptToken(), started.authorizationUrl(), started.lifetime().toSeconds()));
    }

    @PostMapping(path = CALLBACK, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "finishGoogleSignIn",
            summary = "Google's redirect, relayed with the attempt token: a session, or the username step next")
    ResponseEntity<SignInOutcome> callback(@RequestBody GoogleCallback request) {
        SignIn.Outcome outcome = signIn.callback(request.attemptToken(), request.state(), request.code(), request.error());
        SignInOutcome body = outcome.session()
                .map(grant -> new SignInOutcome("signed_in", SessionGrant.of(grant)))
                .orElseGet(() -> new SignInOutcome("username_required", null));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping(path = COMPLETE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "completeSignIn",
            summary = "The username step of a first sign-in: creates the account and starts a session")
    ResponseEntity<SessionGrant> complete(@RequestBody CompleteSignIn request) {
        Language language = request.language() == null ? Language.DEFAULT : Language.fromTag(request.language())
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(400)));
        Sessions.Grant grant = signIn.complete(request.attemptToken(), request.username(),
                Boolean.TRUE.equals(request.termsAccepted()), language);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SessionGrant.of(grant));
    }
}
