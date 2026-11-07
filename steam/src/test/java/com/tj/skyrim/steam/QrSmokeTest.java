package com.tj.skyrim.steam;

import static org.junit.Assert.assertNull;

import org.junit.Assume;
import org.junit.Test;

/** Talks to Steam. Opt-in: STEAM_SMOKE=1 ./gradlew :steam:test. */
public class QrSmokeTest {
    @Test
    public void beginAndPollQr() throws Exception {
        Assume.assumeTrue("set STEAM_SMOKE=1 to run the network test",
                System.getenv("STEAM_SMOKE") != null);
        SteamClient client = new SteamClient("Skyrim Androidborn test");
        try {
            client.connect();
            QrSession session = client.beginQrAuth("Skyrim Androidborn test");
            System.out.println("QR URL: " + session.challengeUrl());
            System.out.println("client_id=" + session.clientId()
                    + " interval=" + session.intervalSeconds() + "s");
            // Nobody scanned it yet: the poll must answer "pending".
            assertNull(client.pollQrAuth(session));
        } finally {
            client.close();
        }
    }
}
