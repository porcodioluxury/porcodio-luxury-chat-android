package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Chat super segrete: secondo livello di cifratura lato client tra due utenti dell'app.
 * Scambio chiavi X25519 (messaggio "PCS1:K:..."), chiave AES-256-GCM derivata con HKDF-SHA256, testo cifrato "PCS1:M:...".
 * Le chiavi private restano solo sul dispositivo (avvolte con una chiave dell'Android Keystore). Funziona sopra qualunque chat
 * privata, comprese le chat segrete end-to-end. Timer di autodistruzione, blocco screenshot, blocco con impronta e cartella nascosta
 * sono impostazioni locali per chat.
 */
public class PcSecret {
    public static final String HELLO = "PCS1:K:";
    public static final String MSG = "PCS1:M:";
    private static final String PREFS = "pc_secret";
    private static final String WRAP_ALIAS = "pc_secret_wrap";

    public static boolean showHidden = false;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String k(String what, int account, long dialogId) {
        return what + ":" + account + ":" + dialogId;
    }

    public static boolean isOn(int account, long dialogId) {
        return prefs().getBoolean(k("on", account, dialogId), false);
    }

    public static boolean isOnAny(long dialogId) {
        for (int a = 0; a < 5; a++) {
            if (isOn(a, dialogId)) {
                return true;
            }
        }
        return false;
    }

    public static int ttl(int account, long dialogId) {
        return prefs().getInt(k("ttl", account, dialogId), 0);
    }

    public static boolean isHidden(long dialogId) {
        for (int a = 0; a < 5; a++) {
            if (prefs().getBoolean(k("hid", a, dialogId), false)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasKey(int account, long dialogId) {
        return sharedKey(account, dialogId) != null;
    }

    /** Elenco dei dialoghi con la funzione attiva su questo account. */
    public static ArrayList<Long> enabledDialogs(int account) {
        ArrayList<Long> res = new ArrayList<>();
        String suffix = ":" + account + ":";
        for (String key : prefs().getAll().keySet()) {
            if (key.startsWith("on" + suffix) && prefs().getBoolean(key, false)) {
                try {
                    res.add(Long.parseLong(key.substring(("on" + suffix).length())));
                } catch (Throwable ignore) {
                }
            }
        }
        return res;
    }

    // ---- chiavi

    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(WRAP_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(WRAP_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            return kg.generateKey();
        }
        return (SecretKey) ks.getKey(WRAP_ALIAS, null);
    }

    static String wrap(byte[] plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length + 1];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return "w" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "p" + Base64.encodeToString(plain, Base64.NO_WRAP);
        }
    }

    static byte[] unwrap(String s) {
        try {
            byte[] raw = Base64.decode(s.substring(1), Base64.NO_WRAP);
            if (s.charAt(0) == 'p') {
                return raw;
            }
            int n = raw[0];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, raw, 1, n));
            return c.doFinal(raw, 1 + n, raw.length - 1 - n);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] privateKey(int account, long dialogId, boolean create) {
        String s = prefs().getString(k("sk", account, dialogId), null);
        if (s != null) {
            return unwrap(s);
        }
        if (!create) {
            return null;
        }
        byte[] priv = PcCrypto.newPrivateKey();
        prefs().edit().putString(k("sk", account, dialogId), wrap(priv)).apply();
        return priv;
    }

    private static final HashMap<String, byte[]> keyCache = new HashMap<>();

    private static byte[] sharedKey(int account, long dialogId) {
        String peer = prefs().getString(k("pk", account, dialogId), null);
        byte[] priv = privateKey(account, dialogId, false);
        if (peer == null || priv == null) {
            return null;
        }
        String ck = k("sh", account, dialogId) + peer;
        synchronized (keyCache) {
            byte[] c = keyCache.get(ck);
            if (c != null) {
                return c;
            }
        }
        try {
            byte[] peerPub = Base64.decode(peer, Base64.NO_WRAP);
            byte[] mine = PcCrypto.publicKey(priv);
            byte[] secret = PcCrypto.sharedSecret(priv, peerPub);
            byte[] salt = PcCrypto.sha256(sortedPair(mine, peerPub));
            byte[] key = PcCrypto.hkdf(secret, salt, "porcodio-luxury-chat-pcs1".getBytes(StandardCharsets.UTF_8), 32);
            synchronized (keyCache) {
                keyCache.put(ck, key);
            }
            return key;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[][] sortedPair(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b) <= 0 ? new byte[][]{a, b} : new byte[][]{b, a};
    }

    /** Codice di sicurezza da confrontare a voce con l'altra persona (8 gruppi esadecimali). */
    public static String safetyCode(int account, long dialogId) {
        String peer = prefs().getString(k("pk", account, dialogId), null);
        byte[] priv = privateKey(account, dialogId, false);
        if (peer == null || priv == null) {
            return null;
        }
        try {
            byte[] h = PcCrypto.sha256(sortedPair(PcCrypto.publicKey(priv), Base64.decode(peer, Base64.NO_WRAP)));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02X", h[i]));
                if (i % 2 == 1 && i < 7) {
                    sb.append(' ');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- invio

    public static void sendHello(int account, long dialogId) {
        byte[] priv = privateKey(account, dialogId, true);
        if (priv == null) {
            return;
        }
        String text = HELLO + Base64.encodeToString(PcCrypto.publicKey(priv), Base64.NO_WRAP);
        prefs().edit().putBoolean(k("sent", account, dialogId), true).apply();
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId, null, null, null, false, null, null, null, true, 0, 0, null, false));
    }

    /** Chiamata da SendMessagesHelper: restituisce il testo da spedire (cifrato) o null se l'invio va bloccato. */
    public static String encryptOutgoing(int account, long dialogId, String text) {
        if (text == null || text.startsWith("PCS1:") || !isOn(account, dialogId)) {
            return text;
        }
        byte[] key = sharedKey(account, dialogId);
        if (key == null) {
            AndroidUtilities.runOnUIThread(() -> PcUi.toast(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PcSecretWaitKey)));
            return null;
        }
        try {
            int t = ttl(account, dialogId);
            long exp = t > 0 ? System.currentTimeMillis() / 1000 + t : 0;
            byte[] blob = PcCrypto.seal(key, (exp + "|" + text).getBytes(StandardCharsets.UTF_8));
            return MSG + Base64.encodeToString(blob, Base64.NO_WRAP);
        } catch (Throwable e) {
            return null;
        }
    }

    // ---- ricezione / visualizzazione

    private static final HashMap<String, String> shownCache = new HashMap<>();

    /** Testo da mostrare per un messaggio (chiamata da MessageObject). Gestisce anche lo scambio chiavi. */
    /**
     * Testo da mostrare nelle notifiche (anche push, Wear e risposta rapida): per le chat super segrete mai il contenuto,
     * solo "Nuovo messaggio segreto". Vale per i messaggi PCS1 (testo cifrato o handshake) e per ogni messaggio di testo
     * in una chat con la funzione attiva. Null = notifica normale.
     */
    public static String notifText(MessageObject mo) {
        try {
            if (mo == null || mo.messageOwner == null) {
                return null;
            }
            String raw = mo.messageOwner.message;
            boolean secret = raw != null && raw.startsWith("PCS1:");
            if (!secret) {
                long did = mo.getDialogId();
                secret = did != 0 && mo.messageOwner.media == null && !android.text.TextUtils.isEmpty(raw) && isOnAny(did);
            }
            return secret ? LocaleController.getString(R.string.PcSecretNotif) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String display(int account, MessageObject mo, String raw) {
        if (raw == null || !raw.startsWith("PCS1:")) {
            return raw;
        }
        long dialogId = mo.getDialogId();
        if (dialogId == 0 || mo.messageOwner == null) {
            return raw;
        }
        if (raw.startsWith(HELLO)) {
            if (!mo.isOutOwner()) {
                onHello(account, dialogId, raw.substring(HELLO.length()).trim());
            }
            return LocaleController.getString(R.string.PcSecretHandshake);
        }
        if (raw.startsWith(MSG)) {
            byte[] key = sharedKey(account, dialogId);
            if (key == null) {
                return LocaleController.getString(R.string.PcSecretNoKey);
            }
            try {
                String plain;
                String ck = account + ":" + dialogId + ":" + mo.getId() + ":" + raw.hashCode();
                synchronized (shownCache) {
                    plain = shownCache.get(ck);
                }
                if (plain == null) {
                    plain = new String(PcCrypto.open(key, Base64.decode(raw.substring(MSG.length()), Base64.NO_WRAP)), StandardCharsets.UTF_8);
                    synchronized (shownCache) {
                        if (shownCache.size() > 2000) {
                            shownCache.clear();
                        }
                        shownCache.put(ck, plain);
                    }
                }
                int bar = plain.indexOf('|');
                long exp = bar > 0 ? Long.parseLong(plain.substring(0, bar)) : 0;
                String text = bar >= 0 ? plain.substring(bar + 1) : plain;
                if (exp > 0 && System.currentTimeMillis() / 1000 > exp) {
                    return LocaleController.getString(R.string.PcSecretExpired);
                }
                return exp > 0 ? text + "  \u23F3" : text;
            } catch (Throwable t) {
                return LocaleController.getString(R.string.PcSecretNoKey);
            }
        }
        return raw;
    }

    private static void onHello(int account, long dialogId, String peerPub) {
        final String key = k("pk", account, dialogId);
        if (peerPub.equals(prefs().getString(key, null))) {
            return;
        }
        try {
            if (Base64.decode(peerPub, Base64.NO_WRAP).length != 32) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        prefs().edit().putString(key, peerPub).apply();
        synchronized (keyCache) {
            keyCache.clear();
        }
        final boolean alreadySent = prefs().getBoolean(k("sent", account, dialogId), false);
        final boolean wasOn = isOn(account, dialogId);
        prefs().edit().putBoolean(k("on", account, dialogId), true).apply();
        if (!alreadySent) {
            AndroidUtilities.runOnUIThread(() -> sendHello(account, dialogId));
        }
        if (!wasOn) {
            AndroidUtilities.runOnUIThread(() -> PcUi.toast(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PcSecretActivated)));
        }
    }

    // ---- impostazioni locali

    public static void setOn(int account, long dialogId, boolean on) {
        SharedPreferences.Editor e = prefs().edit();
        e.putBoolean(k("on", account, dialogId), on);
        if (!on) {
            e.remove(k("sk", account, dialogId)).remove(k("pk", account, dialogId)).remove(k("sent", account, dialogId));
            e.remove(k("ttl", account, dialogId)).remove(k("hid", account, dialogId));
            synchronized (keyCache) {
                keyCache.clear();
            }
        }
        e.apply();
        if (on) {
            sendHello(account, dialogId);
        }
    }

    public static void setTtl(int account, long dialogId, int seconds) {
        prefs().edit().putInt(k("ttl", account, dialogId), seconds).apply();
    }

    public static void setHidden(int account, long dialogId, boolean hidden) {
        prefs().edit().putBoolean(k("hid", account, dialogId), hidden).apply();
        PcHome.postReload(account);
    }

    private static final int[] TTL_VALUES = {0, 10, 60, 3600, 86400};

    private static String ttlName(int seconds) {
        switch (seconds) {
            case 10: return LocaleController.getString(R.string.PcSecretTtl10s);
            case 60: return LocaleController.getString(R.string.PcSecretTtl1m);
            case 3600: return LocaleController.getString(R.string.PcSecretTtl1h);
            case 86400: return LocaleController.getString(R.string.PcSecretTtl1d);
            default: return LocaleController.getString(R.string.PcOff);
        }
    }

    /** Menu "Super segreta" della chat. */
    public static void showMenu(BaseFragment fragment, int account, long dialogId, Runnable after) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final boolean on = isOn(account, dialogId);
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        names.add(LocaleController.getString(on ? R.string.PcSecretTurnOff : R.string.PcSecretTurnOn));
        actions.add(() -> {
            setOn(account, dialogId, !on);
            PcUi.toast(ApplicationLoader.applicationContext, LocaleController.getString(!on ? R.string.PcSecretStarted : R.string.PcOff));
        });
        if (on) {
            names.add(LocaleController.getString(R.string.PcSecretTimer) + ": " + ttlName(ttl(account, dialogId)));
            actions.add(() -> pickTtl(fragment, account, dialogId));
            names.add(LocaleController.getString(PcLock.isLocked(dialogId) ? R.string.PcSecretUnlockFp : R.string.PcSecretLockFp));
            actions.add(() -> PcLock.toggleWithAuth(dialogId, null));
            names.add(LocaleController.getString(isHidden(dialogId) ? R.string.PcSecretUnhide : R.string.PcSecretHide));
            actions.add(() -> {
                if (isHidden(dialogId)) {
                    setHidden(account, dialogId, false);
                } else if (PcLock.isAvailable()) {
                    PcLock.authenticate(dialogId, () -> setHidden(account, dialogId, true), null);
                } else {
                    PcUi.toast(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PcProfileLockUnavailable));
                }
            });
            String code = safetyCode(account, dialogId);
            names.add(LocaleController.getString(R.string.PcSecretSafety));
            actions.add(() -> {
                AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
                b.setTitle(LocaleController.getString(R.string.PcSecretSafety));
                b.setMessage(code == null ? LocaleController.getString(R.string.PcSecretWaitKey)
                    : code + "\n\n" + LocaleController.getString(R.string.PcSecretSafetyHow));
                b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                fragment.showDialog(b.create());
            });
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcSecretTitle));
        b.setItems(names.toArray(new CharSequence[0]), (d, which) -> {
            actions.get(which).run();
            if (after != null) {
                after.run();
            }
        });
        fragment.showDialog(b.create());
    }

    private static void pickTtl(BaseFragment fragment, int account, long dialogId) {
        CharSequence[] items = new CharSequence[TTL_VALUES.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = ttlName(TTL_VALUES[i]);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcSecretTimer));
        b.setItems(items, (d, which) -> setTtl(account, dialogId, TTL_VALUES[which]));
        fragment.showDialog(b.create());
    }
}
