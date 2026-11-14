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
