/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import steam.proto.SteammessagesClientserver2.CMsgClientGetDepotDecryptionKey;
import steam.proto.SteammessagesClientserver2.CMsgClientGetDepotDecryptionKeyResponse;
import steam.proto.SteammessagesClientserverAppinfo.CMsgClientPICSProductInfoRequest;
import steam.proto.SteammessagesClientserverAppinfo.CMsgClientPICSProductInfoResponse;
import steam.proto.SteammessagesContentsystemSteamclient.CContentServerDirectory_GetManifestRequestCode_Request;
import steam.proto.SteammessagesContentsystemSteamclient.CContentServerDirectory_GetManifestRequestCode_Response;
import steam.proto.SteammessagesContentsystemSteamclient.CContentServerDirectory_GetServersForSteamPipe_Request;
import steam.proto.SteammessagesContentsystemSteamclient.CContentServerDirectory_GetServersForSteamPipe_Response;
import steam.proto.SteammessagesContentsystemSteamclient.CContentServerDirectory_ServerInfo;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.OkHttpClient;
import steam.proto.SteammessagesAuthSteamclient.CAuthentication_BeginAuthSessionViaQR_Request;
import steam.proto.SteammessagesAuthSteamclient.CAuthentication_BeginAuthSessionViaQR_Response;
import steam.proto.SteammessagesAuthSteamclient.CAuthentication_DeviceDetails;
import steam.proto.SteammessagesAuthSteamclient.CAuthentication_PollAuthSessionStatus_Request;
import steam.proto.SteammessagesAuthSteamclient.CAuthentication_PollAuthSessionStatus_Response;
import steam.proto.SteammessagesAuthSteamclient.EAuthTokenPlatformType;
import steam.proto.SteammessagesBase.CMsgMulti;
import steam.proto.SteammessagesBase.CMsgProtoBufHeader;
import steam.proto.SteammessagesClientserverLogin.CMsgClientHello;
import steam.proto.SteammessagesClientserverLogin.CMsgClientLogon;
import steam.proto.SteammessagesClientserverLogin.CMsgClientLogonResponse;

/**
 * Minimal Steam CM client: WebSocket transport, unified service calls, QR
 * authentication and access-token logon. It is a Java port of SteamKit2's
 * CMClient/SteamAuthentication/SteamUser subset.
 *
 * <p>The object is thread-safe for one UI flow. Call {@link #connect()}, then
 * {@link #beginQrAuth(String)}, poll with {@link #pollQrAuth(QrSession)} or
 * block in {@link #awaitQrAuth(QrSession, long)}, then {@link #logOn(String,
 * String)}. Tokens are never logged; the caller stores them.
 */
public final class SteamClient implements Closeable {
    /** Connection state changes; failures are reported after a drop. */
    public interface Listener {
        void onDisconnected(Throwable cause);

        void onLoggedOff();
    }

    private static final Logger LOG = Logger.getLogger("SteamClient");
    /** Account 0, desktop instance: the placeholder SteamKit2 sends. */
    private static final long PLACEHOLDER_STEAMID = 76561197960265728L;
    /** EOSType.Android9 from SteamKit2's SteamLanguage.cs. */
    private static final int OS_ANDROID9 = -496;
    private static final int PROTOCOL_VERSION = 65581;
    private static final long SERVICE_TIMEOUT_MS = 30000;
    private static final long LOGON_TIMEOUT_MS = 30000;

    private final OkHttpClient http;
    private final Listener listener;
    private final AtomicLong nextJob = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<Packet>> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<Packet>> oneShots = new ConcurrentHashMap<>();
    private final byte[] machineId;
    private final String machineName;

    private volatile CMConnection connection;
    private volatile boolean connected;
    private volatile boolean loggedOn;
    private volatile boolean closing;
    private volatile long steamId;
    private volatile int sessionId;
    private volatile int cellId;
    private volatile String accountName = "";
    private ScheduledExecutorService heartbeat;

    public SteamClient(String machineName) {
        this(machineName, null, null);
    }

    public SteamClient(String machineName, Listener listener) {
        this(machineName, null, listener);
    }

    /**
     * @param machineId stable 32-byte id for this install; null generates one
     *                  (Steam treats a new id as a new device).
     */
    public SteamClient(String machineName, byte[] machineId, Listener listener) {
        this.machineName = machineName == null || machineName.isEmpty() ? "Android" : machineName;
        this.listener = listener;
        this.machineId = machineId != null && machineId.length > 0 ? machineId.clone() : new byte[32];
        if (machineId == null || machineId.length == 0) {
            new SecureRandom().nextBytes(this.machineId);
        }
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
    }

    /** Resolves a CM list and opens the first WebSocket that accepts. */
    public void connect() throws IOException, SteamException {
        if (connected) {
            return;
        }
        List<String> servers = CMList.fetch(http);
        Exception last = null;
        for (String url : servers) {
            CMConnection candidate = new CMConnection(http, new CMConnection.Listener() {
                @Override
                public void onPacket(CMConnection c, byte[] data) {
                    if (c == connection) {
                        receive(data);
                    }
                }

                @Override
                public void onClosed(CMConnection c, Throwable cause) {
                    if (c == connection) {
                        disconnected(cause);
                    }
                }
            });
            try {
                LOG.info("Steam: connecting to " + url);
                candidate.connect(url);
                connection = candidate;
                connected = true;
                // SteamKit2 sends this in OnClientConnected before anything
                // else; the CM ignores service calls until it arrives.
                CMsgClientHello hello = CMsgClientHello.newBuilder()
                        .setProtocolVersion(PROTOCOL_VERSION)
                        .build();
                send(new Packet(Msg.CLIENT_HELLO,
                        CMsgProtoBufHeader.newBuilder().build(), hello.toByteArray()));
                LOG.info("Steam: connected (hello sent)");
                return;
            } catch (Exception e) {
                last = e;
                candidate.close();
            }
        }
        throw new SteamException("no Steam CM accepted the connection", last);
    }

    /** Starts a QR login; the returned URL is drawn by the caller. */
    public QrSession beginQrAuth(String deviceName) throws Exception {
        String name = deviceName == null || deviceName.isEmpty() ? machineName : deviceName;
        CAuthentication_DeviceDetails details = CAuthentication_DeviceDetails.newBuilder()
                .setDeviceFriendlyName(name)
                .setPlatformType(EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient)
                .setOsType(OS_ANDROID9)
                .build();
        CAuthentication_BeginAuthSessionViaQR_Request request =
                CAuthentication_BeginAuthSessionViaQR_Request.newBuilder()
                        .setDeviceFriendlyName(name)
                        .setPlatformType(EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient)
                        .setDeviceDetails(details)
                        // "Client" gives a token usable for the CM logon;
                        // "Unknown" issues a different audience (EResult 15).
                        .setWebsiteId("Client")
                        .build();
        CAuthentication_BeginAuthSessionViaQR_Response response = callService(
                "Authentication.BeginAuthSessionViaQR#1", request,
                CAuthentication_BeginAuthSessionViaQR_Response.parser(), SERVICE_TIMEOUT_MS);
        if (response.getChallengeUrl().isEmpty()) {
            throw new SteamException("Steam returned an empty QR challenge");
        }
        LOG.info("Steam: QR session started (interval " + response.getInterval() + "s)");
        return new QrSession(response.getClientId(), response.getChallengeUrl(),
                response.getRequestId().toByteArray(), response.getInterval());
    }

    /** Polls once. Returns null while the phone has not confirmed yet. */
    public AuthResult pollQrAuth(QrSession session) throws Exception {
        CAuthentication_PollAuthSessionStatus_Request request =
                CAuthentication_PollAuthSessionStatus_Request.newBuilder()
                        .setClientId(session.clientId())
                        .setRequestId(ByteString.copyFrom(session.requestId()))
                        .build();
        CAuthentication_PollAuthSessionStatus_Response response = callService(
                "Authentication.PollAuthSessionStatus#1", request,
                CAuthentication_PollAuthSessionStatus_Response.parser(), SERVICE_TIMEOUT_MS);
        session.update(response.getNewClientId(), response.getNewChallengeUrl());
        if (response.getRefreshToken().isEmpty()) {
            LOG.fine("Steam: QR still pending");
            return null;
        }
        LOG.info("Steam: QR confirmed for " + response.getAccountName());
        return new AuthResult(response.getRefreshToken(), response.getAccessToken(),
                response.getAccountName());
    }

    /** Blocks until the QR is confirmed or the timeout expires. */
    public AuthResult awaitQrAuth(QrSession session, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AuthResult result = pollQrAuth(session);
            if (result != null) {
                return result;
            }
            Thread.sleep(Math.max(500L, (long) (session.intervalSeconds() * 1000.0)));
        }
        throw new SteamException("QR login timed out");
    }

    /**
     * Logs on with the token returned by {@link #pollQrAuth}. Use the
     * <b>refresh token</b>: DepotDownloader puts it in CMsgClientLogon's
     * access_token field; the short-lived access token is for the Web API.
     */
    public LogOnResult logOn(String refreshToken, String accountName) throws Exception {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new SteamException("no Steam refresh token");
        }
        CMsgClientLogon logon = CMsgClientLogon.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION)
                .setClientOsType(OS_ANDROID9)
                .setClientLanguage("english")
                .setCellId(0)
                .setClientPackageVersion(1771)
                .setMachineId(ByteString.copyFrom(machineId))
                .setMachineName(machineName)
                .setSupportsRateLimitResponse(true)
                .setAccountName(accountName == null ? "" : accountName)
                .setAccessToken(refreshToken)
                .setShouldRememberPassword(false)
                .setSteam2TicketRequest(false)
                .build();
        CMsgProtoBufHeader header = CMsgProtoBufHeader.newBuilder()
                .setSteamid(PLACEHOLDER_STEAMID)
                .build();
        CompletableFuture<Packet> future = new CompletableFuture<>();
        oneShots.put(Msg.CLIENT_LOGON_RESPONSE, future);
        try {
            send(new Packet(Msg.CLIENT_LOGON, header, logon.toByteArray()));
            Packet packet = future.get(LOGON_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            CMsgClientLogonResponse response = CMsgClientLogonResponse.parseFrom(packet.body);
            if (response.getEresult() != 1) {
                throw new SteamException("Steam refused the logon", response.getEresult());
            }
            steamId = packet.header.getSteamid();
            if (steamId == 0) {
                steamId = response.getClientSuppliedSteamid();
            }
            sessionId = packet.header.getClientSessionid();
            cellId = response.getCellId();
            this.accountName = accountName == null ? "" : accountName;
            loggedOn = true;
            int seconds = response.getHeartbeatSeconds() > 0 ? response.getHeartbeatSeconds() : 9;
            startHeartbeat(seconds);
            LOG.info("Steam: logged on as " + this.accountName + " (" + steamId + ")");
            return new LogOnResult(steamId, sessionId, seconds, response.getCellId(),
                    this.accountName);
        } finally {
            oneShots.remove(Msg.CLIENT_LOGON_RESPONSE);
        }
    }

    /** PICS appinfo for an owned app, as an uncompressed binary VDF. */
    public byte[] productInfo(int appId) throws Exception {
        CMsgClientPICSProductInfoRequest.AppInfo app = CMsgClientPICSProductInfoRequest.AppInfo.newBuilder()
                .setAppid(appId)
                .build();
        CMsgClientPICSProductInfoRequest request = CMsgClientPICSProductInfoRequest.newBuilder()
                .addApps(app)
                .setSingleResponse(true)
                .build();
        Packet packet = sendStruct(Msg.CLIENT_PICS_PRODUCT_INFO_REQUEST, request, 30000);
        CMsgClientPICSProductInfoResponse response =
                CMsgClientPICSProductInfoResponse.parseFrom(packet.body);
        for (CMsgClientPICSProductInfoResponse.AppInfo info : response.getAppsList()) {
            if (info.getAppid() == appId) {
                if (info.getMissingToken()) {
                    throw new SteamException("app " + appId + " requires an access token");
                }
                return info.getBuffer().toByteArray();
            }
        }
        throw new SteamException(response.getResponsePending()
                ? "PICS is still pending for app " + appId
                : "PICS returned no info for app " + appId);
    }

    /** The 32-byte AES key of a depot. */
    public byte[] depotKey(int appId, int depotId) throws Exception {
        CMsgClientGetDepotDecryptionKey request = CMsgClientGetDepotDecryptionKey.newBuilder()
                .setAppId(appId)
                .setDepotId(depotId)
                .build();
        Packet packet = sendStruct(Msg.CLIENT_GET_DEPOT_DECRYPTION_KEY, request, 20000);
        CMsgClientGetDepotDecryptionKeyResponse response =
                CMsgClientGetDepotDecryptionKeyResponse.parseFrom(packet.body);
        if (response.getEresult() != 1) {
            throw new SteamException("no depot key for " + depotId, response.getEresult());
        }
        return response.getDepotEncryptionKey().toByteArray();
    }

    /** Content servers for the cell the CM assigned at logon. */
    public List<CContentServerDirectory_ServerInfo> cdnServers(int max) throws Exception {
        CContentServerDirectory_GetServersForSteamPipe_Request request =
                CContentServerDirectory_GetServersForSteamPipe_Request.newBuilder()
                        .setCellId(cellId)
                        .setMaxServers(max)
                        .build();
        CContentServerDirectory_GetServersForSteamPipe_Response response = callService(
                "ContentServerDirectory.GetServersForSteamPipe#1", request,
                CContentServerDirectory_GetServersForSteamPipe_Response.parser(),
                SERVICE_TIMEOUT_MS);
        return response.getServersList();
    }

    /** The token the CDN manifest URL carries after its version. */
    public long manifestRequestCode(int appId, int depotId, long manifestId) throws Exception {
        CContentServerDirectory_GetManifestRequestCode_Request request =
                CContentServerDirectory_GetManifestRequestCode_Request.newBuilder()
                        .setAppId(appId)
                        .setDepotId(depotId)
                        .setManifestId(manifestId)
                        .setAppBranch("public")
                        .setBranchPasswordHash("")
                        .build();
        CContentServerDirectory_GetManifestRequestCode_Response response = callService(
                "ContentServerDirectory.GetManifestRequestCode#1", request,
                CContentServerDirectory_GetManifestRequestCode_Response.parser(),
                SERVICE_TIMEOUT_MS);
        return response.getManifestRequestCode();
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isLoggedOn() {
        return loggedOn;
    }

    public long steamId() {
        return steamId;
    }

    public int sessionId() {
        return sessionId;
    }

    public int cellId() {
        return cellId;
    }

    public String accountName() {
        return accountName;
    }

    /* ---- transport and jobs ---- */

    private <T> T callService(String name, MessageLite request, Parser<T> parser, long timeoutMs)
            throws Exception {
        long job = nextJob.incrementAndGet();
        CMsgProtoBufHeader header = header()
                .setJobidSource(job)
                .setTargetJobName(name)
                .build();
        CompletableFuture<Packet> future = new CompletableFuture<>();
        jobs.put(job, future);
        try {
            int emsg = loggedOn ? Msg.SERVICE_METHOD_CALL_FROM_CLIENT
                    : Msg.SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED;
            send(new Packet(emsg, header, request.toByteArray()));
            Packet response;
            try {
                response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new SteamException("no reply to " + name + " within "
                        + timeoutMs + " ms", e);
            }
            int result = response.header.getEresult();
            if (result != 1) {
                throw new SteamException(name + " failed", result);
            }
            return parser.parseFrom(response.body);
        } finally {
            jobs.remove(job);
        }
    }

    /** Sends a struct message (PICS, depot key) and waits for its job reply. */
    private Packet sendStruct(int emsg, MessageLite body, long timeoutMs) throws Exception {
        long job = nextJob.incrementAndGet();
        CMsgProtoBufHeader header = header().setJobidSource(job).build();
        CompletableFuture<Packet> future = new CompletableFuture<>();
        jobs.put(job, future);
        try {
            send(new Packet(emsg, header, body.toByteArray()));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            jobs.remove(job);
        }
    }

    private CMsgProtoBufHeader.Builder header() {
        CMsgProtoBufHeader.Builder builder = CMsgProtoBufHeader.newBuilder();
        if (steamId != 0) {
            builder.setSteamid(steamId);
        }
        if (sessionId != 0) {
            builder.setClientSessionid(sessionId);
        }
        return builder;
    }

    private void send(Packet packet) throws SteamException {
        CMConnection current = connection;
        if (current == null) {
            throw new SteamException("not connected to Steam");
        }
        LOG.fine("Steam: -> " + packet);
        current.send(packet.encode());
    }

    private void receive(byte[] data) {
        try {
            Packet packet = Packet.decode(data);
            LOG.fine("Steam: <- " + packet);
            handle(packet);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Steam: bad packet", e);
        }
    }

    private void handle(Packet packet) {
        if (packet.emsg == Msg.MULTI) {
            handleMulti(packet);
            return;
        }
        // Service responses, PICS replies and depot-key replies all carry the
        // source job in jobid_target; the default (-1) means "not a job reply".
        long target = packet.header.getJobidTarget();
        if (target != 0 && target != -1) {
            CompletableFuture<Packet> future = jobs.remove(target);
            if (future != null) {
                future.complete(packet);
                return;
            }
        }
        if (packet.emsg == Msg.SERVICE_METHOD) {
            return;  // server notification, no waiter
        }
        CompletableFuture<Packet> oneShot = oneShots.remove(packet.emsg);
        if (oneShot != null) {
            oneShot.complete(packet);
        }
        if (packet.emsg == Msg.CLIENT_LOGGED_OFF) {
            loggedOn = false;
            if (listener != null) {
                listener.onLoggedOff();
            }
        }
    }

    /** CMsgMulti wraps one or more length-prefixed packets, optionally gzipped. */
    private void handleMulti(Packet packet) {
        try {
            CMsgMulti multi = CMsgMulti.parseFrom(packet.body);
            byte[] payload = multi.getMessageBody().toByteArray();
            if (multi.getSizeUnzipped() > 0) {
                payload = gunzip(payload);
            }
            ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            while (bb.remaining() >= 4) {
                int length = bb.getInt();
                if (length <= 0 || length > bb.remaining()) {
                    LOG.warning("Steam: bad multi length " + length);
                    break;
                }
                byte[] inner = new byte[length];
                bb.get(inner);
                try {
                    handle(Packet.decode(inner));
                } catch (SteamException e) {
                    // A Multi can carry struct (non-protobuf) notifications too.
                    LOG.fine("Steam: skipping inner packet: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Steam: bad multi packet", e);
        }
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private void disconnected(Throwable cause) {
        connected = false;
        loggedOn = false;
        SteamException reason = cause instanceof SteamException
                ? (SteamException) cause : new SteamException("Steam disconnected", cause);
        failPending(reason);
        stopHeartbeat();
        if (!closing && listener != null) {
            listener.onDisconnected(cause);
        }
    }

    private void failPending(Throwable cause) {
        for (Long key : new ArrayList<>(jobs.keySet())) {
            CompletableFuture<Packet> future = jobs.remove(key);
            if (future != null) {
                future.completeExceptionally(cause);
            }
        }
        for (Integer key : new ArrayList<>(oneShots.keySet())) {
            CompletableFuture<Packet> future = oneShots.remove(key);
            if (future != null) {
                future.completeExceptionally(cause);
            }
        }
    }

    private synchronized void startHeartbeat(int seconds) {
        stopHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "steam-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                send(new Packet(Msg.CLIENT_HEARTBEAT, header().build(), new byte[0]));
            } catch (Exception e) {
                LOG.fine("Steam: heartbeat failed: " + e);
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    private synchronized void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    @Override
    public synchronized void close() {
        closing = true;
        stopHeartbeat();
        CMConnection current = connection;
        connection = null;
        connected = false;
        loggedOn = false;
        if (current != null) {
            current.close();
        }
        failPending(new SteamException("Steam client closed"));
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
