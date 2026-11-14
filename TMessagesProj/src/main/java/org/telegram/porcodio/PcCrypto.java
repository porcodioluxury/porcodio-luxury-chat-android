package org.telegram.porcodio;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Primitive per le chat super segrete: X25519 (RFC 7748), HKDF-SHA256, AES-256-GCM. Solo javax.crypto, nessuna libreria esterna. */
public class PcCrypto {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] newPrivateKey() {
        return randomBytes(32);
    }

    public static byte[] publicKey(byte[] priv) {
        byte[] nine = new byte[32];
        nine[0] = 9;
        return x25519(priv, nine);
    }

    public static byte[] sharedSecret(byte[] priv, byte[] peerPub) {
        return x25519(priv, peerPub);
    }

    private static BigInteger decode(byte[] le, boolean maskTop) {
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[31 - i] = le[i];
        }
        if (maskTop) {
            be[0] &= 0x7f;
        }
        return new BigInteger(1, be);
    }

    private static byte[] encode(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    public static byte[] x25519(byte[] k, byte[] u) {
        byte[] kk = k.clone();
        kk[0] &= 248;
        kk[31] &= 127;
        kk[31] |= 64;
        BigInteger x1 = decode(u, true).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = (kk[t / 8] >> (t % 8)) & 1;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P);
            BigInteger df = da.subtract(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        return encode(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P));
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    public static byte[] sha256(byte[]... parts) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (byte[] p : parts) {
            md.update(p);
        }
        return md.digest();
    }

    /** nonce(12) || ciphertext || tag(16) */
    public static byte[] seal(byte[] key, byte[] plain) throws Exception {
        byte[] nonce = randomBytes(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    public static byte[] open(byte[] key, byte[] blob) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, blob, 0, 12));
        return c.doFinal(blob, 12, blob.length - 12);
    }
}
