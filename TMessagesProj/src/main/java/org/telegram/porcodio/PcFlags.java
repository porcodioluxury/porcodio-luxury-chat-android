package org.telegram.porcodio;

/**
 * Interruttori delle funzioni a pagamento. Dal v12 (approvazione esplicita di Mario, 15 nov 2026 02:57 UTC+7) e' ACCESA solo la
 * spunta blu tramite Stripe Payment Link (pagina di pagamento ospitata da Stripe, nessuna chiave nell'app). Restano SPENTI
 * PaymentSheet in app, Stripe Connect e la commissione del 5%.
 * Il portafoglio non-custodial (cripto) funziona senza questi flag perche' non passa da nessun nostro server.
 */
public final class PcFlags {
    private PcFlags() {
    }

    /** Stripe (PaymentSheet con carte, Apple Pay, Google Pay e rate) e abbonamento spunta blu. */
    public static final boolean STRIPE_PAYMENTS = false;

    /** Spunta blu: il pulsante apre il Payment Link Stripe (mensile o annuale) con client_reference_id = uid. */
    public static final boolean BLUE_CHECK_PAYMENT_LINKS = true;

    /** Commissione della piattaforma in punti base (500 = 5%), applicata solo se i pagamenti sono accesi. */
    public static final int FEE_BPS = 500;

    public static boolean feeActive() {
        return STRIPE_PAYMENTS;
    }
}
