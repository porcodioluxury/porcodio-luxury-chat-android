package org.telegram.porcodio;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Logica pura degli aggiornamenti (nessuna dipendenza da Android, così si prova con un test Java semplice in CI):
 * confronto versioni, lettura e validazione del manifest, host ammessi per i download, tempi dei controlli.
 */
public final class PcUpdateLogic {
    private PcUpdateLogic() {
    }

    public static final String APK_PREFIX = "https://github.com/porcodioluxury/porcodio-luxury-chat-android/releases/download/";

    public static final class Info {
        public int versionCode;
        public String versionName, apkUrl, sha256, changelog;
        public long size;
    }

    /** L'APK installato ha versionCode = base*10 + ABI; il manifest usa il codice base. */
    public static int installedBase(long installedVersionCode) {
        return (int) (installedVersionCode / 10);
    }

    public static boolean isNewer(int manifestCode, long installedVersionCode) {
        return manifestCode > installedBase(installedVersionCode);
    }

    /** Controllo automatico: ogni 6 ore dopo un controllo riuscito, ogni 15 minuti dopo uno fallito. */
    public static boolean shouldAutoCheck(long now, long lastOk, long lastTry, long everyMs, long retryMs) {
        if (lastOk == 0 || now - lastOk >= everyMs) {
            return lastTry == 0 || now - lastTry >= retryMs || lastTry < lastOk;
        }
        return false;
    }

    /** Solo https verso GitHub (release e CDN degli asset). */
    public static boolean allowedHost(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(u.getScheme()) && (h.equals("github.com") || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com"));
        } catch (Exception e) {
            return false;
        }
    }

    public static String resolve(String base, String location) throws Exception {
        return new java.net.URI(base).resolve(location).toString();
    }

    public static Info parse(String json, boolean allowTestUrl) throws Exception {
        Map<String, String> m = flatJson(json);
        Info i = new Info();
        i.versionCode = Integer.parseInt(req(m, "versionCode"));
        i.versionName = req(m, "versionName");
        i.apkUrl = req(m, "apkUrl");
        i.sha256 = req(m, "sha256").toLowerCase(Locale.ROOT);
        i.size = m.containsKey("size") ? Long.parseLong(m.get("size")) : 0;
        i.changelog = m.containsKey("changelog") ? m.get("changelog") : "";
        boolean urlOk = i.apkUrl.startsWith(APK_PREFIX) || allowTestUrl && i.apkUrl.startsWith("http://10.0.2.2:");
        if (i.versionCode < 1 || !i.sha256.matches("[0-9a-f]{64}") || !urlOk || !i.apkUrl.toLowerCase(Locale.ROOT).contains(".apk")) {
            throw new Exception("manifest non valido");
        }
        return i;
    }

    private static String req(Map<String, String> m, String k) throws Exception {
        String v = m.get(k);
        if (v == null || v.isEmpty()) {
            throw new Exception("manifest: manca " + k);
        }
        return v;
    }

    /** Parser minimo per un oggetto JSON piatto (stringhe e numeri), con gli escape delle stringhe. */
    static Map<String, String> flatJson(String s) throws Exception {
        Map<String, String> out = new HashMap<>();
        int i = s.indexOf('{');
        if (i < 0) {
            throw new Exception("JSON non valido");
        }
        i++;
        while (true) {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) == '}') {
                break;
            }
            if (s.charAt(i) != '"') {
                throw new Exception("JSON non valido");
            }
            int[] pos = {i};
            String key = str(s, pos);
            i = pos[0];
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':')) {
                i++;
            }
            String val;
            if (s.charAt(i) == '"') {
                pos[0] = i;
                val = str(s, pos);
                i = pos[0];
            } else {
                int j = i;
                while (j < s.length() && ",}".indexOf(s.charAt(j)) < 0) {
                    j++;
                }
                val = s.substring(i, j).trim();
                i = j;
            }
            out.put(key, val);
        }
        return out;
    }

    private static String str(String s, int[] pos) throws Exception {
        StringBuilder b = new StringBuilder();
        int i = pos[0] + 1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                pos[0] = i;
                return b.toString();
            }
            if (c == '\\') {
                char n = s.charAt(i++);
                switch (n) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        throw new Exception("JSON non valido");
    }
}
