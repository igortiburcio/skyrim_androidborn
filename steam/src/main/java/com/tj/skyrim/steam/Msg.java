/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

/** EMsg values and the protobuf flag, from SteamKit2's SteamLanguage.cs. */
final class Msg {
    static final int PROTO_MASK = 0x80000000;
    static final int EMSG_MASK = 0x7FFFFFFF;

    static final int MULTI = 1;
    static final int SERVICE_METHOD = 146;
    static final int SERVICE_METHOD_RESPONSE = 147;
    static final int SERVICE_METHOD_CALL_FROM_CLIENT = 151;
    static final int SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED = 9804;

    static final int CLIENT_HELLO = 9805;
    static final int CLIENT_LOGON = 5514;
    static final int CLIENT_LOGON_RESPONSE = 751;
    static final int CLIENT_HEARTBEAT = 703;
    static final int CLIENT_LOGGED_OFF = 757;

    static final int CLIENT_GET_DEPOT_DECRYPTION_KEY = 5438;
    static final int CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE = 5439;
    static final int CLIENT_PICS_PRODUCT_INFO_REQUEST = 8903;
    static final int CLIENT_PICS_PRODUCT_INFO_RESPONSE = 8904;

    private Msg() {
    }
}
