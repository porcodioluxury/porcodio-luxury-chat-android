package org.telegram.porcodio;

/**
 * Struttura dei pagamenti (Java puro). TUTTO SPENTO finche' PcFlags.STRIPE_PAYMENTS e' false: nessuna chiamata a Stripe.
 * Prezzi della spunta blu (decisi da Mario): 4,99 EUR al mese oppure 49,99 EUR all'anno. Commissione piattaforma: 5%.
 * Flusso previsto per la spunta: 1) l'utente paga prima (PaymentSheet) 2) solo dopo parte Stripe Identity, cosi' il costo della
 * verifica e' sempre coperto. Il server (non incluso) crea PaymentIntent/Subscription e restituisce il client secret.
 */
public final class PcPayments {
    private PcPayments() {
    }

    public static final String CURRENCY = "eur";
    public static final int BLUE_MONTH_CENTS = 499;
    public static final int BLUE_YEAR_CENTS = 4999;

    public enum Kind {BLUE_MONTH, BLUE_YEAR, PURCHASE}

    /** Commissione in centesimi con arrotondamento commerciale (mezzo in su). */
    public static long feeCents(long amountCents, int bps) {
        if (amountCents <= 0 || bps <= 0) {
            return 0;
        }
        return (amountCents * bps + 5000) / 10000;
    }

    public static long feeCents(long amountCents) {
        return PcFlags.feeActive() ? feeCents(amountCents, PcFlags.FEE_BPS) : 0;
    }

    public static long netToSellerCents(long amountCents, int bps) {
        return amountCents - feeCents(amountCents, bps);
    }

    public static int priceCents(Kind k, long purchaseCents) {
        switch (k) {
            case BLUE_MONTH:
                return BLUE_MONTH_CENTS;
            case BLUE_YEAR:
                return BLUE_YEAR_CENTS;
            default:
                return (int) purchaseCents;
        }
    }

    public static String formatEur(long cents) {
        return String.format(java.util.Locale.ITALY, "%d,%02d €", cents / 100, cents % 100);
    }

    /** Risparmio del piano annuale rispetto a 12 mesi, in centesimi. */
    public static int yearlySavingCents() {
        return BLUE_MONTH_CENTS * 12 - BLUE_YEAR_CENTS;
    }

    /**
     * URL del Payment Link Stripe della spunta blu con client_reference_id = uid (numerico), cosi' il pagamento e' riconducibile
     * all'account. Vuoto se il link non e' configurato o l'uid non e' valido.
     */
    public static String paymentLinkUrl(Kind kind, String monthLink, String yearLink, long uid) {
        String base = kind == Kind.BLUE_YEAR ? yearLink : monthLink;
        if (base == null || uid <= 0) {
            return "";
        }
        base = base.trim();
        if (!base.startsWith("https://buy.stripe.com/")) {
            return "";
        }
        return base + (base.indexOf('?') >= 0 ? "&" : "?") + "client_reference_id=" + uid;
    }

    public interface Callback {
        void done(boolean paid, String error);
    }

    /**
     * Punto di integrazione del PaymentSheet (carte, Google Pay, rate). Spento: risponde subito con errore, senza rete.
     * Quando i pagamenti saranno accesi qui si chiama il server per il client secret e si presenta lo Stripe PaymentSheet
     * (il Gradle dependency com.stripe:stripe-android si aggiunge solo allora).
     */
    public static void startPaymentSheet(Kind kind, long purchaseCents, Callback cb) {
        if (!PcFlags.STRIPE_PAYMENTS) {
            cb.done(false, "disabled");
            return;
        }
        cb.done(false, "not_configured");
    }
}
