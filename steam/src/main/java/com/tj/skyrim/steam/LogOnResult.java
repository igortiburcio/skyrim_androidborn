/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

/** Result of a successful CM logon. */
public final class LogOnResult {
    private final long steamId;
    private final int sessionId;
    private final int heartbeatSeconds;
    private final int cellId;
    private final String accountName;

    LogOnResult(long steamId, int sessionId, int heartbeatSeconds, int cellId, String accountName) {
        this.steamId = steamId;
        this.sessionId = sessionId;
        this.heartbeatSeconds = heartbeatSeconds;
        this.cellId = cellId;
        this.accountName = accountName;
    }

    public long steamId() {
        return steamId;
    }

    public int sessionId() {
        return sessionId;
    }

    public int heartbeatSeconds() {
        return heartbeatSeconds;
    }

    public int cellId() {
        return cellId;
    }

    public String accountName() {
        return accountName;
    }
}
