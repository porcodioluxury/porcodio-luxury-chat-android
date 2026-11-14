import org.telegram.porcodio.PcPayments;
import org.telegram.porcodio.PcFlags;

public class PcPaymentsTest {
    static int fails = 0;

    static void eq(long a, long b, String w) {
        if (a != b) {
            fails++;
            System.out.println("FALLITO " + w + ": " + a + " != " + b);
        }
    }

    public static void main(String[] x) {
        eq(PcPayments.feeCents(10000, 500), 500, "5% di 100,00");
        eq(PcPayments.feeCents(499, 500), 25, "5% di 4,99 arrotondato");
        eq(PcPayments.feeCents(1, 500), 0, "5% di 0,01");
        eq(PcPayments.feeCents(10, 500), 1, "mezzo in su");
        eq(PcPayments.feeCents(0, 500), 0, "zero");
        eq(PcPayments.netToSellerCents(10000, 500), 9500, "netto");
        eq(PcPayments.priceCents(PcPayments.Kind.BLUE_MONTH, 0), 499, "mese");
        eq(PcPayments.priceCents(PcPayments.Kind.BLUE_YEAR, 0), 4999, "anno");
        eq(PcPayments.yearlySavingCents(), 989, "risparmio");
        eq(PcFlags.STRIPE_PAYMENTS ? 1 : 0, 0, "pagamenti spenti");
        eq(PcPayments.feeCents(10000), 0, "commissione spenta");
        String m = "https://buy.stripe.com/00w3co4VG0Log4peFmaMU00", y = "https://buy.stripe.com/9B6dR24VGbq2dWhcxeaMU01";
        if (!PcPayments.paymentLinkUrl(PcPayments.Kind.BLUE_MONTH, m, y, 123456L).equals(m + "?client_reference_id=123456")) { fails++; System.out.println("FALLITO link mese"); }
        if (!PcPayments.paymentLinkUrl(PcPayments.Kind.BLUE_YEAR, m, y, 42L).equals(y + "?client_reference_id=42")) { fails++; System.out.println("FALLITO link anno"); }
        if (!PcPayments.paymentLinkUrl(PcPayments.Kind.BLUE_YEAR, "", "", 42L).isEmpty()) { fails++; System.out.println("FALLITO link vuoto"); }
        if (!PcPayments.paymentLinkUrl(PcPayments.Kind.BLUE_YEAR, m, "http://evil.example/x", 42L).isEmpty()) { fails++; System.out.println("FALLITO host"); }
        if (!PcPayments.paymentLinkUrl(PcPayments.Kind.BLUE_YEAR, m, y + "?a=b", 7L).equals(y + "?a=b&client_reference_id=7")) { fails++; System.out.println("FALLITO query"); }
        if (!PcFlags.BLUE_CHECK_PAYMENT_LINKS) { fails++; System.out.println("FALLITO link attivi"); }
        final boolean[] called = {false};
        PcPayments.startPaymentSheet(PcPayments.Kind.BLUE_MONTH, 0, (p, e) -> {
            called[0] = true;
            eq(p ? 1 : 0, 0, "non pagato");
            if (!"disabled".equals(e)) { fails++; System.out.println("FALLITO errore"); }
        });
        eq(called[0] ? 1 : 0, 1, "callback");
        if (fails == 0) System.out.println("TUTTI I TEST DEI PAGAMENTI OK"); else System.exit(1);
    }
}
