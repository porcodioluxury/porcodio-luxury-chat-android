package org.telegram.porcodio;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cuore del portafoglio non-custodial (Java puro, senza dipendenze Android, quindi testabile in CI):
 * Keccak-256, secp256k1 (ECDSA deterministico RFC 6979), BIP39, BIP32/BIP44 (m/44'/60'/0'/0/0), indirizzi EIP-55,
 * RLP e firma di transazioni (legacy EIP-155 e EIP-1559 tipo 2) per la rete Base.
 * Nessuna chiave viene mai scritta nei log.
 */
public final class PcWalletCore {
    private PcWalletCore() {
    }

    // ---------- Keccak-256 ----------
    private static final long[] RC = {
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
        0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
        0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
        0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L};
    private static final int[] ROTC = {1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44};
    private static final int[] PILN = {10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1};

    private static void keccakf(long[] st) {
        long[] bc = new long[5];
        for (int r = 0; r < 24; r++) {
            for (int i = 0; i < 5; i++) {
                bc[i] = st[i] ^ st[i + 5] ^ st[i + 10] ^ st[i + 15] ^ st[i + 20];
            }
            for (int i = 0; i < 5; i++) {
                long t = bc[(i + 4) % 5] ^ Long.rotateLeft(bc[(i + 1) % 5], 1);
                for (int j = 0; j < 25; j += 5) {
                    st[j + i] ^= t;
                }
            }
            long t = st[1];
            for (int i = 0; i < 24; i++) {
                int j = PILN[i];
                long b = st[j];
                st[j] = Long.rotateLeft(t, ROTC[i]);
                t = b;
            }
            for (int j = 0; j < 25; j += 5) {
                for (int i = 0; i < 5; i++) {
                    bc[i] = st[j + i];
                }
                for (int i = 0; i < 5; i++) {
                    st[j + i] ^= (~bc[(i + 1) % 5]) & bc[(i + 2) % 5];
                }
            }
            st[0] ^= RC[r];
        }
    }

    public static byte[] keccak256(byte[] in) {
        final int rate = 136;
        long[] st = new long[25];
        byte[] buf = new byte[rate];
        int off = 0;
        while (in.length - off >= rate) {
            absorb(st, in, off, rate);
            off += rate;
        }
        int rem = in.length - off;
        Arrays.fill(buf, (byte) 0);
        System.arraycopy(in, off, buf, 0, rem);
        buf[rem] ^= 0x01;
        buf[rate - 1] ^= (byte) 0x80;
        absorb(st, buf, 0, rate);
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = (byte) (st[i / 8] >>> (8 * (i % 8)));
        }
        return out;
    }

    private static void absorb(long[] st, byte[] b, int off, int len) {
        for (int i = 0; i < len / 8; i++) {
            long v = 0;
            for (int k = 7; k >= 0; k--) {
                v = (v << 8) | (b[off + i * 8 + k] & 0xffL);
            }
            st[i] ^= v;
        }
        keccakf(st);
    }

    // ---------- secp256k1 ----------
    static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    static final BigInteger GX = new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    static final BigInteger GY = new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger TWO = BigInteger.valueOf(2), THREE = BigInteger.valueOf(3);

    private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        if (a == null) return b;
        if (b == null) return a;
        BigInteger lam;
        if (a[0].equals(b[0])) {
            if (!a[1].equals(b[1]) || a[1].signum() == 0) {
                return null;
            }
            lam = a[0].multiply(a[0]).multiply(THREE).multiply(a[1].multiply(TWO).modInverse(P)).mod(P);
        } else {
            lam = b[1].subtract(a[1]).multiply(b[0].subtract(a[0]).modInverse(P)).mod(P);
        }
        BigInteger x = lam.multiply(lam).subtract(a[0]).subtract(b[0]).mod(P);
        BigInteger y = lam.multiply(a[0].subtract(x)).subtract(a[1]).mod(P);
        return new BigInteger[]{x, y};
    }

    static BigInteger[] mul(BigInteger k, BigInteger[] pt) {
        BigInteger[] r = null, q = pt;
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) {
                r = add(r, q);
            }
            q = add(q, q);
        }
        return r;
    }

    private static final BigInteger[] G = {GX, GY};

    private static byte[] to32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }

    public static byte[] publicKeyUncompressed64(byte[] priv) {
        BigInteger[] q = mul(new BigInteger(1, priv), G);
        byte[] out = new byte[64];
        System.arraycopy(to32(q[0]), 0, out, 0, 32);
        System.arraycopy(to32(q[1]), 0, out, 32, 32);
        return out;
    }

    static byte[] publicKeyCompressed(byte[] priv) {
        BigInteger[] q = mul(new BigInteger(1, priv), G);
        byte[] out = new byte[33];
        out[0] = (byte) (q[1].testBit(0) ? 3 : 2);
        System.arraycopy(to32(q[0]), 0, out, 1, 32);
        return out;
    }

    public static String addressFromPrivate(byte[] priv) {
        byte[] h = keccak256(publicKeyUncompressed64(priv));
        byte[] a = Arrays.copyOfRange(h, 12, 32);
        return checksum(hex(a));
    }

    public static String checksum(String hexLower) {
        String a = hexLower.toLowerCase().replace("0x", "");
        byte[] h = keccak256(a.getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            int nib = (h[i / 2] >> (i % 2 == 0 ? 4 : 0)) & 0xf;
            sb.append(Character.isLetter(c) && nib >= 8 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    public static boolean isValidAddress(String s) {
        if (s == null || !s.matches("0x[0-9a-fA-F]{40}")) {
            return false;
        }
        String body = s.substring(2);
        if (body.equals(body.toLowerCase()) || body.equals(body.toUpperCase())) {
            return true;
        }
        return checksum(body).equals(s);
    }

    private static byte[] hmac(String alg, byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, alg));
            for (byte[] p : parts) {
                m.update(p);
            }
            return m.doFinal();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Firma deterministica (RFC 6979, SHA-256) con s basso. Restituisce {r, s, recId}. */
    public static BigInteger[] sign(byte[] hash32, byte[] priv) {
        BigInteger d = new BigInteger(1, priv);
        BigInteger z = new BigInteger(1, hash32);
        byte[] x = to32(d);
        byte[] h1 = to32(z.mod(N));
        byte[] v = new byte[32], k = new byte[32];
        Arrays.fill(v, (byte) 1);
        k = hmac("HmacSHA256", k, v, new byte[]{0}, x, h1);
        v = hmac("HmacSHA256", k, v);
        k = hmac("HmacSHA256", k, v, new byte[]{1}, x, h1);
        v = hmac("HmacSHA256", k, v);
        while (true) {
            v = hmac("HmacSHA256", k, v);
            BigInteger kk = new BigInteger(1, v);
            if (kk.signum() > 0 && kk.compareTo(N) < 0) {
                BigInteger[] R = mul(kk, G);
                BigInteger r = R[0].mod(N);
                if (r.signum() != 0) {
                    BigInteger s = kk.modInverse(N).multiply(z.add(r.multiply(d))).mod(N);
                    if (s.signum() != 0) {
                        int rec = (R[1].testBit(0) ? 1 : 0) | (R[0].compareTo(N) >= 0 ? 2 : 0);
                        if (s.compareTo(N.shiftRight(1)) > 0) {
                            s = N.subtract(s);
                            rec ^= 1;
                        }
                        return new BigInteger[]{r, s, BigInteger.valueOf(rec)};
                    }
                }
            }
            k = hmac("HmacSHA256", k, v, new byte[]{0});
            v = hmac("HmacSHA256", k, v);
        }
    }

    // ---------- BIP39 ----------
    public static String entropyToMnemonic(byte[] entropy, List<String> words) {
        if (words.size() != 2048 || entropy.length % 4 != 0 || entropy.length < 16 || entropy.length > 32) {
            throw new IllegalArgumentException("entropia o lista parole non valida");
        }
        byte[] cs = sha256(entropy);
        int entBits = entropy.length * 8, csBits = entBits / 32;
        int total = entBits + csBits;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total / 11; i++) {
            int idx = 0;
            for (int b = 0; b < 11; b++) {
                int bit = i * 11 + b;
                int val = bit < entBits ? (entropy[bit / 8] >> (7 - bit % 8)) & 1 : (cs[(bit - entBits) / 8] >> (7 - (bit - entBits) % 8)) & 1;
                idx = (idx << 1) | val;
            }
            if (i > 0) sb.append(' ');
            sb.append(words.get(idx));
        }
        return sb.toString();
    }

    public static String generateMnemonic(List<String> words) {
        byte[] e = new byte[16];
        new SecureRandom().nextBytes(e);
        try {
            return entropyToMnemonic(e, words);
        } finally {
            Arrays.fill(e, (byte) 0);
        }
    }

    /** Controlla parole e checksum di una frase da 12/15/18/21/24 parole. */
    public static boolean isValidMnemonic(String mnemonic, List<String> words) {
        if (mnemonic == null) return false;
        String[] w = mnemonic.trim().toLowerCase().split("\\s+");
        if (w.length % 3 != 0 || w.length < 12 || w.length > 24) return false;
        int totalBits = w.length * 11;
        byte[] bits = new byte[(totalBits + 7) / 8];
        for (int i = 0; i < w.length; i++) {
            int idx = words.indexOf(w[i]);
            if (idx < 0) return false;
            for (int b = 0; b < 11; b++) {
                if (((idx >> (10 - b)) & 1) == 1) {
                    int bit = i * 11 + b;
                    bits[bit / 8] |= (byte) (1 << (7 - bit % 8));
                }
            }
        }
        int entBits = totalBits * 32 / 33, csBits = totalBits - entBits;
        byte[] ent = Arrays.copyOf(bits, entBits / 8);
        byte[] cs = sha256(ent);
        for (int i = 0; i < csBits; i++) {
            int a = (cs[i / 8] >> (7 - i % 8)) & 1;
            int bit = entBits + i;
            int b = (bits[bit / 8] >> (7 - bit % 8)) & 1;
            if (a != b) return false;
        }
        return true;
    }

    public static byte[] mnemonicToSeed(String mnemonic, String passphrase) {
        byte[] pw = mnemonic.trim().replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8);
        byte[] salt = ("mnemonic" + (passphrase == null ? "" : passphrase)).getBytes(StandardCharsets.UTF_8);
        return pbkdf2Sha512(pw, salt, 2048, 64);
    }

    public static byte[] pbkdf2Sha512(byte[] password, byte[] salt, int iterations, int dkLen) {
        byte[] out = new byte[dkLen];
        int blocks = (dkLen + 63) / 64;
        for (int i = 1; i <= blocks; i++) {
            byte[] idx = {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i};
            byte[] u = hmac("HmacSHA512", password, salt, idx);
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = hmac("HmacSHA512", password, u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, (i - 1) * 64, Math.min(64, dkLen - (i - 1) * 64));
        }
        return out;
    }

    // ---------- BIP32 ----------
    /** Deriva la chiave privata lungo un percorso tipo m/44'/60'/0'/0/0. */
    public static byte[] derivePrivate(byte[] seed, String path) {
        byte[] i = hmac("HmacSHA512", "Bitcoin seed".getBytes(StandardCharsets.UTF_8), seed);
        byte[] k = Arrays.copyOfRange(i, 0, 32), c = Arrays.copyOfRange(i, 32, 64);
        String[] parts = path.split("/");
        for (int p = 1; p < parts.length; p++) {
            String s = parts[p];
            boolean hard = s.endsWith("'");
            long idx = Long.parseLong(hard ? s.substring(0, s.length() - 1) : s);
            if (hard) idx |= 0x80000000L;
            byte[] ser = {(byte) (idx >>> 24), (byte) (idx >>> 16), (byte) (idx >>> 8), (byte) idx};
            byte[] data = hard ? concat(new byte[]{0}, k, ser) : concat(publicKeyCompressed(k), ser);
            byte[] r = hmac("HmacSHA512", c, data);
            BigInteger child = new BigInteger(1, Arrays.copyOfRange(r, 0, 32)).add(new BigInteger(1, k)).mod(N);
            k = to32(child);
            c = Arrays.copyOfRange(r, 32, 64);
        }
        return k;
    }

    public static final String ETH_PATH = "m/44'/60'/0'/0/0";

    // ---------- RLP ----------
    public static byte[] rlpBytes(byte[] b) {
        if (b.length == 1 && (b[0] & 0xff) < 0x80) return b;
        return concat(rlpLen(b.length, 0x80), b);
    }

    public static byte[] rlpInt(BigInteger v) {
        if (v.signum() == 0) return rlpBytes(new byte[0]);
        byte[] b = v.toByteArray();
        if (b[0] == 0) b = Arrays.copyOfRange(b, 1, b.length);
        return rlpBytes(b);
    }

    public static byte[] rlpList(byte[]... items) {
        int len = 0;
        for (byte[] i : items) len += i.length;
        byte[] body = new byte[len];
        int o = 0;
        for (byte[] i : items) {
            System.arraycopy(i, 0, body, o, i.length);
            o += i.length;
        }
        return concat(rlpLen(len, 0xc0), body);
    }

    private static byte[] rlpLen(int len, int base) {
        if (len < 56) return new byte[]{(byte) (base + len)};
        byte[] l = BigInteger.valueOf(len).toByteArray();
        if (l[0] == 0) l = Arrays.copyOfRange(l, 1, l.length);
        return concat(new byte[]{(byte) (base + 55 + l.length)}, l);
    }

    // ---------- transazioni ----------
    /** Legacy EIP-155 (usata per i vettori di test ufficiali). Restituisce {hashDaFirmare, rawFirmata}. */
    public static byte[][] signLegacy(byte[] priv, BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit, byte[] to, BigInteger value, byte[] data, long chainId) {
        byte[] unsigned = rlpList(rlpInt(nonce), rlpInt(gasPrice), rlpInt(gasLimit), rlpBytes(to), rlpInt(value), rlpBytes(data),
            rlpInt(BigInteger.valueOf(chainId)), rlpInt(BigInteger.ZERO), rlpInt(BigInteger.ZERO));
        byte[] h = keccak256(unsigned);
        BigInteger[] sig = sign(h, priv);
        long v = sig[2].longValue() + chainId * 2 + 35;
        byte[] raw = rlpList(rlpInt(nonce), rlpInt(gasPrice), rlpInt(gasLimit), rlpBytes(to), rlpInt(value), rlpBytes(data),
            rlpInt(BigInteger.valueOf(v)), rlpInt(sig[0]), rlpInt(sig[1]));
        return new byte[][]{h, raw};
    }

    /** EIP-1559 (tipo 2). Restituisce {hashDaFirmare, rawFirmata (con prefisso 0x02)}. */
    public static byte[][] signEip1559(byte[] priv, long chainId, BigInteger nonce, BigInteger maxPriority, BigInteger maxFee, BigInteger gasLimit, byte[] to, BigInteger value, byte[] data) {
        byte[] emptyList = rlpList();
        byte[] unsignedBody = rlpList(rlpInt(BigInteger.valueOf(chainId)), rlpInt(nonce), rlpInt(maxPriority), rlpInt(maxFee), rlpInt(gasLimit),
            rlpBytes(to), rlpInt(value), rlpBytes(data), emptyList);
        byte[] h = keccak256(concat(new byte[]{2}, unsignedBody));
        BigInteger[] sig = sign(h, priv);
        byte[] signedBody = rlpList(rlpInt(BigInteger.valueOf(chainId)), rlpInt(nonce), rlpInt(maxPriority), rlpInt(maxFee), rlpInt(gasLimit),
            rlpBytes(to), rlpInt(value), rlpBytes(data), emptyList, rlpInt(sig[2]), rlpInt(sig[0]), rlpInt(sig[1]));
        return new byte[][]{h, concat(new byte[]{2}, signedBody)};
    }

    // ---------- ERC-20 ----------
    public static byte[] erc20BalanceOfData(String address) {
        return concat(unhex("70a08231"), pad32(unhex(address.substring(2))));
    }

    public static byte[] erc20TransferData(String to, BigInteger amount) {
        return concat(unhex("a9059cbb"), pad32(unhex(to.substring(2))), to32(amount));
    }

    private static byte[] pad32(byte[] b) {
        byte[] out = new byte[32];
        System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    // ---------- utilita' ----------
    public static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static String hex(byte[] b) {
        char[] d = "0123456789abcdef".toCharArray();
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = d[(b[i] >> 4) & 0xf];
            c[i * 2 + 1] = d[b[i] & 0xf];
        }
        return new String(c);
    }

    public static byte[] unhex(String s) {
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.length() % 2 == 1) s = "0" + s;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Importo decimale ("0.015") -> unita' minime con 'decimals' cifre. */
    public static BigInteger parseUnits(String amount, int decimals) {
        return new java.math.BigDecimal(amount.trim().replace(',', '.')).movePointRight(decimals).toBigIntegerExact();
    }

    public static String formatUnits(BigInteger v, int decimals, int shown) {
        java.math.BigDecimal d = new java.math.BigDecimal(v).movePointLeft(decimals).setScale(shown, java.math.RoundingMode.DOWN);
        String s = d.toPlainString();
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.isEmpty() ? "0" : s;
    }

    public static List<String> loadWords(java.io.InputStream in) throws java.io.IOException {
        ArrayList<String> w = new ArrayList<>();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) w.add(line);
        }
        return w;
    }
}
