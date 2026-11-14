package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.HashSet;

/**
 * Chi usa PORCODIO LUXURY Chat. Rilevamento gratuito, senza server: (1) marcatore nella bio del profilo (aggiunto in silenzio
 * dall'app a chi la usa) letto quando il profilo completo viene caricato, (2) canale di pubblicazione @plcb_<uid> verificato
 * (PcBoard.discover, in cache). Chi e' riconosciuto riceve un rombo accanto al nome ovunque venga mostrato (lista chat,
 * header, profilo, feed), tramite UserObject.getUserName.
 */
public final class PcBadge {
    private PcBadge() {
    }

    public static final String SYMBOL = "\u25C6";
    private static final String PREFS = "pc_badge";
    private static HashSet<Long> users;
    private static boolean inited;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized HashSet<Long> set() {
        if (users == null) {
            users = new HashSet<>();
            for (String p : prefs().getString("ids", "").split(",")) {
                try {
                    if (!p.isEmpty()) {
                        users.add(Long.parseLong(p));
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return users;
    }

    public static boolean has(long uid) {
        return uid > 0 && set().contains(uid);
    }

    public static void add(long uid) {
        if (uid <= 0) {
            return;
        }
        synchronized (PcBadge.class) {
            if (!set().add(uid)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Long l : users) {
                sb.append(l).append(',');
            }
            prefs().edit().putString("ids", sb.toString()).apply();
        }
    }

    public static int count() {
        return set().size();
    }

    /** Da chiamare una volta all'avvio: ascolta il caricamento dei profili completi e legge il marcatore nella bio. */
    public static void init() {
        if (inited) {
            return;
        }
        inited = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final int account = a;
            NotificationCenter.getInstance(account).addObserver((id, acc, args) -> {
                if (id == NotificationCenter.userInfoDidLoad && args != null && args.length > 1 && args[0] instanceof Long && args[1] instanceof TLRPC.UserFull) {
                    TLRPC.UserFull f = (TLRPC.UserFull) args[1];
                    if (PcBoard.aboutHasMarker(f.about)) {
                        add((Long) args[0]);
                    }
                }
            }, NotificationCenter.userInfoDidLoad);
        }
    }
}
