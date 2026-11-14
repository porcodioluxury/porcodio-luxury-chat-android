package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Archivio del portafoglio: la frase di recupero (BIP39) e' cifrata con una chiave AES-256-GCM dell'Android Keystore
 * (non esportabile) e salvata solo sul dispositivo. Lo sblocco e' protetto da PIN (PBKDF2-SHA512, con blocco progressivo
 * dopo i tentativi errati) o da biometria. Nessun server, nessun invio della chiave. Ogni sessione si richiude da sola.
 */
public final class PcWalletStore {
    private PcWalletStore() {
    }

    private static final String PREFS = "pc_wallet";
    private static final String KS_ALIAS = "pc_wallet_k1";
    private static final long AUTO_LOCK_MS = 120_000;

    private static volatile String sessionMnemonic;
    private static volatile long unlockedAt;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean exists() {
        return prefs().contains("blob");
    }

    public static boolean biometricEnabled() {
        return prefs().getBoolean("bio", false);
    }

    public static void setBiometric(boolean on) {
        prefs().edit().putBoolean("bio", on).apply();
    }

    public static String address() {
        return prefs().getString("addr", "");
    }

    // ---------- sessione ----------
    public static boolean isUnlocked() {
        if (sessionMnemonic == null) {
            return false;
        }
        if (System.currentTimeMillis() - unlockedAt > AUTO_LOCK_MS) {
            lock();
            return false;
        }
        return true;
    }

    public static void touch() {
        unlockedAt = System.currentTimeMillis();
    }

    public static void lock() {
        sessionMnemonic = null;
        synchronized (PcWalletStore.class) {
            addrCache.clear();
        }
    }

    /** Frase di recupero della sessione sbloccata (null se bloccato). */
    public static String mnemonic() {
        return isUnlocked() ? sessionMnemonic : null;
    }

    public static byte[] privateKey() {
        return privateKey(0);
    }

    public static byte[] privateKey(int index) {
        String m = mnemonic();
        if (m == null) {
            return null;
        }
        return PcWalletCore.derivePrivate(PcWalletCore.mnemonicToSeed(m, ""), PcWalletCore.ethPath(index));
    }

    // ---------- indirizzi HD (privacy): si salva solo l'indice dell'ultimo indirizzo consegnato, mai l'elenco ----------
    public static final int MAX_ADDRESSES = 20;
    private static final java.util.HashMap<Integer, String> addrCache = new java.util.HashMap<>();

    /** Indice dell'indirizzo di ricezione corrente (0 = primo). */
    public static int currentIndex() {
        return Math.min(MAX_ADDRESSES - 1, Math.max(0, prefs().getInt("idx", 0)));
    }

    /** Passa al prossimo indirizzo (nuovo ogni volta). Falso se si e' raggiunto il massimo. */
    public static boolean advanceIndex() {
        int i = currentIndex();
        if (i + 1 >= MAX_ADDRESSES) {
            return false;
        }
        prefs().edit().putInt("idx", i + 1).apply();
        return true;
    }

    /** Indirizzo i derivato dalla frase (serve il portafoglio sbloccato); in cache solo nella sessione. */
    public static synchronized String addressAt(int index) {
        if (index == 0 && !isUnlocked()) {
            return address();
        }
        String c = addrCache.get(index);
        if (c != null && isUnlocked()) {
            return c;
        }
        byte[] priv = privateKey(index);
        if (priv == null) {
            return null;
        }
        String a = PcWalletCore.addressFromPrivate(priv);
        Arrays.fill(priv, (byte) 0);
        addrCache.put(index, a);
        return a;
    }

    public static boolean useProxy() {
        return prefs().getBoolean("use_proxy", false);
    }

    public static void setUseProxy(boolean on) {
        prefs().edit().putBoolean("use_proxy", on).apply();
    }

    // ---------- PIN ----------
    private static byte[] pinHash(String pin, byte[] salt) {
        return PcWalletCore.pbkdf2Sha512(pin.getBytes(StandardCharsets.UTF_8), salt, 20000, 32);
    }

    /** Millisecondi ancora da attendere prima di poter riprovare il PIN (0 = si puo'). */
    public static long lockoutLeft() {
        long until = prefs().getLong("lock_until", 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    public static boolean verifyPin(String pin) {
        if (lockoutLeft() > 0) {
            return false;
        }
        SharedPreferences p = prefs();
        byte[] salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP);
        byte[] want = Base64.decode(p.getString("pin", ""), Base64.NO_WRAP);
        boolean ok = java.security.MessageDigest.isEqual(want, pinHash(pin, salt));
        if (ok) {
            p.edit().putInt("fails", 0).remove("lock_until").apply();
        } else {
            int f = p.getInt("fails", 0) + 1;
            long wait = f >= 5 ? Math.min(3600_000L, 30_000L << Math.min(f - 5, 7)) : 0;
            p.edit().putInt("fails", f).putLong("lock_until", wait > 0 ? System.currentTimeMillis() + wait : 0).apply();
        }
        return ok;
    }

    public static boolean unlockWithPin(String pin) {
        if (!verifyPin(pin)) {
            return false;
        }
        return openSession();
    }

    /** Sblocco dopo una verifica biometrica riuscita (fatta dall'interfaccia). */
    public static boolean unlockAfterBiometric() {
        return biometricEnabled() && openSession();
    }

    private static boolean openSession() {
        try {
            sessionMnemonic = decrypt(prefs().getString("blob", ""));
            touch();
            return sessionMnemonic != null;
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e("PcWalletStore: apertura non riuscita");
            return false;
        }
    }

    public static void changePin(String newPin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs().edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("pin", Base64.encodeToString(pinHash(newPin, salt), Base64.NO_WRAP)).putInt("fails", 0).remove("lock_until").apply();
    }

    // ---------- salvataggio / cancellazione ----------
    public static void create(String mnemonic, String pin) throws Exception {
        byte[] priv = PcWalletCore.derivePrivate(PcWalletCore.mnemonicToSeed(mnemonic, ""), PcWalletCore.ETH_PATH);
        String addr = PcWalletCore.addressFromPrivate(priv);
        Arrays.fill(priv, (byte) 0);
        prefs().edit().putString("blob", encrypt(mnemonic)).putString("addr", addr).apply();
        changePin(pin);
        sessionMnemonic = mnemonic;
        touch();
    }

    public static void wipe() {
        lock();
        prefs().edit().clear().apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(KS_ALIAS);
        } catch (Throwable ignore) {
        }
    }

    // ---------- Keystore ----------
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KS_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    b.setIsStrongBoxBacked(false);
                } catch (Throwable ignore) {
                }
            }
            kg.init(b.build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(KS_ALIAS, null);
    }

    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv = c.getIV();
        byte[] all = new byte[1 + iv.length + ct.length];
        all[0] = (byte) iv.length;
        System.arraycopy(iv, 0, all, 1, iv.length);
        System.arraycopy(ct, 0, all, 1 + iv.length, ct.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }

    private static String decrypt(String blob) throws Exception {
        byte[] all = Base64.decode(blob, Base64.NO_WRAP);
        int ivLen = all[0] & 0xff;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 1, ivLen));
        return new String(c.doFinal(all, 1 + ivLen, all.length - 1 - ivLen), StandardCharsets.UTF_8);
    }

    // ---------- controllo grossolano di dispositivo con root ----------
    public static boolean looksRooted() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/local/bin/su", "/data/local/xbin/su"};
        for (String p : paths) {
            if (new java.io.File(p).exists()) {
                return true;
            }
        }
        String tags = Build.TAGS;
        return tags != null && tags.contains("test-keys");
    }
}
