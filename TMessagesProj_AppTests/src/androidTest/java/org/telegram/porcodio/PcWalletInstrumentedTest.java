package org.telegram.porcodio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test strumentali del portafoglio su emulatore (Keystore reale, PIN, QR, firma, RPC reali). Nessun fondo reale. */
public class PcWalletInstrumentedTest {
    static final String MNEMONIC = "test test test test test test test test test test test junk"; // chiave di prova pubblica (Hardhat)
    static final String ADDR0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String ADDR1 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    @Before
    public void setUp() {
        if (ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        }
        PcWalletStore.wipe();
    }

    @Test
    public void createImportPinAndKeystore() throws Exception {
        List<String> words = PcWalletCore.loadWords(ApplicationLoader.applicationContext.getAssets().open("bip39_en.txt"));
        assertEquals(2048, words.size());
        String gen = PcWalletCore.generateMnemonic(words);
        assertTrue("mnemonic generata valida", PcWalletCore.isValidMnemonic(gen, words));
        assertEquals(12, gen.split(" ").length);
        assertTrue(PcWalletCore.isValidMnemonic(MNEMONIC, words));
        assertFalse(PcWalletCore.isValidMnemonic("test test test test test test test test test test test test", words));

        assertFalse(PcWalletStore.exists());
        PcWalletStore.create(MNEMONIC, "4821");           // import con frase nota, PIN, cifratura Keystore
        assertTrue(PcWalletStore.exists());
        assertEquals(ADDR0, PcWalletStore.address());
        assertTrue(PcWalletStore.isUnlocked());
        PcWalletStore.lock();
        assertFalse(PcWalletStore.isUnlocked());
        assertFalse("PIN errato", PcWalletStore.unlockWithPin("0000"));
        assertTrue("PIN corretto", PcWalletStore.unlockWithPin("4821"));
        assertEquals(MNEMONIC, PcWalletStore.mnemonic());
        // HD: indirizzo 1 e avanzamento indice
        assertEquals(ADDR0, PcWalletStore.addressAt(0));
        assertEquals(ADDR1, PcWalletStore.addressAt(1));
        assertEquals(0, PcWalletStore.currentIndex());
        assertTrue(PcWalletStore.advanceIndex());
        assertEquals(1, PcWalletStore.currentIndex());
        PcWalletStore.changePin("1357");
        PcWalletStore.lock();
        assertFalse(PcWalletStore.unlockWithPin("4821"));
        assertTrue(PcWalletStore.unlockWithPin("1357"));
        PcWalletStore.wipe();
        assertFalse(PcWalletStore.exists());
    }

    @Test
    public void qrRoundTrip() throws Exception {
        int size = 600;
        BitMatrix m = new QRCodeWriter().encode(ADDR0, BarcodeFormat.QR_CODE, size, size);
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        int[] back = new int[size * size];
        bmp.getPixels(back, 0, size, 0, 0, size, size);
        String text = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size, back)))).getText();
        assertEquals(ADDR0, text);
        PcWalletCore.Payment p = PcWalletCore.parsePayment(text);
        assertNotNull(p);
        assertEquals(ADDR0, p.address);
        // EIP-681 in QR
        String uri = "ethereum:" + ADDR1 + "@8453?value=1e15";
        BitMatrix m2 = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                back[y * size + x] = m2.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        String t2 = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size, back)))).getText();
        PcWalletCore.Payment p2 = PcWalletCore.parsePayment(t2);
        assertEquals(ADDR1, p2.address);
        assertEquals(new BigInteger("1000000000000000"), p2.value);
    }

    @Test
    public void signTransaction() throws Exception {
        byte[] priv = PcWalletCore.derivePrivate(PcWalletCore.mnemonicToSeed(MNEMONIC, ""), PcWalletCore.ethPath(0));
        byte[][] signed = PcWalletCore.signEip1559(priv, 84532L, BigInteger.ZERO, BigInteger.valueOf(1_000_000L), BigInteger.valueOf(2_000_000_000L),
            BigInteger.valueOf(21000), PcWalletCore.unhex(ADDR1.substring(2)), BigInteger.valueOf(1), new byte[0]);
        assertEquals(0x02, signed[1][0]);
        byte[][] again = PcWalletCore.signEip1559(priv, 84532L, BigInteger.ZERO, BigInteger.valueOf(1_000_000L), BigInteger.valueOf(2_000_000_000L),
            BigInteger.valueOf(21000), PcWalletCore.unhex(ADDR1.substring(2)), BigInteger.valueOf(1), new byte[0]);
        assertEquals(PcWalletCore.hex(signed[1]), PcWalletCore.hex(again[1])); // deterministica (RFC 6979)
    }

    // ---- rete reale ----
    private static JSONObject rpc(String url, String method, JSONArray params) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream o = c.getOutputStream()) {
            o.write(new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString().getBytes(StandardCharsets.UTF_8));
        }
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return new JSONObject(bo.toString("UTF-8"));
    }

    @Test
    public void walletRpcBaseMainnetBalanceViaRotation() throws Exception {
        final BigInteger[] out = new BigInteger[1];
        final String[] err = new String[1];
        final CountDownLatch l = new CountDownLatch(1);
        // l'indirizzo "dead" ha sempre un saldo ETH > 0 su Base
        PcWalletRpc.ethBalance("0x000000000000000000000000000000000000dEaD", (v, e) -> {
            out[0] = v;
            err[0] = e;
            l.countDown();
        });
        assertTrue("timeout RPC", l.await(60, TimeUnit.SECONDS));
        assertNotNull("errore RPC: " + err[0], out[0]);
        assertTrue(out[0].signum() > 0);
    }

    @Test
    public void baseSepoliaSignatureAcceptedByNode() throws Exception {
        final String url = "https://sepolia.base.org";
        assertEquals("0x14a34", rpc(url, "eth_chainId", new JSONArray()).getString("result"));
        // stima gas reale per un trasferimento semplice
        JSONObject tx = new JSONObject().put("from", ADDR0).put("to", ADDR1).put("value", "0x0");
        assertEquals("0x5208", rpc(url, "eth_estimateGas", new JSONArray().put(tx)).getString("result"));
        // trasmissione simulata: il nodo deve decodificare la firma e recuperare il mittente. Con un conto senza fondi risponde
        // "insufficient funds ... address <nostro indirizzo>", oppure "already known"/"nonce" se qualcuno ha fondi: in ogni caso la firma e' valida.
        byte[] priv = PcWalletCore.derivePrivate(PcWalletCore.mnemonicToSeed(MNEMONIC, ""), PcWalletCore.ethPath(0));
        BigInteger nonce = new BigInteger(rpc(url, "eth_getTransactionCount", new JSONArray().put(ADDR0).put("pending")).getString("result").substring(2), 16);
        byte[][] signed = PcWalletCore.signEip1559(priv, 84532L, nonce, BigInteger.valueOf(1_000_000L), BigInteger.valueOf(2_000_000_000L),
            BigInteger.valueOf(21000), PcWalletCore.unhex(ADDR1.substring(2)), BigInteger.valueOf(1), new byte[0]);
        JSONObject r = rpc(url, "eth_sendRawTransaction", new JSONArray().put("0x" + PcWalletCore.hex(signed[1])));
        String msg = r.has("error") ? r.getJSONObject("error").optString("message") : "ACCETTATA " + r.optString("result");
        android.util.Log.i("PcWalletTest", "sepolia sendRaw: " + msg);
        assertFalse("firma rifiutata: " + msg, msg.toLowerCase().contains("invalid sender") || msg.toLowerCase().contains("invalid signature") || msg.toLowerCase().contains("rlp"));
        assertTrue("risposta inattesa: " + msg, msg.toLowerCase().contains("insufficient funds") || msg.startsWith("ACCETTATA") || msg.toLowerCase().contains("known") || msg.toLowerCase().contains("nonce"));
        if (msg.toLowerCase().contains("insufficient funds")) {
            assertTrue("il nodo ha recuperato il nostro indirizzo", msg.toLowerCase().contains(ADDR0.toLowerCase()));
        }
    }
}
