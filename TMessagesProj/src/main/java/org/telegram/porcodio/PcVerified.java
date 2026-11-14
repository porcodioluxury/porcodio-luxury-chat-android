package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

import java.util.HashSet;

/**
 * Stato "verificato" (spunta blu). Fonti: elenco di ID in pc_config (assegnato dal proprietario) e diritto personale locale,
 * che si imposta solo dopo pagamento + verifica d'identita' (flusso spento: PcFlags.STRIPE_PAYMENTS). Alimenta PcRanking:
 * i verificati ricevono la priorita' in Home/Esplora e superano il filtro "Solo verificati" di "Chi puo' scrivermi".
 */
public final class PcVerified {
    private PcVerified() {
    }

    private static HashSet<Long> ids;

    private static synchronized HashSet<Long> ids() {
        if (ids == null) {
            ids = new HashSet<>();
            String raw = ApplicationLoader.applicationContext.getString(R.string.pc_verified_user_ids);
            for (String p : raw.split("[,\\s]+")) {
                try {
                    if (!p.isEmpty()) {
                        ids.add(Long.parseLong(p));
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return ids;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("pc_verified", Context.MODE_PRIVATE);
    }

    public static boolean isVerified(long userId) {
        return ids().contains(userId) || prefs().getLong("self_until", 0) > System.currentTimeMillis() && prefs().getLong("self_id", -1) == userId;
    }

    /** Da chiamare solo dopo pagamento riuscito e verifica d'identita' superata (non raggiungibile con i pagamenti spenti). */
    static void grantSelf(long userId, long untilMs) {
        prefs().edit().putLong("self_id", userId).putLong("self_until", untilMs).apply();
    }

    public static void install() {
        PcRanking.verifiedSource = PcVerified::isVerified;
    }
}
