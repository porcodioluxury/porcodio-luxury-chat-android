import org.telegram.porcodio.PcSecretMediaCore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

public class PcSecretMediaCoreTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            fails++;
            System.out.println("FALLITO: " + what);
        }
    }

    static byte[] dec(byte[] key, byte[] enc, String[] nameOut) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PcSecretMediaCore.Meta m = PcSecretMediaCore.decrypt(key, new ByteArrayInputStream(enc), o);
        if (nameOut != null) nameOut[0] = m.name + "|" + m.mime;
        return o.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        byte[] key = new byte[32];
        new Random(1).nextBytes(key);
        int[] sizes = {0, 1, 100, PcSecretMediaCore.CHUNK - 1, PcSecretMediaCore.CHUNK, PcSecretMediaCore.CHUNK + 1, 3 * PcSecretMediaCore.CHUNK, 3 * PcSecretMediaCore.CHUNK + 17};
        for (int s : sizes) {
            byte[] data = new byte[s];
            new Random(s).nextBytes(data);
            byte[] enc = PcSecretMediaCore.encryptBytes(key, data, "foto privata.jpg", "image/jpeg");
            String[] nm = new String[1];
            check(Arrays.equals(data, dec(key, enc, nm)), "round trip " + s);
            check("foto privata.jpg|image/jpeg".equals(nm[0]), "meta " + s);
            check(new String(enc, 0, 4).equals("PCSM"), "magic");
            if (s > 100) {
                String plainStr = new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1);
                check(!plainStr.contains("foto privata"), "nome non in chiaro");
            }
        }
        byte[] data = new byte[2 * PcSecretMediaCore.CHUNK + 5];
        new Random(9).nextBytes(data);
        byte[] enc = PcSecretMediaCore.encryptBytes(key, data, "x", "y");
        // bit alterato
        byte[] bad = enc.clone();
        bad[bad.length / 2] ^= 1;
        try { dec(key, bad, null); check(false, "alterazione non rilevata"); } catch (Exception e) { }
        // chiave sbagliata
        byte[] k2 = key.clone();
        k2[0] ^= 1;
        try { dec(k2, enc, null); check(false, "chiave errata accettata"); } catch (Exception e) { }
        // troncamento all'ultimo blocco
        int cut = enc.length - 4 - (PcSecretMediaCore.CHUNK > 0 ? 21 + 16 : 0);
        byte[] shortEnc = Arrays.copyOf(enc, 13 + 4 + 4 + 4 + 1 + 16 + 4 + 4);
        try { dec(key, shortEnc, null); check(false, "troncamento non rilevato"); } catch (Exception e) { }
        // rimozione del blocco finale
        int off = 13;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(enc);
        int l1 = bb.getInt(off); off += 4 + l1;
        int l2 = bb.getInt(off); off += 4 + l2;
        int l3 = bb.getInt(off); off += 4 + l3;
        try { dec(key, Arrays.copyOf(enc, off), null); check(false, "blocco finale rimosso non rilevato"); } catch (Exception e) { }
        if (fails == 0) System.out.println("TUTTI I TEST DEI MEDIA SEGRETI OK");
        else System.exit(1);
    }
}
