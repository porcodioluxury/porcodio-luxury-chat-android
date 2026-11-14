package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Portafoglio non-custodial su Base: crea o importa (BIP39), saldi ETH e PORCO, indirizzo + QR, invio con stima del gas e doppia conferma,
 * storico, backup della frase con avvisi. Chiavi solo sul dispositivo (Keystore), sblocco con PIN o biometria.
 * Accesso: menu ☰ del profilo e "Invia denaro" negli allegati della chat.
 */
public class PcWalletActivity extends PcListFragment {
    private static final int T_HERO = 0, T_ACTIONS = 1, T_ADDR = 2, T_HEAD = 3, T_TX = 4, T_ROW = 5, T_NOTE = 6;

    private static class Item {
        int type;
        String a, b;
        Runnable click;
        PcWalletRpc.Tx tx;

        Item(int type, String a, String b, Runnable click) {
            this.type = type;
            this.a = a;
            this.b = b;
            this.click = click;
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private List<String> words;
    private BigInteger ethWei, porcoUnits;
    private String balanceError;
    private ArrayList<PcWalletRpc.Tx> history = new ArrayList<>();
    private boolean loadingBalance;
    private final long shareToDialog;
    private boolean secureSet;

    public PcWalletActivity(Bundle args) {
        super(args);
        shareToDialog = args != null ? args.getLong("share_dialog", 0) : 0;
    }

    private static String t(String it, String en) {
        String l = LocaleController.getInstance().getCurrentLocale() != null ? LocaleController.getInstance().getCurrentLocale().getLanguage() : "it";
        return "it".equals(l) ? it : en;
    }

    @Override
    protected String screenTitle() {
        return "Wallet";
    }

    @Override
    public boolean onFragmentCreate() {
        try {
            words = PcWalletCore.loadWords(org.telegram.messenger.ApplicationLoader.applicationContext.getAssets().open("bip39_en.txt"));
        } catch (Throwable e) {
            words = new ArrayList<>();
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(0xFF121212);
        build();
        if (shareToDialog != 0 || getArguments() != null && getArguments().getBoolean("open_send", false)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (PcWalletStore.exists()) {
                    ensureUnlocked(this::sendDialog);
                }
            }, 400);
        }
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        setSecure(true);
        if (PcWalletStore.isUnlocked()) {
            PcWalletStore.touch();
            refresh();
        }
        build();
    }

    @Override
    public void onPause() {
        super.onPause();
        setSecure(false);
    }

    @Override
    public void onFragmentDestroy() {
        PcWalletStore.lock();
        setSecure(false);
        super.onFragmentDestroy();
    }

    private void setSecure(boolean on) {
        if (getParentActivity() == null) {
            return;
        }
        try {
            if (on && !secureSet) {
                getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                secureSet = true;
            } else if (!on && secureSet) {
                getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                secureSet = false;
            }
        } catch (Throwable ignore) {
        }
    }

    // ---------- modello ----------
    private void build() {
        items.clear();
        if (PcWalletStore.looksRooted()) {
            items.add(new Item(T_NOTE, t("Attenzione: questo dispositivo sembra con root. Un portafoglio su un dispositivo compromesso e' a rischio.", "Warning: this device looks rooted. A wallet on a compromised device is at risk."), null, null));
        }
        if (!PcWalletStore.exists()) {
            items.add(new Item(T_HERO, t("Il tuo portafoglio", "Your wallet"), t("Non-custodial: solo tu hai le chiavi. Nessun server, nessun account. Rete Base.", "Non-custodial: only you hold the keys. No server, no account. Base network."), null));
            items.add(new Item(T_ROW, t("Crea un nuovo portafoglio", "Create a new wallet"), t("Genera una frase di recupero di 12 parole", "Generates a 12-word recovery phrase"), this::createFlow));
            items.add(new Item(T_ROW, t("Importa con frase di recupero", "Import with recovery phrase"), t("12-24 parole BIP39", "12-24 BIP39 words"), this::importFlow));
        } else if (!PcWalletStore.isUnlocked()) {
            items.add(new Item(T_HERO, t("Portafoglio bloccato", "Wallet locked"), PcWalletStore.address(), null));
            items.add(new Item(T_ROW, t("Sblocca con PIN", "Unlock with PIN"), null, () -> ensureUnlocked(this::afterUnlock)));
            if (PcWalletStore.biometricEnabled()) {
                items.add(new Item(T_ROW, t("Sblocca con impronta / volto", "Unlock with biometrics"), null, () -> ensureUnlocked(this::afterUnlock)));
            }
        } else {
            String eth = ethWei == null ? (balanceError != null ? "-" : "...") : PcWalletCore.formatUnits(ethWei, 18, 6);
            String porco;
            if (PcWalletRpc.porcoContract().isEmpty()) {
                porco = PcWalletRpc.porcoName() + " (" + PcWalletRpc.porcoSymbol() + ") · " + t("in arrivo", "coming soon");
            } else {
                porco = (porcoUnits == null ? "..." : PcWalletCore.formatUnits(porcoUnits, PcWalletRpc.porcoDecimals(), 4)) + " " + PcWalletRpc.porcoSymbol();
            }
            items.add(new Item(T_HERO, eth + " ETH", porco + (balanceError != null ? "\n" + t("Saldo non disponibile: ", "Balance unavailable: ") + balanceError : ""), null));
            items.add(new Item(T_ACTIONS, null, null, null));
            items.add(new Item(T_ADDR, PcWalletStore.address(), null, null));
            items.add(new Item(T_HEAD, t("Cronologia", "History"), null, null));
            if (history.isEmpty()) {
                items.add(new Item(T_NOTE, loadingBalance ? t("Caricamento...", "Loading...") : t("Nessuna transazione.", "No transactions."), null, null));
            }
            for (PcWalletRpc.Tx x : history) {
                Item i = new Item(T_TX, null, null, null);
                i.tx = x;
                items.add(i);
            }
            items.add(new Item(T_HEAD, t("Sicurezza", "Security"), null, null));
            items.add(new Item(T_ROW, t("Mostra frase di recupero", "Show recovery phrase"), t("Richiede di nuovo il PIN. Nessuno ti chiedera' mai la frase.", "Asks for the PIN again. Nobody will ever ask you for it."), () -> reauth(this::showBackup)));
            if (Build.VERSION.SDK_INT >= 28) {
                items.add(new Item(T_ROW, PcWalletStore.biometricEnabled() ? t("Disattiva sblocco biometrico", "Turn off biometric unlock") : t("Attiva sblocco biometrico", "Turn on biometric unlock"), null, () -> reauth(() -> {
                    PcWalletStore.setBiometric(!PcWalletStore.biometricEnabled());
                    build();
                    notifyData();
                })));
            }
            items.add(new Item(T_ROW, t("Cambia PIN", "Change PIN"), null, () -> reauth(() -> pinSetup(pin -> {
                PcWalletStore.changePin(pin);
                PcUi.toast(getParentActivity(), t("PIN aggiornato", "PIN updated"));
            }))));
            items.add(new Item(T_ROW, t("Blocca ora", "Lock now"), null, () -> {
                PcWalletStore.lock();
                build();
                notifyData();
            }));
            items.add(new Item(T_ROW, t("Elimina portafoglio da questo dispositivo", "Remove wallet from this device"), t("Senza la frase di recupero i fondi non sono recuperabili.", "Without the recovery phrase funds cannot be recovered."), this::wipeFlow));
            if (PcFlags.STRIPE_PAYMENTS) {
                items.add(new Item(T_HEAD, t("Acquista con carta", "Buy with card"), null, null));
            }
        }
        items.add(new Item(T_NOTE, t("PIN e frase restano sul dispositivo. Il saldo e le transazioni passano da RPC pubblici di Base. Controlla sempre l'indirizzo prima di inviare.", "PIN and phrase stay on this device. Balance and transactions use public Base RPCs. Always double-check the address before sending."), null, null));
        notifyData();
    }

    @Override
    protected int itemCount() {
        return items.size();
    }

    @Override
    protected int itemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        Item i = items.get(position);
        return i.type == T_ROW || i.type == T_TX;
    }

    @Override
    protected void onRowClick(View view, int position) {
        Item i = items.get(position);
        if (i.type == T_ROW && i.click != null) {
            i.click.run();
        } else if (i.type == T_TX && i.tx != null) {
            try {
                getParentActivity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PcWalletRpc.explorerTx(i.tx.hash))));
            } catch (Throwable ignore) {
            }
        }
    }

    private TextView text(Context c, String s, float size, boolean bold, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(AndroidUtilities.bold());
        return v;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return box;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        final Context c = row.getContext();
        LinearLayout box = (LinearLayout) row;
        box.removeAllViews();
        box.setPadding(0, 0, 0, 0);
        Item it = items.get(position);
        if (viewType == T_HERO) {
            box.setPadding(dp(16), dp(8), dp(16), dp(8));
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(20), dp(22), dp(20), dp(22));
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFF242833, 0xFF1A1A1A});
            g.setCornerRadius(dp(18));
            g.setStroke(Math.max(1, dp(1)), 0xFF2F3442);
            card.setBackground(g);
            card.addView(text(c, t("Saldo", "Balance"), 13, false, 0xFFBFBFBF));
            card.addView(text(c, it.a, 30, true, 0xFFF2F2F2), lp(0, 6, 0, 0));
            if (it.b != null) {
                card.addView(text(c, it.b, 14, false, 0xFFBFBFBF), lp(0, 6, 0, 0));
            }
            box.addView(card);
        } else if (viewType == T_ACTIONS) {
            box.setPadding(dp(12), dp(4), dp(12), dp(4));
            LinearLayout r = new LinearLayout(c);
            r.addView(actionButton(c, R.drawable.ic_pc_send_money, t("Invia", "Send"), () -> reauthSoft(this::sendDialog)), new LinearLayout.LayoutParams(0, dp(72), 1f));
            r.addView(actionButton(c, R.drawable.ic_pc_receive, t("Ricevi", "Receive"), this::receiveDialog), new LinearLayout.LayoutParams(0, dp(72), 1f));
            r.addView(actionButton(c, R.drawable.ic_pc_refresh, t("Aggiorna", "Refresh"), this::refresh), new LinearLayout.LayoutParams(0, dp(72), 1f));
            box.addView(r);
        } else if (viewType == T_ADDR) {
            box.setPadding(dp(16), dp(10), dp(16), dp(6));
            TextView l = text(c, t("Il tuo indirizzo (Base)", "Your address (Base)"), 12, false, 0xFFBFBFBF);
            TextView a = text(c, it.a, 13, false, 0xFFF2F2F2);
            a.setTextIsSelectable(false);
            a.setOnClickListener(v -> copy(it.a));
            box.addView(l);
            box.addView(a, lp(0, 2, 0, 0));
        } else if (viewType == T_HEAD) {
            box.setPadding(dp(16), dp(18), dp(16), dp(6));
            box.addView(text(c, it.a, 15, true, 0xFFF2F2F2));
        } else if (viewType == T_TX) {
            box.setPadding(dp(16), dp(10), dp(16), dp(10));
            PcWalletRpc.Tx x = it.tx;
            boolean out = x.from.equalsIgnoreCase(PcWalletStore.address());
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            String other = out ? x.to : x.from;
            col.addView(text(c, (out ? t("Inviato a ", "Sent to ") : t("Ricevuto da ", "Received from ")) + shortAddr(other), 14, true, 0xFFF2F2F2));
            col.addView(text(c, new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(x.time * 1000L)) + (x.ok ? "" : " · " + t("fallita", "failed")), 12, false, 0xFFBFBFBF));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(text(c, (out ? "-" : "+") + PcWalletCore.formatUnits(x.value, 18, 6) + " " + x.symbol, 14, true, out ? 0xFFBFBFBF : 0xFF9DAAC8));
            box.addView(r);
        } else if (viewType == T_ROW) {
            box.setPadding(dp(16), dp(14), dp(16), dp(14));
            box.addView(text(c, it.a, 16, true, 0xFFF2F2F2));
            if (it.b != null) {
                box.addView(text(c, it.b, 13, false, 0xFFBFBFBF), lp(0, 2, 0, 0));
            }
        } else {
            box.setPadding(dp(20), dp(14), dp(20), dp(24));
            box.addView(text(c, it.a, 12, false, 0xFF8A8F9C));
        }
    }

    private static LinearLayout.LayoutParams lp(int l, int tp, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(l), dp(tp), dp(r), dp(b));
        return p;
    }

    private View actionButton(Context c, int icon, String label, Runnable click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF1A1A1A);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), 0xFF2F3442);
        b.setBackground(g);
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setColorFilter(0xFFF2F2F2);
        b.addView(i, new LinearLayout.LayoutParams(dp(26), dp(26)));
        b.addView(text(c, label, 13, true, 0xFFF2F2F2), lp(0, 4, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(72), 1f);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> click.run());
        FrameLayout w = new FrameLayout(c);
        w.setPadding(dp(4), 0, dp(4), 0);
        w.addView(b, new FrameLayout.LayoutParams(-1, -1));
        return w;
    }

    private String shortAddr(String a) {
        return a == null || a.length() < 12 ? String.valueOf(a) : a.substring(0, 6) + "..." + a.substring(a.length() - 4);
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("address", s));
            PcUi.toast(getParentActivity(), t("Copiato", "Copied"));
        } catch (Throwable ignore) {
        }
    }

    // ---------- dati ----------
    private void afterUnlock() {
        refresh();
        build();
    }

    private void refresh() {
        if (!PcWalletStore.isUnlocked()) {
            return;
        }
        final String addr = PcWalletStore.address();
        loadingBalance = true;
        balanceError = null;
        PcWalletRpc.ethBalance(addr, (v, e) -> {
            loadingBalance = false;
            if (e != null) {
                balanceError = e;
            } else {
                ethWei = v;
            }
            build();
        });
        final String token = PcWalletRpc.porcoContract();
        if (!token.isEmpty()) {
            PcWalletRpc.tokenBalance(token, addr, (v, e) -> {
                if (e == null) {
                    porcoUnits = v;
                    build();
                }
            });
        }
        PcWalletRpc.history(addr, (list, e) -> {
            if (list != null) {
                history = list;
                build();
            }
        });
    }

    // ---------- sblocco ----------
    private void ensureUnlocked(Runnable then) {
        if (PcWalletStore.isUnlocked()) {
            PcWalletStore.touch();
            then.run();
            return;
        }
        if (PcWalletStore.biometricEnabled() && Build.VERSION.SDK_INT >= 28) {
            biometric(ok -> {
                if (ok && PcWalletStore.unlockAfterBiometric()) {
                    then.run();
                } else {
                    pinPrompt(t("Sblocca il portafoglio", "Unlock wallet"), pin -> {
                        if (PcWalletStore.unlockWithPin(pin)) {
                            then.run();
                            return true;
                        }
                        return false;
                    });
                }
            });
        } else {
            pinPrompt(t("Sblocca il portafoglio", "Unlock wallet"), pin -> {
                if (PcWalletStore.unlockWithPin(pin)) {
                    then.run();
                    return true;
                }
                return false;
            });
        }
    }

    /** Operazioni sensibili: richiede sempre il PIN, anche a portafoglio sbloccato. */
    private void reauth(Runnable then) {
        pinPrompt(t("Conferma con il PIN", "Confirm with PIN"), pin -> {
            if (PcWalletStore.verifyPin(pin)) {
                PcWalletStore.touch();
                then.run();
                return true;
            }
            return false;
        });
    }

    private void reauthSoft(Runnable then) {
        ensureUnlocked(then);
    }

    private void biometric(final org.telegram.messenger.Utilities.Callback<Boolean> cb) {
        try {
            final Context ctx = getParentActivity();
            android.hardware.biometrics.BiometricPrompt.Builder b = new android.hardware.biometrics.BiometricPrompt.Builder(ctx)
                .setTitle(t("Sblocca il portafoglio", "Unlock wallet"))
                .setNegativeButton(t("Usa il PIN", "Use PIN"), ctx.getMainExecutor(), (d, w) -> cb.run(false));
            android.hardware.biometrics.BiometricPrompt bp = b.build();
            bp.authenticate(new android.os.CancellationSignal(), ctx.getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                    cb.run(true);
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    if (errorCode != 13 && errorCode != 10) {
                        cb.run(false);
                    }
                }
            });
        } catch (Throwable t) {
            cb.run(false);
        }
    }

    private interface PinCheck {
        boolean ok(String pin);
    }

    private EditText pinField(Context c) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        e.setTextColor(0xFFF2F2F2);
        e.setHintTextColor(0xFF8A8F9C);
        e.setGravity(Gravity.CENTER);
        e.setTextSize(22);
        return e;
    }

    private void pinPrompt(String title, final PinCheck check) {
        if (getParentActivity() == null) {
            return;
        }
        long wait = PcWalletStore.lockoutLeft();
        if (wait > 0) {
            PcUi.toast(getParentActivity(), t("Troppi tentativi. Riprova tra " + (wait / 1000 + 1) + " s", "Too many attempts. Try again in " + (wait / 1000 + 1) + " s"));
            return;
        }
        final EditText e = pinField(getParentActivity());
        e.setHint("PIN");
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(e);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(title);
        b.setView(holder);
        b.setPositiveButton("OK", (d, w) -> {
            if (!check.ok(e.getText().toString())) {
                PcUi.toast(getParentActivity(), t("PIN errato", "Wrong PIN"));
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void pinSetup(final org.telegram.messenger.Utilities.Callback<String> done) {
        if (getParentActivity() == null) {
            return;
        }
        final EditText p1 = pinField(getParentActivity()), p2 = pinField(getParentActivity());
        p1.setHint(t("Nuovo PIN (almeno 6 cifre)", "New PIN (6+ digits)"));
        p2.setHint(t("Ripeti il PIN", "Repeat PIN"));
        LinearLayout holder = new LinearLayout(getParentActivity());
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(p1);
        holder.addView(p2);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Imposta il PIN del portafoglio", "Set wallet PIN"));
        b.setView(holder);
        b.setPositiveButton("OK", (d, w) -> {
            String a = p1.getText().toString();
            if (a.length() < 6) {
                PcUi.toast(getParentActivity(), t("Il PIN deve avere almeno 6 cifre", "PIN must have at least 6 digits"));
            } else if (!a.equals(p2.getText().toString())) {
                PcUi.toast(getParentActivity(), t("I PIN non coincidono", "PINs do not match"));
            } else {
                done.run(a);
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    // ---------- crea / importa / backup ----------
    private void createFlow() {
        if (words == null || words.size() != 2048) {
            PcUi.toast(getParentActivity(), t("Lista parole non disponibile", "Word list unavailable"));
            return;
        }
        pinSetup(pin -> {
            final String mnemonic = PcWalletCore.generateMnemonic(words);
            showWords(mnemonic, true, () -> quiz(mnemonic, () -> {
                try {
                    PcWalletStore.create(mnemonic, pin);
                    PcUi.toast(getParentActivity(), t("Portafoglio creato", "Wallet created"));
                    afterUnlock();
                } catch (Throwable e) {
                    PcUi.toast(getParentActivity(), t("Creazione non riuscita: ", "Creation failed: ") + e.getMessage());
                }
            }));
        });
    }

    private void showWords(String mnemonic, boolean creating, Runnable next) {
        if (getParentActivity() == null) {
            return;
        }
        String[] w = mnemonic.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            sb.append(String.format(java.util.Locale.US, "%2d. %s", i + 1, w[i])).append(i % 2 == 1 ? "\n" : "      ");
        }
        TextView tv = text(getParentActivity(), sb.toString(), 16, true, 0xFFF2F2F2);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        TextView warn = text(getParentActivity(), t("Scrivi queste parole su carta, nell'ordine. Chiunque le abbia controlla i tuoi fondi. Non farne screenshot, non salvarle online, non darle a nessuno: nemmeno a noi. Se le perdi, i fondi sono persi.", "Write these words down in order. Anyone who has them controls your funds. No screenshots, no cloud, never share them - not even with us. If you lose them, the funds are lost."), 13, false, 0xFFBFBFBF);
        LinearLayout holder = new LinearLayout(getParentActivity());
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(tv);
        holder.addView(warn, lp(0, 12, 0, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Frase di recupero", "Recovery phrase"));
        b.setView(holder);
        b.setPositiveButton(creating ? t("Le ho scritte", "I wrote them down") : "OK", (d, x) -> {
            if (next != null) next.run();
        });
        if (creating) {
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        }
        showDialog(b.create());
    }

    private void quiz(final String mnemonic, final Runnable ok) {
        final String[] w = mnemonic.split(" ");
        final int idx = new java.security.SecureRandom().nextInt(w.length);
        final EditText e = new EditText(getParentActivity());
        e.setTextColor(0xFFF2F2F2);
        e.setHint(t("Parola numero ", "Word number ") + (idx + 1));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(e);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Verifica il backup", "Verify your backup"));
        b.setView(holder);
        b.setPositiveButton("OK", (d, x) -> {
            if (e.getText().toString().trim().equalsIgnoreCase(w[idx])) {
                ok.run();
            } else {
                PcUi.toast(getParentActivity(), t("Parola sbagliata: controlla il foglio e riprova", "Wrong word: check your paper and retry"));
                showWords(mnemonic, true, () -> quiz(mnemonic, ok));
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void importFlow() {
        if (getParentActivity() == null) {
            return;
        }
        final EditText e = new EditText(getParentActivity());
        e.setTextColor(0xFFF2F2F2);
        e.setHint(t("12-24 parole separate da spazio", "12-24 words separated by spaces"));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(e);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Importa portafoglio", "Import wallet"));
        b.setView(holder);
        b.setPositiveButton("OK", (d, x) -> {
            final String m = e.getText().toString().trim().toLowerCase().replaceAll("\\s+", " ");
            if (!PcWalletCore.isValidMnemonic(m, words)) {
                PcUi.toast(getParentActivity(), t("Frase non valida (parole o checksum errati)", "Invalid phrase (wrong words or checksum)"));
                return;
            }
            pinSetup(pin -> {
                try {
                    PcWalletStore.create(m, pin);
                    afterUnlock();
                } catch (Throwable ex) {
                    PcUi.toast(getParentActivity(), t("Importazione non riuscita: ", "Import failed: ") + ex.getMessage());
                }
            });
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void showBackup() {
        String m = PcWalletStore.mnemonic();
        if (m != null) {
            showWords(m, false, null);
        }
    }

    private void wipeFlow() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Eliminare il portafoglio?", "Remove the wallet?"));
        b.setMessage(t("Verra' cancellato da questo dispositivo. Senza la frase di recupero i fondi NON sono recuperabili.", "It will be erased from this device. Without the recovery phrase the funds CANNOT be recovered."));
        b.setPositiveButton(t("Elimina", "Remove"), (d, w) -> reauth(() -> {
            PcWalletStore.wipe();
            ethWei = null;
            porcoUnits = null;
            history = new ArrayList<>();
            build();
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    // ---------- ricevi ----------
    private void receiveDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final String addr = PcWalletStore.address();
        LinearLayout holder = new LinearLayout(getParentActivity());
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        ImageView qr = new ImageView(getParentActivity());
        qr.setImageBitmap(qr(addr, dp(280)));
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(8), dp(8), dp(8), dp(8));
        holder.addView(qr, new LinearLayout.LayoutParams(dp(296), dp(296)));
        TextView a = text(getParentActivity(), addr, 13, false, 0xFFF2F2F2);
        a.setGravity(Gravity.CENTER);
        holder.addView(a, lp(0, 10, 0, 0));
        holder.addView(text(getParentActivity(), t("Invia qui solo ETH o token sulla rete Base.", "Only send ETH or tokens on the Base network here."), 12, false, 0xFFBFBFBF), lp(0, 8, 0, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Ricevi", "Receive"));
        b.setView(holder);
        b.setPositiveButton(t("Copia indirizzo", "Copy address"), (d, w) -> copy(addr));
        b.setNeutralButton(t("Condividi", "Share"), (d, w) -> {
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(android.content.Intent.EXTRA_TEXT, addr);
                getParentActivity().startActivity(android.content.Intent.createChooser(i, t("Condividi indirizzo", "Share address")));
            } catch (Throwable ignore) {
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Close), null);
        showDialog(b.create());
    }

    private static Bitmap qr(String s, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, m.get(x, y) ? 0xFF121212 : Color.WHITE);
                }
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- invia ----------
    private void sendDialog() {
        sendDialog("", "", false);
    }

    private void scanQr(final String keepTo, final String keepAmt, final boolean keepPorco) {
        final boolean[] found = {false};
        org.telegram.ui.CameraScanActivity.showAsSheet(this, true, org.telegram.ui.CameraScanActivity.TYPE_QR, new org.telegram.ui.CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                found[0] = true;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    PcWalletCore.Payment pay = PcWalletCore.parsePayment(text);
                    if (pay == null) {
                        PcUi.toast(getParentActivity(), t("Questo QR non contiene un indirizzo valido.", "This QR does not contain a valid address."));
                        sendDialog(keepTo, keepAmt, keepPorco);
                        return;
                    }
                    if (pay.chainId != null && pay.chainId != 8453) {
                        PcUi.toast(getParentActivity(), t("Il QR e' per un'altra rete (chain " + pay.chainId + "). Qui si usa solo Base.", "This QR is for another network (chain " + pay.chainId + "). Only Base is supported."));
                        sendDialog(keepTo, keepAmt, keepPorco);
                        return;
                    }
                    boolean token = false;
                    String amount = keepAmt;
                    if (pay.contract != null) {
                        if (PcWalletRpc.porcoContract().isEmpty() || !PcWalletRpc.porcoContract().equalsIgnoreCase(pay.contract)) {
                            PcUi.toast(getParentActivity(), t("Token non supportato dal portafoglio.", "Token not supported by the wallet."));
                            sendDialog(keepTo, keepAmt, keepPorco);
                            return;
                        }
                        token = true;
                        if (pay.value != null) {
                            amount = PcWalletCore.formatUnits(pay.value, PcWalletRpc.porcoDecimals(), PcWalletRpc.porcoDecimals());
                        }
                    } else if (pay.value != null) {
                        amount = PcWalletCore.formatUnits(pay.value, 18, 18);
                    }
                    if (amount.contains(".")) {
                        amount = amount.replaceAll("0+$", "").replaceAll("\\.$", "");
                    }
                    PcUi.toast(getParentActivity(), t("Indirizzo letto dal QR", "Address read from QR"));
                    sendDialog(pay.address, amount, token);
                }, 150);
            }

            @Override
            public void onDismiss() {
                if (!found[0]) {
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> sendDialog(keepTo, keepAmt, keepPorco), 150);
                }
            }
        });
    }

    private void sendDialog(String prefTo, String prefAmt, boolean prefPorco) {
        if (getParentActivity() == null) {
            return;
        }
        final boolean porcoOk = !PcWalletRpc.porcoContract().isEmpty();
        final EditText to = new EditText(getParentActivity());
        to.setHint(t("Indirizzo 0x... (Base)", "Address 0x... (Base)"));
        to.setTextColor(0xFFF2F2F2);
        to.setText(prefTo);
        to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText amt = new EditText(getParentActivity());
        amt.setHint(t("Importo", "Amount"));
        amt.setTextColor(0xFFF2F2F2);
        amt.setText(prefAmt);
        amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final boolean[] porco = {prefPorco && !PcWalletRpc.porcoContract().isEmpty()};
        final TextView asset = text(getParentActivity(), "ETH", 15, true, 0xFF9DAAC8);
        asset.setPadding(0, dp(10), 0, dp(10));
        if (porcoOk) {
            asset.setText((porco[0] ? PcWalletRpc.porcoSymbol() : "ETH") + "  ▾");
            asset.setOnClickListener(v -> {
                porco[0] = !porco[0];
                asset.setText((porco[0] ? PcWalletRpc.porcoSymbol() : "ETH") + "  ▾");
            });
        } else {
            asset.setText("ETH   (" + PcWalletRpc.porcoSymbol() + " " + t("in arrivo", "coming soon") + ")");
        }
        LinearLayout holder = new LinearLayout(getParentActivity());
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(asset);
        holder.addView(to);
        holder.addView(amt);
        final TextView scan = text(getParentActivity(), "\u25A3  " + t("Scansiona QR con la fotocamera", "Scan QR with the camera"), 15, true, 0xFF9DAAC8);
        scan.setPadding(0, dp(14), 0, dp(6));
        holder.addView(scan);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Invia denaro", "Send money"));
        b.setView(holder);
        b.setPositiveButton(t("Stima costi", "Estimate"), (d, w) -> prepareSend(to.getText().toString().trim(), amt.getText().toString().trim(), porco[0]));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog dlg = b.create();
        scan.setOnClickListener(v -> {
            final String kt = to.getText().toString(), ka = amt.getText().toString();
            final boolean kp = porco[0];
            dlg.dismiss();
            scanQr(kt, ka, kp);
        });
        showDialog(dlg);
    }

    private void prepareSend(String toRaw, final String amount, final boolean token) {
        PcWalletCore.Payment parsed = PcWalletCore.parsePayment(toRaw);
        final String toAddr = parsed != null && parsed.address != null ? parsed.address : toRaw;
        if (!PcWalletCore.isValidAddress(toAddr)) {
            PcUi.toast(getParentActivity(), t("Indirizzo non valido (controlla il checksum)", "Invalid address (check the checksum)"));
            return;
        }
        final BigInteger units;
        try {
            units = PcWalletCore.parseUnits(amount, token ? PcWalletRpc.porcoDecimals() : 18);
            if (units.signum() <= 0) throw new Exception();
        } catch (Throwable e) {
            PcUi.toast(getParentActivity(), t("Importo non valido", "Invalid amount"));
            return;
        }
        PcUi.toast(getParentActivity(), t("Stimo il gas...", "Estimating gas..."));
        final String from = PcWalletStore.address();
        final byte[] data = token ? PcWalletCore.erc20TransferData(toAddr, units) : new byte[0];
        PcWalletRpc.quote(from, toAddr, token ? BigInteger.ZERO : units, data, token ? PcWalletRpc.porcoContract() : null, (q, err) -> {
            if (err != null) {
                PcUi.toast(getParentActivity(), t("Impossibile stimare: ", "Cannot estimate: ") + err);
                return;
            }
            confirmSend(toAddr, amount, token, units, q);
        });
    }

    private void confirmSend(final String toAddr, final String amount, final boolean token, final BigInteger units, final PcWalletRpc.Quote q) {
        if (getParentActivity() == null) {
            return;
        }
        String sym = token ? PcWalletRpc.porcoSymbol() : "ETH";
        String fee = PcWalletCore.formatUnits(q.feeWei(), 18, 8);
        String msg = t("Rete: Base\nA: ", "Network: Base\nTo: ") + toAddr + "\n" + t("Importo: ", "Amount: ") + amount + " " + sym + "\n" + t("Costo di rete massimo (gas): ", "Max network fee (gas): ") + fee + " ETH";
        if (PcFlags.feeActive()) {
            msg += "\n" + t("Commissione piattaforma 5%", "Platform fee 5%");
        }
        msg += "\n\n" + t("L'operazione non e' annullabile.", "This cannot be undone.");
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Conferma l'invio", "Confirm sending"));
        b.setMessage(msg);
        b.setPositiveButton(t("Conferma con PIN", "Confirm with PIN"), (d, w) -> reauth(() -> {
            final byte[] priv = PcWalletStore.privateKey();
            if (priv == null) {
                PcUi.toast(getParentActivity(), t("Portafoglio bloccato", "Wallet locked"));
                return;
            }
            PcWalletRpc.send(q, priv, (hash, err) -> {
                Arrays.fill(priv, (byte) 0);
                if (err != null) {
                    PcUi.toast(getParentActivity(), t("Invio non riuscito: ", "Send failed: ") + err);
                    return;
                }
                sentDialog(hash, amount + " " + sym);
                refresh();
            });
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void sentDialog(final String hash, final String what) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(t("Transazione inviata", "Transaction sent"));
        b.setMessage(hash + "\n\n" + PcWalletRpc.explorerTx(hash));
        if (shareToDialog != 0) {
            b.setPositiveButton(t("Condividi nella chat", "Share in the chat"), (d, w) -> org.telegram.messenger.SendMessagesHelper.prepareSendingText(
                org.telegram.messenger.AccountInstance.getInstance(currentAccount), t("Ti ho inviato ", "I sent you ") + what + "\n" + PcWalletRpc.explorerTx(hash), shareToDialog, true, 0, 0, 0));
            b.setNegativeButton(LocaleController.getString(R.string.Close), null);
        } else {
            b.setPositiveButton(t("Copia link", "Copy link"), (d, w) -> copy(PcWalletRpc.explorerTx(hash)));
            b.setNegativeButton(LocaleController.getString(R.string.Close), null);
        }
        showDialog(b.create());
    }
}
