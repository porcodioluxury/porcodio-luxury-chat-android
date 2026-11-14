package org.telegram.porcodio;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifratura dei file delle chat super segrete (Java puro, testabile). Formato:
 * "PCSM" | versione(1) | prefisso nonce(8) | poi blocchi: lunghezza cifrata(4) | AES-256-GCM(blocco).
 * Il primo blocco contiene il nome originale e il tipo; poi i dati a blocchi da 64 KiB.
 * Nonce del blocco = prefisso(8) + contatore(4). AAD = intestazione + contatore + flag "ultimo": una troncatura, un
 * riordino o un blocco sostituito fanno fallire la decifratura. Il nome del file non e' mai in chiaro nel trasferimento.
 */
public final class PcSecretMediaCore {
    private PcSecretMediaCore() {
    }

    public static final int CHUNK = 64 * 1024;
    private static final byte[] MAGIC = {'P', 'C', 'S', 'M'};
    private static final SecureRandom RNG = new SecureRandom();

    private static Cipher cipher(int mode, byte[] key, byte[] prefix, int counter, boolean last) throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(ByteBuffer.allocate(4 + 1 + 8 + 4 + 1).put(MAGIC).put((byte) 1).put(prefix).putInt(counter).put((byte) (last ? 1 : 0)).array());
        return c;
    }

    public static void encrypt(byte[] key, InputStream in, String name, String mime, OutputStream out) throws IOException, GeneralSecurityException {
        byte[] prefix = new byte[8];
        RNG.nextBytes(prefix);
        out.write(MAGIC);
        out.write(1);
        out.write(prefix);
        byte[] n = (name == null ? "" : name).getBytes(StandardCharsets.UTF_8);
        byte[] m = (mime == null ? "" : mime).getBytes(StandardCharsets.UTF_8);
        ByteBuffer meta = ByteBuffer.allocate(4 + n.length + 4 + m.length).putInt(n.length).put(n).putInt(m.length).put(m);
        int counter = 0;
        writeBlock(out, cipher(Cipher.ENCRYPT_MODE, key, prefix, counter++, false).doFinal(meta.array()));
        byte[] cur = new byte[CHUNK];
        int curLen = fill(in, cur);
        while (true) {
            byte[] next = new byte[CHUNK];
            int nextLen = curLen == CHUNK ? fill(in, next) : 0;
            boolean last = nextLen == 0;
            byte[] part = new byte[curLen];
            System.arraycopy(cur, 0, part, 0, curLen);
            writeBlock(out, cipher(Cipher.ENCRYPT_MODE, key, prefix, counter++, last).doFinal(part));
            if (last) {
                break;
            }
            cur = next;
            curLen = nextLen;
        }
        out.flush();
    }

    private static int fill(InputStream in, byte[] buf) throws IOException {
        int pos = 0;
        while (pos < buf.length) {
            int r = in.read(buf, pos, buf.length - pos);
            if (r < 0) {
                break;
            }
            pos += r;
        }
        return pos;
    }

    private static void writeBlock(OutputStream out, byte[] b) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(b.length).array());
        out.write(b);
    }

    public static class Meta {
        public String name, mime;
    }

    /** Decifra scrivendo i dati in out. Lancia eccezione se il file e' alterato o troncato. */
    public static Meta decrypt(byte[] key, InputStream raw, OutputStream out) throws IOException, GeneralSecurityException {
        DataInputStream in = new DataInputStream(raw);
        byte[] head = new byte[4];
        in.readFully(head);
        if (!java.util.Arrays.equals(head, MAGIC) || in.read() != 1) {
            throw new IOException("formato non valido");
        }
        byte[] prefix = new byte[8];
        in.readFully(prefix);
        int counter = 0;
        byte[] first = readBlock(in);
        if (first == null) {
            throw new IOException("vuoto");
        }
        ByteBuffer meta = ByteBuffer.wrap(cipher(Cipher.DECRYPT_MODE, key, prefix, counter++, false).doFinal(first));
        Meta r = new Meta();
        byte[] n = new byte[meta.getInt()];
        meta.get(n);
        byte[] m = new byte[meta.getInt()];
        meta.get(m);
        r.name = new String(n, StandardCharsets.UTF_8);
        r.mime = new String(m, StandardCharsets.UTF_8);
        byte[] cur = readBlock(in);
        if (cur == null) {
            throw new IOException("troncato");
        }
        while (true) {
            byte[] next = readBlock(in);
            boolean last = next == null;
            out.write(cipher(Cipher.DECRYPT_MODE, key, prefix, counter++, last).doFinal(cur));
            if (last) {
                break;
            }
            cur = next;
        }
        out.flush();
        return r;
    }

    private static byte[] readBlock(DataInputStream in) throws IOException {
        byte[] l = new byte[4];
        try {
            in.readFully(l);
        } catch (EOFException e) {
            return null;
        }
        int len = ByteBuffer.wrap(l).getInt();
        if (len < 16 || len > CHUNK + 64 + 4096) {
            throw new IOException("blocco non valido");
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    public static byte[] encryptBytes(byte[] key, byte[] data, String name, String mime) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        encrypt(key, new java.io.ByteArrayInputStream(data), name, mime, o);
        return o.toByteArray();
    }
}
