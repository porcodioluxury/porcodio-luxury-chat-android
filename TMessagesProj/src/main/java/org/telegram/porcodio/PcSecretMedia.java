package org.telegram.porcodio;

import android.net.Uri;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.SendMessagesHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Chat super segrete: i file (foto, video, documenti) escono cifrati con AES-256-GCM (chiave dell'intesa X25519) e arrivano
 * come allegato "segreto.pcs". Se la cifratura non e' possibile (chiave non ancora scambiata, file illeggibile) l'invio si
 * BLOCCA: non esce mai un file in chiaro da una chat con la funzione attiva. Il destinatario apre il file con un tocco: si
 * decifra in una cartella privata e si apre con il visualizzatore di sistema.
 */
public final class PcSecretMedia {
    private PcSecretMedia() {
    }

    public static final String EXT = ".pcs";

    public static File outDir() {
        File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/pcs_out");
        d.mkdirs();
        return d;
    }

    public static File viewDir() {
        File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/pcs_view");
        d.mkdirs();
        return d;
    }

    private static boolean isOurs(String path) {
        return path != null && path.endsWith(EXT) && path.contains("/cache/pcs_out/");
    }

    public static boolean active(int account, long dialogId) {
        return PcSecret.isOn(account, dialogId);
    }

    /** Cifra i percorsi dati. Restituisce i file cifrati, oppure null se l'invio va bloccato (avvisando l'utente). */
    public static ArrayList<String> encryptAll(int account, long dialogId, ArrayList<String> paths, ArrayList<Uri> uris) {
        byte[] key = PcSecret.mediaKey(account, dialogId);
        if (key == null) {
            AndroidUtilities.runOnUIThread(() -> PcUi.toast(ApplicationLoader.applicationContext, "Chat segreta: scambio chiavi non ancora completato. File non inviato."));
            return null;
        }
        ArrayList<String> src = new ArrayList<>();
        if (paths != null) {
            src.addAll(paths);
        }
        if (uris != null) {
            for (Uri u : uris) {
                String p = u == null ? null : MediaController.copyFileToCache(u, "tmp");
                src.add(p);
            }
        }
        ArrayList<String> out = new ArrayList<>();
        for (String p : src) {
            if (isOurs(p)) {
                out.add(p);
                continue;
            }
            String enc = encryptFile(key, p);
            if (enc == null) {
                AndroidUtilities.runOnUIThread(() -> PcUi.toast(ApplicationLoader.applicationContext, "Chat segreta: impossibile cifrare il file. Non inviato."));
                return null;
            }
            out.add(enc);
        }
        return out;
    }

    private static String encryptFile(byte[] key, String path) {
        if (path == null) {
            return null;
        }
        try {
            File f = new File(path);
            if (!f.exists()) {
                return null;
            }
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String mime = dot >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase()) : null;
            File out = new File(outDir(), "segreto-" + Long.toHexString(System.nanoTime()) + EXT);
            try (FileInputStream in = new FileInputStream(f); FileOutputStream os = new FileOutputStream(out)) {
                PcSecretMediaCore.encrypt(key, in, name, mime, os);
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e("PcSecretMedia: cifratura non riuscita");
            return null;
        }
    }

    /** Decifra un allegato .pcs ricevuto. Restituisce il file in chiaro (nel suo nome originale) o null. */
    public static File decryptForView(int account, long dialogId, File encrypted) {
        byte[] key = PcSecret.mediaKey(account, dialogId);
        if (key == null || encrypted == null || !encrypted.exists()) {
            return null;
        }
        try {
            File tmp = new File(viewDir(), "tmp-" + Long.toHexString(System.nanoTime()));
            PcSecretMediaCore.Meta meta;
            try (FileInputStream in = new FileInputStream(encrypted); FileOutputStream os = new FileOutputStream(tmp)) {
                meta = PcSecretMediaCore.decrypt(key, in, os);
            }
            String safe = meta.name == null ? "file" : meta.name.replaceAll("[^A-Za-z0-9._ -]", "_");
            if (safe.isEmpty() || safe.startsWith(".")) {
                safe = "file" + safe;
            }
            File out = new File(viewDir(), safe);
            if (out.exists()) {
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                return null;
            }
            return out;
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e("PcSecretMedia: decifratura non riuscita");
            return null;
        }
    }

    /** Svuota i file in chiaro aperti (chiamato all'avvio). */
    public static void clearViewCache() {
        try {
            File[] fs = viewDir().listFiles();
            if (fs != null) {
                for (File f : fs) {
                    f.delete();
                }
            }
        } catch (Throwable ignore) {
        }
    }
}
