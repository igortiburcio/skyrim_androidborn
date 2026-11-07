/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import steam.proto.SteammessagesContentsystemSteamclient.CContentServerDirectory_ServerInfo;

/** Steam content CDN: manifest ZIPs and encrypted depot chunks over HTTPS. */
final class CdnClient {
    private static final String USER_AGENT = "SkyrimAndroidborn/0.2";
    private final OkHttpClient http;

    CdnClient(OkHttpClient http) {
        this.http = http;
    }

    /** Hosts usable for plain HTTPS GETs, best-effort ordered. */
    static List<String> hosts(List<CContentServerDirectory_ServerInfo> servers) throws SteamException {
        List<String> out = new ArrayList<>();
        for (CContentServerDirectory_ServerInfo server : servers) {
            if (server.getUseAsProxy() || server.getVhost().isEmpty()) {
                continue;
            }
            if ("none".equalsIgnoreCase(server.getHttpsSupport())) {
                continue;
            }
            if (!out.contains(server.getVhost())) {
                out.add(server.getVhost());
            }
            if (out.size() >= 8) {
                break;
            }
        }
        if (out.isEmpty()) {
            throw new SteamException("Steam returned no usable content server");
        }
        return out;
    }

    DepotManifest fetchManifest(List<String> hosts, int depotId, long manifestId,
                                long requestCode, byte[] depotKey) throws Exception {
        StringBuilder path = new StringBuilder("depot/").append(depotId)
                .append("/manifest/").append(Long.toUnsignedString(manifestId)).append("/5");
        if (requestCode != 0) {
            path.append('/').append(Long.toUnsignedString(requestCode));
        }
        byte[] zip = get(hosts, path.toString());
        return DepotManifest.parseZip(zip).withDecryptedNames(depotKey);
    }

    byte[] fetchChunk(List<String> hosts, int depotId, byte[] sha, int compressedLength)
            throws Exception {
        String path = "depot/" + depotId + "/chunk/" + hex(sha);
        byte[] data = get(hosts, path);
        if (compressedLength > 0 && data.length != compressedLength) {
            throw new SteamException("chunk " + hex(sha) + " is " + data.length
                    + " bytes, expected " + compressedLength);
        }
        return data;
    }

    private byte[] get(List<String> hosts, String path) throws Exception {
        Exception last = null;
        for (String host : hosts) {
            Request request = new Request.Builder()
                    .url("https://" + host + "/" + path)
                    .header("User-Agent", USER_AGENT)
                    .build();
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new SteamException("CDN " + host + " HTTP " + response.code());
                }
                return response.body().bytes();
            } catch (Exception e) {
                last = e;
            }
        }
        throw new SteamException("all content servers failed for " + path, last);
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }
}
