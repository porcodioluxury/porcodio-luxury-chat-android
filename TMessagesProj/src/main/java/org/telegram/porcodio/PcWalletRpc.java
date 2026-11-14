package org.telegram.porcodio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Accesso in sola rete alla blockchain Base: RPC pubblico gratuito per saldi, gas e invio; Blockscout per lo storico. */
public final class PcWalletRpc {
    private PcWalletRpc() {
    }

    public static final long CHAIN_ID = 8453;

    public interface Cb<T> {
        void done(T value, String error);
    }

    private static String res(int id) {
        return ApplicationLoader.applicationContext.getString(id);
    }

    public static String porcoContract() {
        String c = res(R.string.pc_porco_contract).trim();
        return PcWalletCore.isValidAddress(c) ? c : "";
    }

    public static int porcoDecimals() {
        try {
            return Integer.parseInt(res(R.string.pc_porco_decimals).trim());
        } catch (Exception e) {
            return 18;
        }
    }

    public static String porcoName() {
        return res(R.string.pc_porco_name);
    }

    public static String porcoSymbol() {
        return res(R.string.pc_porco_symbol);
    }

    public static String explorerTx(String hash) {
        return res(R.string.pc_base_explorer) + "/tx/" + hash;
    }

    private static String http(String urlStr, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
        }
        if (code >= 400) {
            throw new Exception("HTTP " + code);
        }
        return bo.toString("UTF-8");
    }

    private static Object rpc(String method, JSONArray params) throws Exception {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        req.put("params", params);
        JSONObject r = new JSONObject(http(res(R.string.pc_base_rpc), req.toString()));
        if (r.has("error")) {
            JSONObject e = r.getJSONObject("error");
            throw new Exception(e.optString("message", "errore RPC") + " (" + e.optInt("code") + ")");
        }
        return r.get("result");
    }

    private static BigInteger big(Object hex) {
        String s = String.valueOf(hex);
        if (s.startsWith("0x")) s = s.substring(2);
        return s.isEmpty() ? BigInteger.ZERO : new BigInteger(s, 16);
    }

    private static <T> void async(Cb<T> cb, java.util.concurrent.Callable<T> job) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final T v = job.call();
                AndroidUtilities.runOnUIThread(() -> cb.done(v, null));
            } catch (final Throwable t) {
                org.telegram.messenger.FileLog.e("PcWalletRpc: " + t.getMessage());
                AndroidUtilities.runOnUIThread(() -> cb.done(null, t.getMessage() == null ? t.toString() : t.getMessage()));
            }
        });
    }

    public static void ethBalance(final String addr, Cb<BigInteger> cb) {
        async(cb, () -> big(rpc("eth_getBalance", new JSONArray().put(addr).put("latest"))));
    }

    public static void tokenBalance(final String token, final String addr, Cb<BigInteger> cb) {
        async(cb, () -> {
            JSONObject call = new JSONObject().put("to", token).put("data", "0x" + PcWalletCore.hex(PcWalletCore.erc20BalanceOfData(addr)));
            return big(rpc("eth_call", new JSONArray().put(call).put("latest")));
        });
    }

    public static class Quote {
        public BigInteger nonce, gasLimit, maxFee, maxPriority;
        public byte[] to, data;
        public BigInteger value;

        public BigInteger feeWei() {
            return gasLimit.multiply(maxFee);
        }
    }

    /** Prepara l'invio: nonce, stima del gas e commissioni EIP-1559 correnti. */
    public static void quote(final String from, final String toAddr, final BigInteger valueWei, final byte[] data, final String toContractOrNull, Cb<Quote> cb) {
        async(cb, () -> {
            Quote q = new Quote();
            q.to = PcWalletCore.unhex((toContractOrNull != null ? toContractOrNull : toAddr).substring(2));
            q.value = valueWei;
            q.data = data;
            q.nonce = big(rpc("eth_getTransactionCount", new JSONArray().put(from).put("pending")));
            JSONObject tx = new JSONObject().put("from", from).put("to", toContractOrNull != null ? toContractOrNull : toAddr)
                .put("value", "0x" + valueWei.toString(16)).put("data", "0x" + PcWalletCore.hex(data));
            BigInteger est = big(rpc("eth_estimateGas", new JSONArray().put(tx)));
            q.gasLimit = est.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN); // +20% di margine
            JSONObject block = (JSONObject) rpc("eth_getBlockByNumber", new JSONArray().put("latest").put(false));
            BigInteger base = block.has("baseFeePerGas") ? big(block.get("baseFeePerGas")) : big(rpc("eth_gasPrice", new JSONArray()));
            BigInteger prio;
            try {
                prio = big(rpc("eth_maxPriorityFeePerGas", new JSONArray()));
            } catch (Throwable t) {
                prio = BigInteger.valueOf(1_000_000L);
            }
            if (prio.signum() == 0) prio = BigInteger.valueOf(1_000_000L);
            q.maxPriority = prio;
            q.maxFee = base.multiply(BigInteger.valueOf(2)).add(prio);
            return q;
        });
    }

    /** Firma in locale e trasmette. Restituisce l'hash della transazione. */
    public static void send(final Quote q, final byte[] priv, Cb<String> cb) {
        async(cb, () -> {
            byte[][] signed = PcWalletCore.signEip1559(priv, CHAIN_ID, q.nonce, q.maxPriority, q.maxFee, q.gasLimit, q.to, q.value, q.data);
            Object r = rpc("eth_sendRawTransaction", new JSONArray().put("0x" + PcWalletCore.hex(signed[1])));
            return String.valueOf(r);
        });
    }

    public static class Tx {
        public String hash, from, to, symbol;
        public BigInteger value;
        public long time;
        public boolean ok = true;
        public int decimals = 18;
    }

    public static void history(final String addr, Cb<ArrayList<Tx>> cb) {
        async(cb, () -> {
            ArrayList<Tx> out = new ArrayList<>();
            String api = res(R.string.pc_base_history_api);
            JSONObject j = new JSONObject(http(api + "/addresses/" + addr + "/transactions", null));
            JSONArray items = j.optJSONArray("items");
            for (int i = 0; items != null && i < items.length() && i < 25; i++) {
                JSONObject o = items.getJSONObject(i);
                Tx t = new Tx();
                t.hash = o.optString("hash");
                JSONObject f = o.optJSONObject("from"), to = o.optJSONObject("to");
                t.from = f != null ? f.optString("hash") : "";
                t.to = to != null ? to.optString("hash") : "";
                t.value = new BigInteger(o.optString("value", "0"));
                t.symbol = "ETH";
                t.ok = !"error".equals(o.optString("status"));
                t.time = parseTime(o.optString("timestamp"));
                out.add(t);
            }
            java.util.Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
            return out;
        });
    }

    private static long parseTime(String iso) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime() / 1000L;
        } catch (Throwable t) {
            return 0;
        }
    }
}
