package io.github.gmshield.accounts.web;

import io.github.gmshield.accounts.service.Account;
import io.github.gmshield.accounts.service.Sessions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in account, renewal and sign-out. The session token arrives in {@code X-Session-Token}, never a
 * cookie. Only sign-in and renewal issue a token: an ordinary call never replaces one.
 */
@RestController
@Tag(name = "session")
class SessionController {

    static final String HEADER = "X-Session-Token";
    static final String ME = "/api/v1/me";
    static final String SESSION = "/api/v1/session";
    static final String RENEWAL = "/api/v1/session/renewal";

    private final Sessions sessions;

    SessionController(Sessions sessions) {
        this.sessions = sessions;
    }

    @Schema(name = "Me", requiredProperties = {"id", "username"})
    record Me(
            @Schema(description = "The account's id: the browser keys this account's pending rolls with it (Story 6.4)",
                    example = "01890a5d-ac96-774b-bcce-b302099a8057") UUID id,
            @Schema(description = "The username, as typed", example = "mestre_ana") String username) {
    }

    @GetMapping(path = ME, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMe", summary = "The signed-in account; session.required (401) without a session,"
            + " session.renewal_required (401) when the token must be renewed first")
    ResponseEntity<Me> me(@RequestHeader(name = HEADER, required = false) String sessionToken) {
        Account account = sessions.account(sessionToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Me(account.id(), account.username()));
    }

    @PostMapping(path = RENEWAL, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renewSession", summary = "Renew the session: a new token, the presented one kept for"
            + " the overlap. session.already_renewed (409) with the previous token within the overlap; session.required"
            + " (401) when the session is over")
    ResponseEntity<SignInController.SessionGrant> renew(@RequestHeader(name = HEADER, required = false) String sessionToken) {
        Sessions.Grant grant = sessions.renew(sessionToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SignInController.SessionGrant.of(grant));
    }

    @DeleteMapping(path = SESSION)
    @Operation(operationId = "endSession", summary = "Sign out: ends the session of the token, if it exists",
            responses = @ApiResponse(responseCode = "204", description = "The session is over"))
    ResponseEntity<Void> end(@RequestHeader(name = HEADER, required = false) String sessionToken) {
        sessions.end(sessionToken);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
