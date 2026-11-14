package org.telegram.porcodio;

import android.os.Bundle;

import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ProfileActivity;

/** Punto unico di intercettazione della navigazione: profilo Porcodio e blocco chat. */
public class PcHook {
    public static final String STOCK = "pc_stock";

    private static boolean onlyKnownProfileKeys(Bundle args) {
        for (String k : args.keySet()) {
            if (!"user_id".equals(k) && !"expandPhoto".equals(k) && !"ignoreBlocked".equals(k) && !"from_chat".equals(k)) {
                return false;
            }
        }
        return true;
    }

    /** @return true se la navigazione è stata gestita qui (da non proseguire). */
    public static boolean intercept(INavigationLayout layout, INavigationLayout.NavigationParams params) {
        final BaseFragment f = params.fragment;
        if (f == null) {
            return false;
        }
        final Bundle args = f.getArguments();
        if (f instanceof ProfileActivity && args != null && !args.getBoolean(STOCK, false)
                && args.getLong("user_id", 0) != 0 && onlyKnownProfileKeys(args)) {
            params.fragment = new PcProfileActivity(args);
            return false;
        }
        if (f instanceof ChatActivity && args != null && !args.getBoolean("pc_unlocked", false)) {
            long id = args.getLong("user_id", 0);
            if (id == 0) {
                long chatId = args.getLong("chat_id", 0);
                id = chatId != 0 ? -chatId : 0;
            }
            if (id != 0 && PcLock.isLocked(id) && !PcLock.recentlyUnlocked(id)) {
                if (params.preview) {
                    return true; // niente anteprima di una chat bloccata
                }
                final long did = id;
                PcLock.authenticate(did, () -> {
                    args.putBoolean("pc_unlocked", true);
                    layout.presentFragment(params);
                }, null);
                return true;
            }
        }
        return false;
    }
}
