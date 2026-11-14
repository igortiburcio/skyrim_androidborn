/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/**
 * Installs the PC game data from the Steam depots into a folder. It follows
 * DepotDownloader: PICS lists the depots, the client fetches each depot key and
 * manifest request code, the CDN serves the manifest and its encrypted chunks,
 * and complete files are verified and skipped (the port only needs Data/).
 */
public final class SteamDownloader {
    public interface Listener {
        void onPlan(int depots, int files, long bytes);

        void onDepot(String message);

        void onProgress(long done, long total, String file);

        void onDone();

        void onError(Throwable error);
    }

    public static final int SKYRIM_APP_ID = 72850;

    private final SteamClient client;
    private final OkHttpClient http;
    private final CdnClient cdn;
    private volatile boolean cancelled;
    private volatile boolean dataOnly = true;
    private volatile boolean verifyExisting = true;

    public SteamDownloader(SteamClient client) {
        this.client = client;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        this.cdn = new CdnClient(http);
    }

    public void cancel() {
        cancelled = true;
    }

    /** Only Data/ is written by default; the port does not need the exe/DLLs. */
    public void dataOnly(boolean value) {
        dataOnly = value;
    }

    public void verifyExisting(boolean value) {
        verifyExisting = value;
    }

    /** Blocks; run it on a worker thread. */
    public void download(int appId, File target, Listener listener) {
        try {
            List<DepotPlan> plan = plan(appId, target);
            listener.onDepot(plan.size() + " depots in the public branch");
            List<String> hosts = CdnClient.hosts(client.cdnServers(20));
            listener.onDepot("content server " + hosts.get(0));

            List<PreparedDepot> prepared = new ArrayList<>();
            int files = 0;
            long total = 0;
            long missing = 0;
            for (DepotPlan depot : plan) {
                checkCancelled();
                byte[] key;
                try {
                    key = client.depotKey(appId, depot.id);
                } catch (SteamException e) {
                    if (!ownedFailure(e)) {
                        throw e;
                    }
                    listener.onDepot("depot " + depot.id + " is not owned; skipped");
                    continue;
                }
                long code;
                DepotManifest manifest;
                try {
                    code = client.manifestRequestCode(appId, depot.id, depot.manifestId);
                    manifest = cdn.fetchManifest(
                            hosts, depot.id, depot.manifestId, code, key);
                } catch (SteamException e) {
                    if (!ownedFailure(e)) {
                        throw e;
                    }
                    listener.onDepot("depot " + depot.id + " is not owned; skipped");
                    continue;
                }
                List<DepotManifest.FileEntry> selected = select(manifest.files);
                if (selected.isEmpty()) {
                    continue;
                }
                prepared.add(new PreparedDepot(depot.id, key, selected));
                files += selected.size();
                for (DepotManifest.FileEntry file : selected) {
                    total += file.size;
                    File out = resolve(target, file.name);
                    if (!out.isFile() || out.length() != file.size) {
                        missing += file.size;
                    }
                }
                listener.onDepot("depot " + depot.id + ": " + selected.size() + " files");
            }
            listener.onPlan(prepared.size(), files, total);
            if (files == 0) {
                throw new SteamException("no Data files were selected from the manifests; "
                        + "the depot filenames could not be read");
            }
            if (missing > 0 && target.getUsableSpace() < missing + (256L << 20)) {
                throw new SteamException("not enough free space: need "
                        + ((missing >> 20) + 256) + " MB");
            }

            long done = 0;
            for (PreparedDepot depot : prepared) {
                for (DepotManifest.FileEntry file : depot.files) {
                    checkCancelled();
                    File out = resolve(target, file.name);
                    if (verifyExisting) {
                        listener.onProgress(done, total, file.name + " (checking)");
                        if (isComplete(out, file)) {
                            done += file.size;
                            listener.onProgress(done, total, file.name);
                            continue;
                        }
                    }
                    downloadFile(hosts, depot, file, out, listener, done, total);
                    done += file.size;
                    listener.onProgress(done, total, file.name);
                }
            }
            listener.onDone();
        } catch (Throwable error) {
            if (!cancelled) {
                listener.onError(error);
            }
        }
    }

    /** Reads PICS and returns the public-branch depots for the app. */
    public List<String> describeDepots(int appId) throws Exception {
        List<String> out = new ArrayList<>();
        for (DepotPlan depot : plan(appId, null)) {
            out.add(depot.id + " -> manifest " + Long.toUnsignedString(depot.manifestId));
        }
        return out;
    }

    private void checkCancelled() throws SteamException {
        if (cancelled) {
            throw new SteamException("download cancelled");
        }
    }

    /** EResult 15/2/9 means the account does not own that optional depot. */
    private static boolean ownedFailure(SteamException e) {
        return e.eresult() == 15 || e.eresult() == 2 || e.eresult() == 9;
    }

    private List<DepotPlan> plan(int appId, File target) throws Exception {
        byte[] buffer = client.productInfo(appId);
        try {
            Map<String, Object> root = KeyValues.parse(buffer);
            // Text PICS is "appinfo" { ... }; binary PICS has the keys at root.
            Map<String, Object> appinfo = KeyValues.map(root.get("appinfo"));
            if (appinfo.isEmpty()) {
                appinfo = root;
            }
            Map<String, Object> depots = KeyValues.map(appinfo.get("depots"));
            List<DepotPlan> out = new ArrayList<>();
            for (Map.Entry<String, Object> entry : depots.entrySet()) {
                if (!entry.getKey().matches("\\d+")) {
                    continue;
                }
                int id = Integer.parseInt(entry.getKey());
                Map<String, Object> depot = KeyValues.map(entry.getValue());
                Map<String, Object> manifests = KeyValues.map(depot.get("manifests"));
                Map<String, Object> pub = KeyValues.map(manifests.get("public"));
                long manifestId = KeyValues.number(pub.get("gid"));
                if (manifestId == 0) {
                    continue;
                }
                Map<String, Object> config = KeyValues.map(depot.get("config"));
                String os = KeyValues.string(config.get("oslist")).toLowerCase(Locale.ROOT);
                if (!os.isEmpty() && !os.contains("windows")) {
                    continue;
                }
                String language = KeyValues.string(config.get("language")).toLowerCase(Locale.ROOT);
                if (!language.isEmpty() && !language.startsWith("english")
                        && !language.equals("en")) {
                    continue;
                }
                if (KeyValues.bool(config.get("sharedinstall"))) {
                    continue;
                }
                // The optional High Resolution Texture Pack is a separate
                // 4.7 GB DLC; the port runs with the base textures.
                if ("202485".equals(KeyValues.string(depot.get("dlcappid")))) {
                    continue;
                }
                out.add(new DepotPlan(id, manifestId));
            }
            return out;
        } catch (SteamException e) {
            if (target != null) {
                try {
                    if (target.isDirectory() || target.mkdirs()) {
                        File dump = new File(target, "appinfo.bin");
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(dump)) {
                            out.write(buffer);
                        }
                        throw new SteamException(e.getMessage()
                                + " [appinfo.bin saved to " + dump + "]", e);
                    }
                } catch (IOException ignored) {
                    // keep the original parse error
                }
            }
            throw e;
        }
    }

    private List<DepotManifest.FileEntry> select(List<DepotManifest.FileEntry> files) {
        List<DepotManifest.FileEntry> out = new ArrayList<>();
        for (DepotManifest.FileEntry file : files) {
            if (file.isDirectory() || file.isSymlink()) {
                continue;
            }
            if ((file.flags & DepotManifest.FLAG_INSTALL_SCRIPT) != 0) {
                continue;
            }
            if (dataOnly && !file.name.toLowerCase(Locale.ROOT).startsWith("data/")) {
                continue;
            }
            out.add(file);
        }
        return out;
    }

    private void downloadFile(List<String> hosts, PreparedDepot depot,
                              DepotManifest.FileEntry file, File out, Listener listener,
                              long doneBase, long total) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new SteamException("cannot create folder " + parent);
        }
        File part = new File(parent, out.getName() + ".part");
        File state = new File(parent, out.getName() + ".part.state");
        /* Resume: the chunks already written are listed in the state file, so
         * an interrupted 8.4 GB download continues where it stopped instead of
         * starting the file over. */
        Set<Long> written = readState(state, file);
        boolean fresh = !(part.isFile() && part.length() == file.size && !written.isEmpty());
        if (fresh) {
            written.clear();
            deleteQuietly(state);
            if (part.exists() && !part.delete()) {
                throw new SteamException("cannot replace " + part);
            }
        }
        long remaining = 0;
        for (DepotManifest.Chunk chunk : file.chunks) {
            if (!written.contains(chunk.offset)) remaining += chunk.uncompressedLength;
        }
        long done = doneBase + (file.size - remaining);
        int sinceFlush = 0;
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw");
             Writer st = new OutputStreamWriter(new FileOutputStream(state, !fresh), "UTF-8")) {
            if (fresh) {
                st.write(header(file));
                st.write('\n');
            }
            raf.setLength(file.size);
            for (DepotManifest.Chunk chunk : file.chunks) {
                checkCancelled();
                if (written.contains(chunk.offset)) continue;
                byte[] encrypted = cdn.fetchChunk(
                        hosts, depot.id, chunk.sha, chunk.compressedLength);
                byte[] plain = ChunkDecoder.decode(chunk, encrypted, depot.key);
                raf.seek(chunk.offset);
                raf.write(plain);
                st.write(Long.toString(chunk.offset));
                st.write('\n');
                if (++sinceFlush >= 64) {
                    st.flush();
                    sinceFlush = 0;
                }
                done += plain.length;
                listener.onProgress(done, total, file.name);
            }
            st.flush();
        }
        if (file.shaContent.length > 0 && !sha1(part).equals(hex(file.shaContent))) {
            deleteQuietly(part);
            deleteQuietly(state);
            throw new SteamException("file hash mismatch: " + file.name);
        }
        if (out.exists() && !out.delete()) {
            throw new SteamException("cannot replace " + out);
        }
        if (!part.renameTo(out)) {
            throw new SteamException("cannot rename " + part);
        }
        deleteQuietly(state);
    }

    /**
     * Chunk offsets already written to a .part file, or an empty set. The
     * first line pins the file the offsets belong to, so a new build of the
     * depot (same name, different contents) cannot reuse stale pieces.
     */
    private static Set<Long> readState(File state, DepotManifest.FileEntry file) {
        Set<Long> out = new HashSet<>();
        if (!state.isFile()) return out;
        String want = header(file);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(state), "UTF-8"))) {
            String header = reader.readLine();
            if (header == null || !header.equals(want)) return out;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    out.add(Long.parseLong(line));
                } catch (NumberFormatException ignored) {
                    /* a torn last line from a power cut: ignore it */
                }
            }
        } catch (IOException ignored) {
            out.clear();
        }
        return out;
    }

    /** Version, size and content hash of the file the state belongs to. */
    private static String header(DepotManifest.FileEntry file) {
        return "v1 " + file.size + " " + hex(file.shaContent);
    }

    private static void deleteQuietly(File file) {
        try {
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }

    private boolean isComplete(File file, DepotManifest.FileEntry entry) throws Exception {
        if (!file.isFile() || file.length() != entry.size) {
            return false;
        }
        if (entry.shaContent.length == 0) {
            return true;
        }
        return sha1(file).equals(hex(entry.shaContent));
    }

    private static File resolve(File target, String name) throws IOException, SteamException {
        String path = name.replace('\\', '/');
        if (path.startsWith("/") || path.contains("../") || path.equals("..")) {
            throw new SteamException("unsafe path " + name);
        }
        File out = new File(target, path);
        String base = target.getCanonicalPath() + File.separator;
        if (!out.getCanonicalPath().startsWith(base)) {
            throw new SteamException("path escapes the game folder: " + name);
        }
        return out;
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = new FileInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    private static final class DepotPlan {
        final int id;
        final long manifestId;

        DepotPlan(int id, long manifestId) {
            this.id = id;
            this.manifestId = manifestId;
        }
    }

    private static final class PreparedDepot {
        final int id;
        final byte[] key;
        final List<DepotManifest.FileEntry> files;

        PreparedDepot(int id, byte[] key, List<DepotManifest.FileEntry> files) {
            this.id = id;
            this.key = key;
            this.files = files;
        }
    }
}
