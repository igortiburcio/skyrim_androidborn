/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

/** A pending Steam QR login. The URL can be refreshed while polling. */
public final class QrSession {
    private volatile String challengeUrl;
    private volatile long clientId;
    private final byte[] requestId;
    private final double intervalSeconds;

    QrSession(long clientId, String challengeUrl, byte[] requestId, double intervalSeconds) {
        this.clientId = clientId;
        this.challengeUrl = challengeUrl;
        this.requestId = requestId;
        this.intervalSeconds = intervalSeconds > 0 ? intervalSeconds : 2.0;
    }

    /** The {@code https://s.team/q/...} URL to draw as a QR code. */
    public String challengeUrl() {
        return challengeUrl;
    }

    public long clientId() {
        return clientId;
    }

    byte[] requestId() {
        return requestId;
    }

    public double intervalSeconds() {
        return intervalSeconds;
    }

    void update(long newClientId, String newChallengeUrl) {
        if (newClientId != 0) {
            clientId = newClientId;
        }
        if (newChallengeUrl != null && !newChallengeUrl.isEmpty()) {
            challengeUrl = newChallengeUrl;
        }
    }
}
