package io.github.gmshield.accounts.service;

/** What Google confirmed: its stable id for the person ({@code sub}) and a verified email address. */
public record GoogleIdentity(String subject, String email) {

    @Override
    public String toString() {
        return "GoogleIdentity[hidden]";
    }
}
