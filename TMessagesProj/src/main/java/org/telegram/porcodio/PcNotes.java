package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** Note private sui contatti: restano solo sul dispositivo (nessuna sincronizzazione, nessun server). */
public class PcNotes {
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("pc_notes", Context.MODE_PRIVATE);
    }

    public static String get(long userId) {
        return prefs().getString("n" + userId, "");
    }

    public static void set(long userId, String text) {
        SharedPreferences.Editor e = prefs().edit();
        if (text == null || text.trim().isEmpty()) {
            e.remove("n" + userId);
        } else {
            e.putString("n" + userId, text.trim());
        }
        e.apply();
    }
}
