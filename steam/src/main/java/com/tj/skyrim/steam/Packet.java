/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import steam.proto.SteammessagesBase.CMsgProtoBufHeader;

/**
 * One CM message on the WebSocket: the EMsg (with the 0x80000000 protobuf
 * flag), a length-prefixed {@link CMsgProtoBufHeader}, then the body. This is
 * the same layout SteamKit2's {@code MsgHdrProtoBuf} writes; the WebSocket
 * frame delimits the message, so there is no outer length.
 */
final class Packet {
    final int emsg;
    final CMsgProtoBufHeader header;
    final byte[] body;

    Packet(int emsg, CMsgProtoBufHeader header, byte[] body) {
        this.emsg = emsg;
        this.header = header;
        this.body = body;
    }

    static Packet decode(byte[] data) throws SteamException {
        if (data.length < 8) {
            throw new SteamException("short CM packet (" + data.length + " bytes)");
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int raw = bb.getInt();
        boolean proto = (raw & Msg.PROTO_MASK) != 0;
        int emsg = raw & Msg.EMSG_MASK;
        if (!proto) {
            throw new SteamException("non-protobuf CM packet " + emsg);
        }
        int headerLength = bb.getInt();
        if (headerLength < 0 || 8L + headerLength > data.length) {
            throw new SteamException("bad CM header length " + headerLength);
        }
        CMsgProtoBufHeader header;
        try {
            CodedInputStream in = CodedInputStream.newInstance(data, 8, headerLength);
            header = CMsgProtoBufHeader.parseFrom(in);
        } catch (IOException e) {
            throw new SteamException("cannot parse CM header", e);
        }
        byte[] body = new byte[data.length - 8 - headerLength];
        System.arraycopy(data, 8 + headerLength, body, 0, body.length);
        return new Packet(emsg, header, body);
    }

    byte[] encode() {
        byte[] head = header.toByteArray();
        ByteBuffer bb = ByteBuffer.allocate(8 + head.length + body.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(emsg | Msg.PROTO_MASK);
        bb.putInt(head.length);
        bb.put(head);
        bb.put(body);
        return bb.array();
    }

    @Override
    public String toString() {
        return "Packet{emsg=" + emsg + ", job=" + header.getJobidTarget()
                + ", result=" + header.getEresult() + ", body=" + body.length + "B}";
    }
}
