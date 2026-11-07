package com.tj.skyrim.launcher;

import com.tj.skyrim.steam.SteamClient;

/**
 * The signed-in Steam client shared by the launcher and the foreground
 * download service. The refresh token itself lives encrypted in
 * {@link SteamTokenStore}; this only holds the live CM session.
 */
public final class SteamSession {
    private static volatile SteamClient client;

    public static SteamClient get() {
        return client;
    }

    public static void set(SteamClient steam) {
        client = steam;
    }

    public static boolean isLoggedOn() {
        SteamClient steam = client;
        return steam != null && steam.isLoggedOn();
    }

    public static void close() {
        SteamClient steam = client;
        client = null;
        if (steam != null) {
            steam.close();
        }
    }

    private SteamSession() {
    }
}
