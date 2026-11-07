package com.tj.skyrim.steam;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import steam.proto.SteammessagesBase.CMsgProtoBufHeader;

public class PacketTest {
    @Test
    public void roundTrip() throws Exception {
        byte[] body = new byte[] {1, 2, 3, 4};
        CMsgProtoBufHeader header = CMsgProtoBufHeader.newBuilder()
                .setJobidSource(42L)
                .setJobidTarget(43L)
                .setTargetJobName("Authentication.BeginAuthSessionViaQR#1")
                .setEresult(1)
                .build();
        Packet sent = new Packet(Msg.SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED, header, body);
        Packet got = Packet.decode(sent.encode());
        assertEquals(Msg.SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED, got.emsg);
        assertEquals(42L, got.header.getJobidSource());
        assertEquals(43L, got.header.getJobidTarget());
        assertEquals("Authentication.BeginAuthSessionViaQR#1", got.header.getTargetJobName());
        assertArrayEquals(body, got.body);
    }
}
