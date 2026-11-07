/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Resolves the CM WebSocket endpoints through ISteamDirectory. */
final class CMList {
    private static final String URL =
            "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/"
                    + "?cellid=0&maxcount=20";

    static List<String> fetch(OkHttpClient http) throws IOException, SteamException {
        Request request = new Request.Builder()
                .url(URL)
                .header("User-Agent", "SkyrimAndroidborn/0.2")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new SteamException("CM list HTTP " + response.code());
            }
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            // The Web API wraps the payload in {"response": {...}}.
            JsonObject payload = root.has("response") && root.get("response").isJsonObject()
                    ? root.getAsJsonObject("response") : root;
            if (!payload.has("success") || !payload.get("success").getAsBoolean()) {
                throw new SteamException("Steam returned no CM list");
            }
            List<String> out = new ArrayList<>();
            // Current API: objects with endpoint/type. Older replies used a
            // plain array of "host:port" strings.
            JsonArray list = payload.has("serverlist") ? payload.getAsJsonArray("serverlist") : null;
            if (list != null) {
                for (JsonElement element : list) {
                    if (element.isJsonObject()) {
                        JsonObject server = element.getAsJsonObject();
                        String type = server.has("type") ? server.get("type").getAsString() : "";
                        if ("websockets".equals(type) && server.has("endpoint")) {
                            out.add("wss://" + server.get("endpoint").getAsString() + "/cmsocket/");
                        }
                    } else if (element.isJsonPrimitive()) {
                        out.add("wss://" + element.getAsString() + "/cmsocket/");
                    }
                }
            }
            JsonArray legacy = payload.has("serverlist_websockets")
                    ? payload.getAsJsonArray("serverlist_websockets") : null;
            if (legacy != null) {
                for (JsonElement element : legacy) {
                    out.add("wss://" + element.getAsString() + "/cmsocket/");
                }
            }
            if (out.isEmpty()) {
                throw new SteamException("Steam returned no WebSocket CM servers");
            }
            return out;
        }
    }

    private CMList() {
    }
}
