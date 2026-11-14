package org.telegram.porcodio;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * Aggiornamenti interni di Porcodio Luxury Chat (dalla v6).
 * Manifest JSON pubblico su GitHub Releases ("latest/download/update.json"), scaricamento con un downloader nostro
 * (HTTPS verso GitHub, redirect seguiti a mano, ripresa del file, 3 tentativi, controllo SHA-256), installazione con PackageInstaller
 * (alternativa: installer di sistema via FileProvider). Il permesso "Installa app sconosciute" viene spiegato e si apre la schermata giusta.
 * Stati: IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, ERROR. La schermata Impostazioni > Aggiornamenti li mostra tutti.
 * Logica pura (confronto versioni, manifest, host) in PcUpdateLogic, provata in CI.
 */
public class PcUpdater {
    public static final String MANIFEST_URL = "https://github.com/porcodioluxury/porcodio-luxury-chat-android/releases/latest/download/update.json";
    private static final long AUTO_EVERY_MS = 6L * 3600_000L;
    private static final long RETRY_MS = 15L * 60_000L;
    private static final String PREFS = "pc_updater";

    public enum State {IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, ERROR}

    public interface Listener {
        void onUpdaterChanged();
    }

    public static volatile State state = State.IDLE;
    public static PcUpdateLogic.Info available;
    public static String error;
    public static int progress;
    public static long gotBytes, totalBytes;
    private static File readyFile;
    private static boolean errorRetryIsDownload;
    private static volatile boolean cancelled;
    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static boolean periodicStarted;

    // ---- stato e preferenze
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean autoUpdate() {
        return prefs().getBoolean("auto", false);
    }

    public static void setAutoUpdate(boolean on) {
        prefs().edit().putBoolean("auto", on).apply();
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed(State s) {
        state = s;
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onUpdaterChanged();
            }
        });
    }

    public static long installedCode() {
        try {
            android.content.pm.PackageInfo p = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    public static int currentCode() {
        return PcUpdateLogic.installedBase(installedCode());
    }

    public static String currentName() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean debuggable() {
        return (ApplicationLoader.applicationContext.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static String manifestUrl() {
        if (debuggable()) {
            String o = prefs().getString("test_manifest", null); // solo build di debug: serve ai test su emulatore
            if (o != null && o.startsWith("http://10.0.2.2:")) {
                return o;
            }
        }
        return MANIFEST_URL;
    }

    /** Solo per i test su emulatore (build di debug): imposta un manifest locale e riparte. */
    public static void setTestManifest(String url) {
        prefs().edit().putString("test_manifest", url).apply();
    }

    // ---- controllo
    /** Chiamato all'avvio e a ogni ritorno in primo piano: controlla se sono passate 6 ore dall'ultimo controllo riuscito (15 minuti dopo un errore). */
    public static void autoCheck(Activity activity) {
        if (activity == null) {
            return;
        }
        startPeriodic();
        cleanup();
        if (state == State.READY && readyFile != null && readyFile.exists()) {
            // permesso appena concesso nella schermata di sistema: l'installazione riparte
            if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) {
                if (prefs().getBoolean("wait_perm", false)) {
                    prefs().edit().putBoolean("wait_perm", false).apply();
                    install(activity, readyFile);
                }
            }
            return;
        }
        if (state != State.IDLE && state != State.ERROR && state != State.AVAILABLE) {
            return;
        }
        if (PcUpdateLogic.shouldAutoCheck(System.currentTimeMillis(), prefs().getLong("last_ok", 0), prefs().getLong("last_try", 0), AUTO_EVERY_MS, RETRY_MS)) {
            check(activity, false);
        } else if (state == State.AVAILABLE && available != null && System.currentTimeMillis() - prefs().getLong("snooze", 0) > AUTO_EVERY_MS) {
            present(activity); // c'è una versione già trovata e l'utente non l'ha ancora installata: si ripropone
        }
    }

    /** Ricontrollo ogni 6 ore anche se l'app resta aperta a lungo. */
    private static void startPeriodic() {
        if (periodicStarted) {
            return;
        }
        periodicStarted = true;
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            android.app.Activity a = org.telegram.ui.LaunchActivity.instance;
            if (a != null && !a.isFinishing()) {
                autoCheck(a);
            }
            AndroidUtilities.runOnUIThread(loop[0], AUTO_EVERY_MS);
        };
        AndroidUtilities.runOnUIThread(loop[0], AUTO_EVERY_MS);
    }

    /** Da Impostazioni: dice sempre l'esito. */
    public static void checkNow(Activity activity) {
        check(activity, true);
    }

    private static void check(final Activity activity, final boolean manual) {
        if (state == State.CHECKING || state == State.DOWNLOADING) {
            return;
        }
        error = null;
        changed(State.CHECKING);
        final long started = System.currentTimeMillis();
        prefs().edit().putLong("last_try", started).apply();
        fetch((info, err) -> {
            if (err != null) {
                error = err;
                errorRetryIsDownload = false;
                changed(State.ERROR);
                if (manual && activity != null && !activity.isFinishing()) {
                    presentError(activity);
                }
                return;
            }
            prefs().edit().putLong("last_ok", System.currentTimeMillis()).apply();
            if (PcUpdateLogic.isNewer(info.versionCode, installedCode())) {
                available = info;
                changed(State.AVAILABLE);
                if (activity != null && !activity.isFinishing()) {
                    if (autoUpdate() && !manual) {
                        startDownload(activity, info, true);
                    } else if (manual || System.currentTimeMillis() - prefs().getLong("snooze", 0) > AUTO_EVERY_MS) {
                        present(activity);
                    }
                }
            } else {
                available = null;
                changed(State.IDLE);
                if (manual && activity != null && !activity.isFinishing()) {
                    PcUi.toast(activity, LocaleController.formatString(R.string.PcUpdUpToDate, currentName()));
                }
            }
        });
    }

    private interface Done<T> {
        void run(T value, String error);
    }

    private static void fetch(Done<PcUpdateLogic.Info> done) {
        Utilities.globalQueue.postRunnable(() -> {
            PcUpdateLogic.Info info = null;
            String error = null;
            try {
                String text = httpText(manifestUrl());
                info = PcUpdateLogic.parse(text, debuggable());
            } catch (Throwable t) {
                error = LocaleController.getString(R.string.PcUpdErrNet) + "\n(" + String.valueOf(t.getMessage()) + ")";
            }
            final PcUpdateLogic.Info fi = info;
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> done.run(fi, fe));
        });
    }

    /** GET con redirect seguiti a mano (github.com -> CDN degli asset), solo https verso GitHub. */
    private static String httpText(String start) throws Exception {
        HttpURLConnection c = openFollow(start, 0);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 64 * 1024) {
                        throw new IOException("manifest troppo grande");
                    }
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection openFollow(String start, long resumeFrom) throws Exception {
        String url = start;
        for (int hop = 0; hop < 6; hop++) {
            if (!PcUpdateLogic.allowedHost(url) && !(debuggable() && url.startsWith("http://10.0.2.2:"))) {
                throw new IOException("indirizzo non ammesso");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "PorcodioLuxuryChat/" + currentName());
            c.setRequestProperty("Accept", "*/*");
            if (resumeFrom > 0) {
                c.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) {
                    throw new IOException("redirect senza destinazione");
                }
                url = PcUpdateLogic.resolve(url, loc);
                continue;
            }
            if (code != 200 && code != 206) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("troppi redirect");
    }

    // ---- interfaccia: dialoghi
    private static boolean usable(Activity a) {
        return a != null && !a.isFinishing();
    }

    /** Mostra il dialogo giusto per lo stato attuale. */
    public static void present(final Activity activity) {
        if (!usable(activity)) {
            return;
        }
        if (state == State.AVAILABLE && available != null) {
            final PcUpdateLogic.Info info = available;
            StringBuilder msg = new StringBuilder(LocaleController.formatString(R.string.PcUpdMessage, info.versionName));
            msg.append("  ").append(LocaleController.formatString(R.string.PcUpdFrom, currentName()));
            if (info.size > 0) {
                msg.append("\n").append(String.format(Locale.US, "%.1f MB", info.size / 1048576.0));
            }
            if (!info.changelog.isEmpty()) {
                msg.append("\n\n").append(LocaleController.getString(R.string.PcUpdNotes)).append(":\n").append(info.changelog);
            }
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle(LocaleController.getString(R.string.PcUpdTitleNew));
            b.setMessage(msg.toString());
            b.setPositiveButton(LocaleController.getString(R.string.PcUpdNowBtn), (d, w) -> startDownload(activity, info, false));
            b.setNegativeButton(LocaleController.getString(R.string.PcUpdLater), (d, w) -> prefs().edit().putLong("snooze", System.currentTimeMillis()).apply());
            b.show();
        } else if (state == State.READY && readyFile != null) {
            presentReady(activity);
        } else if (state == State.ERROR) {
            presentError(activity);
        } else if (state == State.DOWNLOADING) {
            showProgress(activity);
        }
    }

    private static void presentReady(final Activity activity) {
        if (!usable(activity) || available == null || readyFile == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PcUpdReadyTitle));
        b.setMessage(LocaleController.formatString(R.string.PcUpdReadyText, available.versionName));
        b.setPositiveButton(LocaleController.getString(R.string.PcUpdInstallBtn), (d, w) -> install(activity, readyFile));
        b.setNegativeButton(LocaleController.getString(R.string.PcUpdLater), (d, w) -> prefs().edit().putLong("snooze", System.currentTimeMillis()).apply());
        b.show();
    }

    private static void presentError(final Activity activity) {
        if (!usable(activity)) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PcUpdErrTitle));
        b.setMessage(error == null ? LocaleController.getString(R.string.PcUpdFailed) : error);
        b.setPositiveButton(LocaleController.getString(R.string.PcUpdRetry), (d, w) -> {
            if (errorRetryIsDownload && available != null) {
                startDownload(activity, available, false);
            } else {
                check(activity, true);
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.PcUpdClose), null);
        b.show();
    }

    private static AlertDialog progressDialog;
    private static TextView progressText;
    private static ProgressBar progressBar;

    private static void showProgress(final Activity activity) {
        if (!usable(activity)) {
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        progressText = new TextView(activity);
        progressText.setTextSize(15);
        progressText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        box.addView(progressText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        box.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16, 0, 12, 0, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PcUpdTitleNew));
        b.setView(box);
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            cancelled = true;
        });
        progressDialog = b.create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
        updateProgressUi();
    }

    private static void updateProgressUi() {
        if (progressText == null) {
            return;
        }
        progressBar.setProgress(progress);
        progressText.setText(LocaleController.formatString(R.string.PcUpdDownloadingMb, progress,
            String.format(Locale.US, "%.1f", gotBytes / 1048576.0), String.format(Locale.US, "%.1f", Math.max(totalBytes, gotBytes) / 1048576.0)));
    }

    private static void closeProgress() {
        try {
            if (progressDialog != null) {
                progressDialog.dismiss();
            }
        } catch (Throwable ignore) {
        }
        progressDialog = null;
        progressText = null;
        progressBar = null;
    }

    // ---- download
    private static File updatesDir() {
        Context ctx = ApplicationLoader.applicationContext;
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = new File(ctx.getFilesDir(), "updates");
        }
        dir.mkdirs();
        return dir;
    }

    private static String fileName(PcUpdateLogic.Info info) {
        return "porcodio-luxury-chat-" + info.versionCode + ".apk";
    }

    /** Toglie gli APK di versioni gia' installate o piu' vecchie. */
    private static void cleanup() {
        try {
            File[] fs = updatesDir().listFiles();
            if (fs == null) {
                return;
            }
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith("porcodio-luxury-chat-") && (n.endsWith(".apk") || n.endsWith(".part"))) {
                    String num = n.substring("porcodio-luxury-chat-".length()).replaceAll("[^0-9].*$", "");
                    if (!num.isEmpty() && Integer.parseInt(num) <= currentCode()) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** background = scarico silenzioso (aggiornamento automatico): nessun dialogo di avanzamento, poi si chiede solo di installare. */
    public static void startDownload(final Activity activity, final PcUpdateLogic.Info info, final boolean background) {
        if (state == State.DOWNLOADING) {
            if (!background) {
                showProgress(activity);
            }
            return;
        }
        available = info;
        cancelled = false;
        progress = 0;
        gotBytes = 0;
        totalBytes = info.size;
        error = null;
        changed(State.DOWNLOADING);
        if (!background) {
            showProgress(activity);
        }
        final File fin = new File(updatesDir(), fileName(info));
        final File part = new File(updatesDir(), fileName(info) + ".part");
        Utilities.globalQueue.postRunnable(() -> {
            String err = null;
            boolean ok = false;
            try {
                if (fin.exists() && info.sha256.equals(sha256(fin))) {
                    ok = true;
                } else {
                    fin.delete();
                    IOException last = null;
                    for (int attempt = 1; attempt <= 3 && !cancelled; attempt++) {
                        try {
                            downloadTo(part, info);
                            last = null;
                            break;
                        } catch (IOException e) {
                            last = e;
                            try {
                                Thread.sleep(attempt * 2000L);
                            } catch (InterruptedException ignore) {
                            }
                        }
                    }
                    if (cancelled) {
                        err = null;
                    } else if (last != null) {
                        err = LocaleController.getString(R.string.PcUpdErrDownload) + "\n(" + last.getMessage() + ")";
                    } else if (!info.sha256.equals(sha256(part))) {
                        part.delete();
                        err = LocaleController.getString(R.string.PcUpdBadHash);
                    } else if (!part.renameTo(fin)) {
                        err = LocaleController.getString(R.string.PcUpdErrDownload);
                    } else {
                        ok = true;
                    }
                }
            } catch (Throwable t) {
                err = LocaleController.getString(R.string.PcUpdErrDownload) + "\n(" + t.getMessage() + ")";
            }
            final boolean fok = ok;
            final String ferr = err;
            AndroidUtilities.runOnUIThread(() -> {
                closeProgress();
                if (fok) {
                    readyFile = fin;
                    changed(State.READY);
                    Activity a = usable(activity) ? activity : org.telegram.ui.LaunchActivity.instance;
                    if (usable(a)) {
                        presentReady(a);
                    }
                } else if (cancelled) {
                    changed(State.AVAILABLE);
                } else {
                    error = ferr;
                    errorRetryIsDownload = true;
                    changed(State.ERROR);
                    Activity a = usable(activity) ? activity : org.telegram.ui.LaunchActivity.instance;
                    if (usable(a)) {
                        presentError(a);
                    }
                }
            });
        });
    }

    private static void downloadTo(File part, PcUpdateLogic.Info info) throws IOException {
        try {
            long have = part.exists() ? part.length() : 0;
            if (info.size > 0 && have > info.size) {
                part.delete();
                have = 0;
            }
            HttpURLConnection c = openFollow(info.apkUrl, have);
            try {
                boolean resumed = c.getResponseCode() == 206 && have > 0;
                long len = c.getContentLengthLong();
                long total = len > 0 ? (resumed ? have + len : len) : info.size;
                long got = resumed ? have : 0;
                if (total > 0) {
                    totalBytes = total;
                }
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, resumed)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long lastUi = 0;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled) {
                            return;
                        }
                        out.write(buf, 0, n);
                        got += n;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastUi > 250) {
                            lastUi = now;
                            gotBytes = got;
                            progress = total > 0 ? (int) Math.min(100, got * 100 / total) : 0;
                            AndroidUtilities.runOnUIThread(() -> {
                                updateProgressUi();
                                for (Listener l : new ArrayList<>(listeners)) {
                                    l.onUpdaterChanged();
                                }
                            });
                        }
                    }
                    gotBytes = got;
                    progress = 100;
                }
                if (total > 0 && part.length() != total) {
                    throw new IOException("file incompleto (" + part.length() + " di " + total + " byte)");
                }
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
    }

    // ---- installazione
    public static void install(final Activity activity, final File f) {
        if (!usable(activity)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle(LocaleController.getString(R.string.PcUpdPermTitle));
            b.setMessage(LocaleController.getString(R.string.PcUpdPermText));
            b.setPositiveButton(LocaleController.getString(R.string.PcUpdOpenSettings), (d, w) -> {
                try {
                    prefs().edit().putBoolean("wait_perm", true).apply();
                    Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(s);
                } catch (Throwable t) {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    } catch (Throwable ignore) {
                    }
                }
            });
            b.setNegativeButton(LocaleController.getString(R.string.PcUpdLater), null);
            b.show();
            return; // il file resta scaricato e verificato: al ritorno nell'app l'installazione riparte da sola
        }
        Utilities.globalQueue.postRunnable(() -> {
            String problem = null;
            try {
                installSession(activity.getApplicationContext(), f);
            } catch (Throwable t) {
                problem = String.valueOf(t.getMessage());
            }
            final String fp = problem;
            if (fp != null) {
                AndroidUtilities.runOnUIThread(() -> installViaIntent(activity, f, fp));
            }
        });
    }

    private static void installSession(Context ctx, File f) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("update.apk", 0, f.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                s.fsync(out);
            }
            Intent cb = new Intent(ctx, PcInstallReceiver.class).setAction(PcInstallReceiver.ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, cb, flags);
            s.commit(pending.getIntentSender());
        } catch (Throwable t) {
            s.abandon();
            throw t;
        } finally {
            s.close();
        }
    }

    /** Alternativa: installer di sistema con il file condiviso tramite FileProvider. */
    static void installViaIntent(Activity activity, File f, String why) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            error = LocaleController.formatString(R.string.PcUpdErrInstall, String.valueOf(t.getMessage()));
            errorRetryIsDownload = false;
            changed(State.ERROR);
            presentError(activity);
        }
    }

    /** Chiamato dal ricevitore dell'installazione. */
    static void onInstallResult(int status, String message) {
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            AndroidUtilities.runOnUIThread(() -> PcUi.toast(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PcUpdCancelled)));
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            return;
        }
        error = LocaleController.formatString(R.string.PcUpdErrInstall, message == null ? ("codice " + status) : message);
        errorRetryIsDownload = false;
        changed(State.ERROR);
        Activity a = org.telegram.ui.LaunchActivity.instance;
        if (usable(a)) {
            if (readyFile != null && readyFile.exists()) {
                // seconda strada: installer di sistema
                installViaIntent(a, readyFile, message);
            } else {
                presentError(a);
            }
        }
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) {
                sb.append(String.format(Locale.ROOT, "%02x", x));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
