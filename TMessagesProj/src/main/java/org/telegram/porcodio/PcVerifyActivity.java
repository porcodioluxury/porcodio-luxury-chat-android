package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

/** Spunta blu: piani (4,99 €/mese, 49,99 €/anno), vantaggi e stato. L'acquisto e' dietro PcFlags.STRIPE_PAYMENTS (spento). */
public class PcVerifyActivity extends PcListFragment {
    private static class Row {
        int type; // 0 hero, 1 plan, 2 benefit, 3 cta, 4 note
        String a, b;
        PcPayments.Kind kind;
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    private static String t(String it, String en) {
        java.util.Locale l = LocaleController.getInstance().getCurrentLocale();
        return l != null && !"it".equals(l.getLanguage()) ? en : it;
    }

    @Override
    protected String screenTitle() {
        return t("Spunta blu", "Blue check");
    }

    private Row row(int type, String a, String b, PcPayments.Kind k) {
        Row r = new Row();
        r.type = type;
        r.a = a;
        r.b = b;
        r.kind = k;
        rows.add(r);
        return r;
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(0xFF121212);
        boolean verified = PcVerified.isVerified(UserConfig.getInstance(currentAccount).getClientUserId());
        row(0, verified ? t("Il tuo account e' verificato", "Your account is verified") : t("Verifica il tuo account", "Verify your account"), t("La spunta blu certifica che sei tu e ti da' visibilita' e strumenti in piu'.", "The blue check proves it is you and gives you more reach and tools."), null);
        row(1, t("Mensile", "Monthly"), PcPayments.formatEur(PcPayments.BLUE_MONTH_CENTS) + t(" al mese", " / month"), PcPayments.Kind.BLUE_MONTH);
        row(1, t("Annuale", "Yearly"), PcPayments.formatEur(PcPayments.BLUE_YEAR_CENTS) + t(" all'anno · risparmi ", " / year · save ") + PcPayments.formatEur(PcPayments.yearlySavingCents()), PcPayments.Kind.BLUE_YEAR);
        row(2, t("Spunta blu accanto al nome", "Blue check next to your name"), null, null);
        row(2, t("Priorita' nella Home e in Esplora", "Priority in Home and Explore"), null, null);
        row(2, t("Passi il filtro \"Solo verificati\" di chi puo' scriverti", "Pass the \"Verified only\" filter of who can write to you"), null, null);
        row(2, t("Dashboard professionale con le visualizzazioni", "Professional dashboard with views"), null, null);
        row(3, PcFlags.BLUE_CHECK_PAYMENT_LINKS ? t("Continua al pagamento", "Continue to payment") : t("In arrivo", "Coming soon"), null, null);
        row(4, t("Come funziona: paghi l'abbonamento sulla pagina sicura di Stripe (si apre nel browser), poi verifichiamo la tua identita' e attiviamo la spunta sul tuo account. Disdici quando vuoi.", "How it works: you pay the subscription on Stripe's secure page (opens in your browser), then we verify your identity and turn the check on for your account. Cancel anytime."), null, null);
        notifyData();
        return v;
    }

    @Override
    protected int itemCount() {
        return rows.size();
    }

    @Override
    protected int itemViewType(int p) {
        return rows.get(p).type;
    }

    @Override
    protected boolean isRowEnabled(int p) {
        return rows.get(p).type == 1 || rows.get(p).type == 3;
    }

    private PcPayments.Kind selected = PcPayments.Kind.BLUE_YEAR;

    @Override
    protected void onRowClick(View view, int p) {
        Row r = rows.get(p);
        if (r.type == 1) {
            selected = r.kind;
            notifyData();
        } else if (r.type == 3) {
            if (!PcFlags.BLUE_CHECK_PAYMENT_LINKS) {
                PcUi.toast(getParentActivity(), t("I pagamenti non sono ancora attivi", "Payments are not active yet"));
                return;
            }
            String url = PcPayments.paymentLinkUrl(selected,
                getParentActivity().getString(R.string.pc_pay_link_month), getParentActivity().getString(R.string.pc_pay_link_year),
                UserConfig.getInstance(currentAccount).getClientUserId());
            if (url.isEmpty()) {
                PcUi.toast(getParentActivity(), t("Link di pagamento non disponibile", "Payment link not available"));
                return;
            }
            PcLinks.open(getParentActivity(), url);
        }
    }

    @Override
    protected View createRow(Context c, int vt) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return b;
    }

    private TextView tv(Context c, String s, int sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(AndroidUtilities.bold());
        return t;
    }

    @Override
    protected void bindRow(View row, int vt, int p) {
        Context c = row.getContext();
        LinearLayout b = (LinearLayout) row;
        b.removeAllViews();
        Row r = rows.get(p);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        if (r.type == 0) {
            b.setPadding(dp(20), dp(20), dp(20), dp(10));
            b.addView(tv(c, "✓", 40, true, 0xFF3797F0));
            b.addView(tv(c, r.a, 22, true, 0xFFF2F2F2));
            b.addView(tv(c, r.b, 14, false, 0xFFBFBFBF));
        } else if (r.type == 1) {
            boolean sel = r.kind == selected;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            g.setColor(0xFF1A1A1A);
            g.setStroke(Math.max(1, dp(sel ? 2 : 1)), sel ? 0xFF3797F0 : 0xFF2F3442);
            card.setBackground(g);
            card.addView(tv(c, r.a, 16, true, 0xFFF2F2F2));
            card.addView(tv(c, r.b, 14, false, 0xFFBFBFBF));
            b.addView(card);
        } else if (r.type == 2) {
            b.setPadding(dp(24), dp(6), dp(20), dp(6));
            b.addView(tv(c, "•  " + r.a, 14, false, 0xFFF2F2F2));
        } else if (r.type == 3) {
            b.setPadding(dp(16), dp(16), dp(16), dp(6));
            TextView cta = tv(c, r.a, 16, true, 0xFFFFFFFF);
            cta.setGravity(android.view.Gravity.CENTER);
            cta.setPadding(0, dp(14), 0, dp(14));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(26));
            g.setColor(PcFlags.BLUE_CHECK_PAYMENT_LINKS ? 0xFF3797F0 : 0xFF2F3442);
            cta.setBackground(g);
            b.addView(cta);
        } else {
            b.setPadding(dp(20), dp(12), dp(20), dp(30));
            b.addView(tv(c, r.a, 12, false, 0xFF8A8F9C));
        }
    }
}
