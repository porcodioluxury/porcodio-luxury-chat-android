package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import java.util.HashSet;
import java.util.Set;

/**
 * Blocco chat locale: le chat nell'elenco richiedono impronta (o PIN/sblocco del telefono) per aprirsi.
 * Gli id restano solo sul dispositivo. Se l'autenticazione non è disponibile il blocco non si può attivare
 * (e un blocco già attivo non può mai diventare impossibile da rimuovere: la rimozione richiede la stessa autenticazione).
 */
public class PcLock {
    private static final String PREFS = "pc_lock";
    private static final String KEY = "locked";
    private static final long GRACE_MS = 60_000;
    private static Set<String> locked;
    private static final java.util.HashMap<Long, Long> unlockedUntil = new java.util.HashMap<>();

    private static final int AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized Set<String> set() {
        if (locked == null) {
            locked = new HashSet<>(prefs().getStringSet(KEY, new HashSet<>()));
        }
        return locked;
    }

    public static boolean isLocked(long dialogId) {
        return set().contains(String.valueOf(dialogId));
    }

    public static boolean isAvailable() {
        try {
            return BiometricManager.from(ApplicationLoader.applicationContext).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void setLocked(long dialogId, boolean value) {
        if (value) {
            set().add(String.valueOf(dialogId));
        } else {
            set().remove(String.valueOf(dialogId));
        }
        prefs().edit().putStringSet(KEY, new HashSet<>(set())).apply();
    }

    public static boolean recentlyUnlocked(long dialogId) {
        Long t = unlockedUntil.get(dialogId);
        return t != null && SystemClock.elapsedRealtime() < t;
    }

    /** Chiede l'autenticazione; onResult(true) solo se riuscita. */
    public static void authenticate(long dialogId, Runnable onSuccess, Runnable onFail) {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || !isAvailable()) {
            if (onFail != null) onFail.run();
            return;
        }
        try {
            BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    unlockedUntil.put(dialogId, SystemClock.elapsedRealtime() + GRACE_MS);
                    onSuccess.run();
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    if (onFail != null) onFail.run();
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(LocaleController.getString(R.string.PcLockPromptTitle))
                .setSubtitle(LocaleController.getString(R.string.PcLockPromptSub))
                .setAllowedAuthenticators(AUTH)
                .build();
            prompt.authenticate(info);
        } catch (Throwable t) {
            if (onFail != null) onFail.run();
        }
    }

    /** Attiva o toglie il blocco (richiede sempre l'autenticazione). Mostra un avviso se il telefono non ha impronta/PIN. */
    public static void toggleWithAuth(long dialogId, Runnable after) {
        if (!isAvailable()) {
            PcUi.toast(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PcProfileLockUnavailable));
            return;
        }
        final boolean now = isLocked(dialogId);
        authenticate(dialogId, () -> {
            setLocked(dialogId, !now);
            PcUi.toast(ApplicationLoader.applicationContext, LocaleController.getString(!now ? R.string.PcProfileLockOn : R.string.PcOff));
            if (after != null) after.run();
        }, null);
    }
}
