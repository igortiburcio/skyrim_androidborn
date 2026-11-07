package com.tj.skyrim.launcher;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.tj.skyrim.SteamDownloadService;
import com.tj.skyrim.steam.AuthResult;
import com.tj.skyrim.steam.QrSession;
import com.tj.skyrim.steam.SteamClient;
import com.tj.skyrim.steam.SteamDownloader;
import com.tj.skyrim.steam.SteamException;
import java.io.File;
import java.util.Locale;

/**
 * Steam sign-in and download block, shown inside the Game Files tab. The
 * transfer itself runs in {@link SteamDownloadService}; this view only starts
 * it and follows {@link SteamDownloadState}.
 */
public final class SteamSection implements LauncherWindow.Content, SteamDownloadState.Listener {

    /** The activity owns the folder picker and the game-folder refresh. */
    public interface Listener {
        void onChooseDownloadFolderRequested();

        void onDownloadFinished(String path);
    }

    private final Widgets widgets;
    private final Listener listener;
    private final SteamTokenStore tokens;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    private TextView statusText, detailText, progressText;
    private ImageView qrView;
    private Button signInButton, signOutButton, downloadButton, cancelButton;
    private SteamClient client;
    private volatile Thread worker;
    private long lastUiPost;
    private boolean downloadFinishedNotified;

    public SteamSection(Widgets widgets, Listener listener) {
        this.widgets = widgets;
        this.listener = listener;
        this.tokens = new SteamTokenStore(widgets.context());
    }

    @Override
    public CharSequence title() {
        return "Steam";
    }

    @Override
    public void build(LinearLayout pane) {
        buildInto(pane);
    }

    /** Adds the block to a shared pane (Game Files) instead of owning a tab. */
    public void buildInto(LinearLayout pane) {
        statusText = widgets.text(pane, "Not signed in", 16, false);
        detailText = widgets.text(pane,
                "Open the Steam app on your phone, scan the code and confirm.", 14, false);
        detailText.setTextColor(widgets.hintColor());
        qrView = new ImageView(widgets.context());
        qrView.setVisibility(View.GONE);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                widgets.dp(240), widgets.dp(240));
        qrLp.topMargin = widgets.dp(8);
        pane.addView(qrView, qrLp);
        signInButton = widgets.actionButton(pane, "Sign in with Steam", v -> signIn());
        signOutButton = widgets.actionButton(pane, "Sign out", v -> signOut());
        signOutButton.setVisibility(View.GONE);
        downloadButton = widgets.actionButton(pane, "Download game data", v -> startDownload());
        downloadButton.setVisibility(View.GONE);
        cancelButton = widgets.actionButton(pane, "Cancel download", v -> cancelDownload());
        cancelButton.setVisibility(View.GONE);
        progressText = widgets.text(pane, "", 13, false);
        progressText.setTextColor(widgets.hintColor());
        progressText.setMaxLines(2);

        SteamDownloadState.add(this);
        SteamClient session = SteamSession.get();
        if (session != null && session.isLoggedOn()) {
            client = session;
            showLoggedOn(session);
        } else if (tokens.has()) {
            String account = tokens.accountName();
            if (!account.isEmpty()) {
                signInButton.setText("Continue as " + account);
                detailText.setText("Saved Steam session. Tap Continue, or Sign out to "
                        + "use another account.");
            }
        }
        applyDownloadState();
    }

    @Override
    public View initialFocus() {
        return signInButton;
    }

    /** Stops following the tab; keeps the session while a download runs. */
    public void close() {
        SteamDownloadState.remove(this);
        cancelled = true;
        if (!SteamDownloadState.isRunning()) {
            SteamSession.close();
            client = null;
        }
    }

    /* ---- sign in ---- */

    private void signIn() {
        if (worker != null) return;
        if (SteamSession.isLoggedOn()) {
            client = SteamSession.get();
            showLoggedOn(client);
            return;
        }
        SteamTokenStore.Token saved = tokens.load();
        if (saved != null) startTokenSignIn(saved);
        else startQrSignIn();
    }

    private void startQrSignIn() {
        if (worker != null) return;
        cancelled = false;
        signInButton.setEnabled(false);
        signInButton.setText("Sign in with Steam");
        setStatus("Connecting to Steam\u2026", widgets.hintColor());
        detailText.setText("The Steam app on your phone confirms the code.");
        qrView.setVisibility(View.GONE);
        worker = new Thread(this::runSignIn, "steam-sign-in");
        worker.setDaemon(true);
        worker.start();
    }

    private void startTokenSignIn(SteamTokenStore.Token saved) {
        cancelled = false;
        signInButton.setEnabled(false);
        signInButton.setText("Signing in\u2026");
        setStatus("Signing in with the saved session\u2026", widgets.hintColor());
        detailText.setText("Steam account: " + saved.accountName);
        qrView.setVisibility(View.GONE);
        worker = new Thread(() -> {
            SteamClient steam = new SteamClient(Build.MODEL);
            try {
                steam.connect();
                steam.logOn(saved.refreshToken, saved.accountName);
                if (cancelled) {
                    steam.close();
                    return;
                }
                client = steam;
                SteamSession.set(steam);
                ui.post(() -> {
                    showLoggedOn(steam);
                    applyDownloadState();
                });
            } catch (Exception e) {
                android.util.Log.w("SteamTab", "saved Steam session failed", e);
                tokens.clear();
                steam.close();
                worker = null;
                ui.post(() -> {
                    signInButton.setText("Sign in with Steam");
                    signInButton.setEnabled(true);
                    setStatus("Saved session expired; scan the QR", widgets.warnColor());
                    startQrSignIn();
                });
            } finally {
                worker = null;
            }
        }, "steam-token-login");
        worker.setDaemon(true);
        worker.start();
    }

    private void runSignIn() {
        SteamClient steam = new SteamClient(Build.MODEL);
        try {
            steam.connect();
            QrSession session = steam.beginQrAuth("Skyrim Androidborn");
            client = steam;
            ui.post(() -> showQr(session));
            AuthResult auth = awaitConfirmation(steam, session);
            if (auth == null) {
                throw new SteamException("QR login timed out");
            }
            ui.post(() -> setStatus("Signing in\u2026", widgets.hintColor()));
            steam.logOn(auth.refreshToken(), auth.accountName());
            if (cancelled) {
                steam.close();
                return;
            }
            tokens.save(auth.refreshToken(), auth.accountName());
            SteamSession.set(steam);
            ui.post(() -> {
                showLoggedOn(steam);
                applyDownloadState();
            });
        } catch (Exception e) {
            android.util.Log.e("SteamTab", "Steam sign-in failed", e);
            steam.close();
            if (!cancelled) {
                ui.post(() -> showError(e));
            }
        } finally {
            worker = null;
        }
    }

    private AuthResult awaitConfirmation(SteamClient steam, QrSession session) throws Exception {
        long end = System.currentTimeMillis() + 5 * 60 * 1000L;
        String lastUrl = session.challengeUrl();
        int timeouts = 0;
        while (!cancelled && System.currentTimeMillis() < end) {
            AuthResult result;
            try {
                result = steam.pollQrAuth(session);
                timeouts = 0;
            } catch (SteamException e) {
                if (e.getCause() instanceof java.util.concurrent.TimeoutException && ++timeouts < 3) {
                    android.util.Log.w("SteamTab", "poll timed out, retrying", e);
                    Thread.sleep(1000);
                    continue;
                }
                throw e;
            }
            if (result != null) {
                return result;
            }
            if (!session.challengeUrl().equals(lastUrl)) {
                lastUrl = session.challengeUrl();
                ui.post(() -> showQr(session));
            }
            Thread.sleep(Math.max(500L, (long) (session.intervalSeconds() * 1000.0)));
        }
        return null;
    }

    private void showQr(QrSession session) {
        try {
            qrView.setImageBitmap(qrBitmap(session.challengeUrl(), widgets.dp(240)));
            qrView.setVisibility(View.VISIBLE);
            setStatus("Waiting for confirmation\u2026", widgets.warnColor());
            detailText.setText("Steam app > Confirm sign-in. The code refreshes by itself.");
        } catch (Exception e) {
            showError(e);
        }
    }

    private void showLoggedOn(SteamClient steam) {
        qrView.setVisibility(View.GONE);
        signInButton.setVisibility(View.GONE);
        signOutButton.setVisibility(View.VISIBLE);
        signOutButton.setEnabled(true);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        String name = steam.accountName() == null || steam.accountName().isEmpty()
                ? "Steam account" : steam.accountName();
        setStatus("Signed in as " + name, widgets.okColor());
        detailText.setText("SteamID " + steam.steamId()
                + ". Download the game data in the folder chosen above.");
    }

    private void showError(Exception e) {
        qrView.setVisibility(View.GONE);
        signInButton.setEnabled(true);
        signInButton.setVisibility(View.VISIBLE);
        signInButton.setText("Sign in with Steam");
        signOutButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.GONE);
        progressText.setText("");
        setStatus("Steam sign-in failed", widgets.badColor());
        detailText.setText(e.getMessage() == null ? e.toString() : e.getMessage());
        worker = null;
    }

    private void signOut() {
        cancelled = true;
        if (SteamDownloadState.isRunning()) {
            SteamDownloadService.cancel(widgets.context());
        }
        SteamSession.close();
        client = null;
        tokens.clear();
        qrView.setVisibility(View.GONE);
        signOutButton.setVisibility(View.GONE);
        signInButton.setVisibility(View.VISIBLE);
        signInButton.setEnabled(true);
        signInButton.setText("Sign in with Steam");
        downloadButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.GONE);
        progressText.setText("");
        setStatus("Not signed in", widgets.hintColor());
        detailText.setText("Open the Steam app on your phone, scan the code and confirm.");
    }

    /* ---- download ---- */

    private void startDownload() {
        if (!SteamSession.isLoggedOn()) {
            setStatus("Sign in to Steam first", widgets.badColor());
            return;
        }
        listener.onChooseDownloadFolderRequested();
    }

    /** Called after the in-app folder browser returns a path. */
    public void downloadInto(String path) {
        final File target = new File(path);
        if (!target.isDirectory() && !target.mkdirs()) {
            error("Cannot write to that folder: " + path);
            return;
        }
        if (!SteamSession.isLoggedOn()) {
            setStatus("Sign in to Steam first", widgets.badColor());
            return;
        }
        downloadFinishedNotified = false;
        downloadButton.setEnabled(false);
        cancelButton.setVisibility(View.VISIBLE);
        cancelButton.setEnabled(true);
        progressText.setText("");
        setStatus("Starting download\u2026", widgets.hintColor());
        detailText.setText(target.getAbsolutePath());
        SteamDownloadService.start(widgets.context(), target.getAbsolutePath(),
                SteamDownloader.SKYRIM_APP_ID);
    }

    private void cancelDownload() {
        SteamDownloadService.cancel(widgets.context());
        cancelButton.setVisibility(View.GONE);
        setStatus("Cancelling download\u2026", widgets.warnColor());
    }

    @Override
    public void onDownloadStateChanged() {
        int state = SteamDownloadState.state();
        long now = System.currentTimeMillis();
        if (state == SteamDownloadState.RUNNING && now - lastUiPost < 200) {
            return;
        }
        lastUiPost = now;
        ui.post(this::applyDownloadState);
    }

    private void applyDownloadState() {
        if (statusText == null) {
            return;
        }
        boolean logged = SteamSession.isLoggedOn();
        int state = SteamDownloadState.state();

        if (!logged && state != SteamDownloadState.RUNNING) {
            downloadButton.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            signOutButton.setVisibility(View.GONE);
            return;
        }
        if (state == SteamDownloadState.RUNNING) {
            downloadButton.setVisibility(View.VISIBLE);
            downloadButton.setEnabled(false);
            cancelButton.setVisibility(View.VISIBLE);
            signOutButton.setEnabled(false);
            int files = SteamDownloadState.files();
            long total = SteamDownloadState.total();
            if (files > 0) {
                setStatus("Downloading " + files + " files (" + size(total) + ")",
                        widgets.hintColor());
            } else {
                setStatus("Preparing download\u2026", widgets.hintColor());
            }
            String depot = SteamDownloadState.detail();
            if (!depot.isEmpty()) {
                detailText.setText(depot);
            }
            long done = SteamDownloadState.done();
            int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
            progressText.setText(percent + "%  " + size(done) + " / " + size(total)
                    + "\n" + SteamDownloadState.file());
        } else if (state == SteamDownloadState.DONE) {
            downloadButton.setVisibility(View.VISIBLE);
            downloadButton.setEnabled(true);
            cancelButton.setVisibility(View.GONE);
            signOutButton.setEnabled(true);
            setStatus("Game data is ready", widgets.okColor());
            detailText.setText("Verified/downloaded in " + SteamDownloadState.target());
            progressText.setText("");
            if (!downloadFinishedNotified) {
                downloadFinishedNotified = true;
                if (listener != null) {
                    listener.onDownloadFinished(SteamDownloadState.target());
                }
            }
        } else if (state == SteamDownloadState.ERROR) {
            downloadButton.setVisibility(View.VISIBLE);
            downloadButton.setEnabled(true);
            cancelButton.setVisibility(View.GONE);
            signOutButton.setEnabled(true);
            setStatus("Download failed", widgets.badColor());
            detailText.setText(SteamDownloadState.error());
            progressText.setText("");
        } else if (state == SteamDownloadState.CANCELLED) {
            downloadButton.setVisibility(View.VISIBLE);
            downloadButton.setEnabled(true);
            cancelButton.setVisibility(View.GONE);
            signOutButton.setEnabled(true);
            setStatus("Download cancelled", widgets.warnColor());
            progressText.setText("");
        }
    }

    /** Shows a folder-picker or download-folder error in the tab. */
    public void error(String message) {
        setStatus("Download folder error", widgets.badColor());
        detailText.setText(message);
    }

    private void setStatus(String text, int color) {
        statusText.setText(text);
        statusText.setTextColor(color);
    }

    private static String size(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
    }

    private static Bitmap qrBitmap(String text, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF101010 : 0xFFFFFFFF);
            }
        }
        return bitmap;
    }
}
