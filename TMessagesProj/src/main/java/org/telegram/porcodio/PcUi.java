package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** Piccoli attrezzi grafici condivisi dalle schermate Porcodio (pannelli 4dp con bordo 1px, pulsanti, etichette). */
public class PcUi {
    public static GradientDrawable panel(boolean raised) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.getColor(raised ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray));
        g.setCornerRadius(dp(4));
        g.setStroke(Math.max(1, (int) AndroidUtilities.density), Theme.getColor(Theme.key_divider));
        return g;
    }

    public static GradientDrawable filled(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(4));
        return g;
    }

    public static TextView label(Context context, String text, float size, boolean bold, int colorKey) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Theme.getColor(colorKey));
        if (bold) {
            t.setTypeface(AndroidUtilities.bold());
        }
        return t;
    }

    /** Pulsante testuale con bordo; primary = riempito chiaro con testo scuro. */
    public static TextView button(Context context, String text, boolean primary, View.OnClickListener l) {
        TextView t = new TextView(context);
        t.setText(text.toUpperCase());
        t.setTextSize(12);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, dp(8), 0);
        if (primary) {
            t.setBackground(filled(Theme.getColor(Theme.key_chats_actionBackground)));
            t.setTextColor(Theme.getColor(Theme.key_chats_actionIcon));
        } else {
            t.setBackground(panel(true));
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        t.setOnClickListener(l);
        return t;
    }

    /** Piccola targhetta (es. PORCODIO). */
    public static TextView badge(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(10);
        t.setTypeface(AndroidUtilities.bold());
        t.setLetterSpacing(0.08f);
        t.setSingleLine(true);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        t.setBackground(panel(false));
        return t;
    }

    public static void toast(Context context, String text) {
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }
}
