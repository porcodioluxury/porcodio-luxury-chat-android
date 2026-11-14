package org.telegram.porcodio;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.browser.Browser;

/** Link cliccabili (bio, link del profilo, didascalie): Linkify + ClickableSpan che apre nel browser dell'app/di sistema. */
public final class PcLinks {
    private PcLinks() {
    }

    public static void linkify(TextView tv) {
        CharSequence cs = tv.getText();
        SpannableString ss = new SpannableString(cs);
        Linkify.addLinks(ss, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        // anche "t.me/..." e domini senza schema sono coperti da WEB_URLS; sostituisco gli URLSpan con uno che usa il browser dell'app
        URLSpan[] spans = ss.getSpans(0, ss.length(), URLSpan.class);
        for (URLSpan u : spans) {
            final int st = ss.getSpanStart(u), en = ss.getSpanEnd(u);
            final String url = u.getURL();
            ss.removeSpan(u);
            ss.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    open(widget.getContext(), url);
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(0xFF9DAAC8);
                    ds.setUnderlineText(true);
                }
            }, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(ss, TextView.BufferType.SPANNABLE);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setLinksClickable(true);
        tv.setFocusable(false);
    }

    public static void open(Context context, String url) {
        try {
            String u = url.trim();
            if (u.contains("@") && !u.contains("://") && !u.startsWith("mailto:")) {
                u = "mailto:" + u;
            } else if (!u.contains("://") && !u.startsWith("mailto:") && !u.startsWith("tel:")) {
                u = "https://" + u;
            }
            Browser.openUrl(context, u);
        } catch (Throwable t) {
            try {
                context.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignore) {
            }
        }
    }
}
