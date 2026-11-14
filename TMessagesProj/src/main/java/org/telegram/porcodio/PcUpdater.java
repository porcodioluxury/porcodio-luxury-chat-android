package org.telegram.porcodio;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Aggiornamenti interni di Porcodio Luxury Chat. Legge un manifest JSON pubblico (GitHub Releases "latest", gratuito):
 * {"versionCode":7305,"versionName":"13.2.2-v6","apkUrl":"https://github.com/.../releases/download/...","sha256":"...","size":123,"changelog":"..."}
 * versionCode è il codice base (quello di gradle.properties; l'APK installato ha codice*10+ABI).
 * Scarica con DownloadManager, controlla lo SHA-256 e passa il file all'installer di sistema (FileProvider).
 * L'APK deve provenire dalle release di questo repository e avere lo stesso hash del manifest.
 */
public class PcUpdater {
    public static final String MANIFEST_URL = "https://github.com/porcodioluxury/porcodio-luxury-chat-android/releases/latest/download/update.json";
    private static final String APK_PREFIX = "https://github.com/porcodioluxury/porcodio-luxury-chat-android/releases/download/";
    private static final long AUTO_EVERY_MS = 6L * 3600_000L;
    private static final String PREFS = "pc_updater";

    public static class Info {
        public int versionCode;
        public String versionName, apkUrl, sha256, changelog;
        public long size;
    }

    private interface Done<T> {
        void run(T value, String error);
    }

    private static boolean busy;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int currentCode() {
        try {
            android.content.pm.PackageInfo p = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            long v = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            return (int) (v / 10);
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    public static String currentName() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Controllo silenzioso all'avvio, al massimo ogni 6 ore. Mostra il dialogo solo se c'è una versione più nuova. */
    public static void autoCheck(Activity activity) {
        if (activity == null || System.currentTimeMillis() - prefs().getLong("last", 0) < AUTO_EVERY_MS) {
            return;
        }
        check(activity, false);
    }

    /** Da Impostazioni: dice sempre l'esito. */
    public static void checkNow(Activity activity) {
        check(activity, true);
    }

    private static void check(Activity activity, boolean manual) {
        if (busy) {
            return;
        }
        busy = true;
        if (manual) {
            PcUi.toast(activity, LocaleController.getString(R.string.PcUpdChecking));
        }
        fetch((info, error) -> {
            busy = false;
            prefs().edit().putLong("last", System.currentTimeMillis()).apply();
            if (activity.isFinishing()) {
                return;
            }
            if (error != null) {
                if (manual) {
                    PcUi.toast(activity, LocaleController.getString(R.string.PcUpdFailed));
                }
                return;
            }
            if (info.versionCode > currentCode()) {
                if (manual || info.versionCode != prefs().getInt("skipped", 0)) {
                    showDialog(activity, info);
                }
            } else if (manual) {
                PcUi.toast(activity, LocaleController.formatString(R.string.PcUpdUpToDate, currentName()));
            }
        });
    }

    // ---- manifest
    private static void fetch(Done<Info> done) {
        Utilities.globalQueue.postRunnable(() -> {
            Info info = null;
            String error = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "PorcodioLuxuryChat/" + currentName());
                c.setRequestProperty("Cache-Control", "no-cache");
                if (c.getResponseCode() != 200) {
                    throw new Exception("HTTP " + c.getResponseCode());
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 64 * 1024) {
                            throw new Exception("manifest troppo grande");
                        }
                    }
                }
                info = parse(new JSONObject(out.toString("UTF-8")));
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            final Info fi = info;
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> done.run(fi, fe));
        });
    }

    static Info parse(JSONObject o) throws Exception {
        Info i = new Info();
        i.versionCode = o.getInt("versionCode");
        i.versionName = o.getString("versionName");
        i.apkUrl = o.getString("apkUrl");
        i.sha256 = o.getString("sha256").toLowerCase(Locale.ROOT);
        i.size = o.optLong("size", 0);
        i.changelog = o.optString("changelog", "");
        if (i.versionCode < 1 || !i.sha256.matches("[0-9a-f]{64}") || !i.apkUrl.startsWith(APK_PREFIX) || !i.apkUrl.toLowerCase(Locale.ROOT).contains(".apk")) {
            throw new Exception("manifest non valido");
        }
        return i;
    }

    // ---- interfaccia
    private static void showDialog(Activity activity, Info info) {
        StringBuilder msg = new StringBuilder(LocaleController.formatString(R.string.PcUpdMessage, info.versionName));
        if (info.size > 0) {
            msg.append(" (").append(String.format(Locale.US, "%.1f MB", info.size / 1048576.0)).append(")");
        }
        if (!info.changelog.isEmpty()) {
            msg.append("\n\n").append(info.changelog);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PcUpdTitle));
        b.setMessage(msg.toString());
        b.setPositiveButton(LocaleController.getString(R.string.PcUpdNow), (d, w) -> download(activity, info));
        b.setNegativeButton(LocaleController.getString(R.string.PcUpdLater), (d, w) -> prefs().edit().putInt("skipped", info.versionCode).apply());
        b.show();
    }

    private static File target(Context ctx, Info info) {
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(dir, "porcodio-luxury-chat-" + info.versionCode + ".apk");
    }

    private static void download(Activity activity, Info info) {
        final File f = target(activity, info);
        // già scaricato e verificato in un tentativo precedente (es. dopo aver concesso il permesso di installazione)
        Utilities.globalQueue.postRunnable(() -> {
            final boolean ok = f.exists() && info.sha256.equals(sha256(f));
            AndroidUtilities.runOnUIThread(() -> {
                if (ok) {
                    install(activity, f);
                } else {
                    startDownload(activity, info, f);
                }
            });
        });
    }

    private static void startDownload(Activity activity, Info info, File f) {
        if (f.exists()) {
            f.delete();
        }
        final DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.apkUrl));
        req.setTitle("Porcodio Luxury Chat " + info.versionName);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, f.getName());
        req.setMimeType("application/vnd.android.package-archive");
        final long id = dm.enqueue(req);

        final boolean[] cancelled = {false};
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.PcUpdTitle));
        b.setMessage(LocaleController.formatString(R.string.PcUpdDownloading, 0));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            cancelled[0] = true;
            dm.remove(id);
        });
        final AlertDialog dialog = b.show();
        dialog.setCanceledOnTouchOutside(false);

        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (cancelled[0]) {
                return;
            }
            int status = -1;
            long got = 0, total = 0;
            try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (cur != null && cur.moveToFirst()) {
                    status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                }
            } catch (Throwable ignore) {
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                dialog.setMessage(LocaleController.getString(R.string.PcUpdVerifying));
                verifyThenInstall(activity, info, f, dialog);
            } else if (status == DownloadManager.STATUS_FAILED || status == -1) {
                dialog.dismiss();
                PcUi.toast(activity, LocaleController.getString(R.string.PcUpdFailed));
            } else {
                if (total > 0) {
                    dialog.setMessage(LocaleController.formatString(R.string.PcUpdDownloading, (int) (got * 100 / total)));
                }
                AndroidUtilities.runOnUIThread(poll[0], 500);
            }
        };
        AndroidUtilities.runOnUIThread(poll[0], 700);
    }

    private static void verifyThenInstall(Activity activity, Info info, File f, AlertDialog dialog) {
        Utilities.globalQueue.postRunnable(() -> {
            final boolean ok = f.exists() && info.sha256.equals(sha256(f));
            AndroidUtilities.runOnUIThread(() -> {
                dialog.dismiss();
                if (ok) {
                    install(activity, f);
                } else {
                    f.delete();
                    PcUi.toast(activity, LocaleController.getString(R.string.PcUpdBadHash));
                }
            });
        });
    }

    private static void install(Activity activity, File f) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                PcUi.toast(activity, LocaleController.getString(R.string.PcUpdAllow));
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(s);
                return; // il file resta scaricato e verificato: al prossimo "Aggiorna" si installa subito
            }
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            PcUi.toast(activity, LocaleController.getString(R.string.PcUpdFailed));
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
