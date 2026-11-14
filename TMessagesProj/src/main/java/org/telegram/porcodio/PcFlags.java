package org.telegram.porcodio;

/**
 * Interruttori delle funzioni a pagamento. TUTTO SPENTO: nessuna chiamata a Stripe, nessuna commissione.
 * Il portafoglio non-custodial (cripto) funziona senza questi flag perche' non passa da nessun nostro server.
 */
public final class PcFlags {
    private PcFlags() {
    }

    /** Stripe (PaymentSheet con carte, Apple Pay, Google Pay e rate) e abbonamento spunta blu. */
    public static final boolean STRIPE_PAYMENTS = false;

    /** Commissione della piattaforma in punti base (500 = 5%), applicata solo se i pagamenti sono accesi. */
    public static final int FEE_BPS = 500;

    public static boolean feeActive() {
        return STRIPE_PAYMENTS;
    }
}
