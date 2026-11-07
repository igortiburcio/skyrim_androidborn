/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

/** Tokens returned once the Steam mobile app confirms the QR login. */
public final class AuthResult {
    private final String refreshToken;
    private final String accessToken;
    private final String accountName;

    AuthResult(String refreshToken, String accessToken, String accountName) {
        this.refreshToken = refreshToken;
        this.accessToken = accessToken;
        this.accountName = accountName;
    }

    /** Long-lived token; store it encrypted and never log it. */
    public String refreshToken() {
        return refreshToken;
    }

    /** Short-lived token used for the CM logon. */
    public String accessToken() {
        return accessToken;
    }

    public String accountName() {
        return accountName;
    }
}
