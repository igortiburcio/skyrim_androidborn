package com.tj.skyrim.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Looks up the latest GitHub release and compares it with the installed
 * version. The request runs on its own thread; the callback always arrives on
 * the main thread and is called with null when this build is current or when
 * the check fails (offline, rate limit, malformed response), so the launcher
 * never blocks or complains about a check it could not finish.
 */
public final class UpdateChecker {

    public static final String RELEASE_API =
            "https://api.github.com/repos/igortiburcio/skyrim_androidborn/releases/latest";
    private static final String USER_AGENT = "Skyrim-Androidborn";

    /** One release: version without the leading "v", notes and the APK asset. */
    public static final class Release {
        public final String version;
        public final String htmlUrl;
        public final String notes;
        public final String apkUrl;
        public final long apkSize;
        public final String sha256;   /* empty when GitHub did not send a digest */

        Release(String version, String htmlUrl, String notes,
                String apkUrl, long apkSize, String sha256) {
            this.version = version;
            this.htmlUrl = htmlUrl;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
            this.sha256 = sha256;
        }
    }

    public interface Callback {
        /** The newer release, or null when there is none or the check failed. */
        void onResult(Release release);
    }

    private UpdateChecker() {
    }

    /**
     * Checks in the background. {@code force} is only used by debug builds to
     * try the update flow when the installed version already matches GitHub.
     */
    public static void check(Context context, boolean force, Callback cb) {
        final String current = versionName(context);
        final Handler main = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            Release release = null;
            try {
                release = fetch(current, force);
            } catch (Throwable ignored) {
                /* offline, rate limited, no asset: stay quiet */
            }
            final Release result = release;
            main.post(() -> cb.onResult(result));
        }, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static Release fetch(String current, boolean force) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
        try {
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (connection.getResponseCode() != 200) return null;

            InputStream in = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            JSONObject json = new JSONObject(read(in));
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null;

            String version = stripV(json.optString("tag_name", ""));
            if (!force && !isNewer(version, current)) return null;

            String apkUrl = "";
            long apkSize = 0;
            String sha256 = "";
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    String url = asset.optString("browser_download_url", "");
                    if (!name.endsWith(".apk") || !trustedUrl(url)) continue;
                    apkUrl = url;
                    apkSize = asset.optLong("size", 0);
                    String digest = asset.optString("digest", "");
                    if (digest.startsWith("sha256:")) sha256 = digest.substring(7);
                    break;
                }
            }

            return new Release(version, json.optString("html_url", ""),
                    json.optString("body", "").trim(), apkUrl, apkSize, sha256);
        } finally {
            connection.disconnect();
        }
    }

    /** "v0.1.6" becomes "0.1.6"; "0.1.5-dual" becomes "0.1.5". */
    static String stripV(String tag) {
        String version = tag == null ? "" : tag.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        int dash = version.indexOf('-');
        return dash >= 0 ? version.substring(0, dash) : version;
    }

    /** Numeric compare, so 0.1.10 is newer than 0.1.9. */
    static boolean isNewer(String remote, String local) {
        int[] a = parts(remote);
        int[] b = parts(local);
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        String[] bits = version.split("[^0-9]+");
        int count = 0;
        for (String bit : bits) if (!bit.isEmpty()) count++;
        int[] out = new int[count];
        int i = 0;
        for (String bit : bits) if (!bit.isEmpty()) out[i++] = parseInt(bit);
        return out;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The APK link must stay on GitHub, never on a host we do not know. */
    private static boolean trustedUrl(String url) {
        try {
            String host = new URL(url).getHost();
            return host != null && (host.equals("github.com")
                    || host.endsWith(".github.com")
                    || host.equals("githubusercontent.com")
                    || host.endsWith(".githubusercontent.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String versionName(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) > 0) out.append(buffer, 0, count);
        }
        return out.toString();
    }
}
