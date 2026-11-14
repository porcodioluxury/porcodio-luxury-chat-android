import org.telegram.porcodio.PcUpdateLogic;

/** Test senza framework: java -cp out PcUpdateLogicTest. Esce con errore se qualcosa non torna. */
public class PcUpdateLogicTest {
    static int fails;

    static void check(boolean ok, String what) {
        if (!ok) {
            fails++;
            System.out.println("FALLITO: " + what);
        }
    }

    public static void main(String[] a) throws Exception {
        // confronto versioni: v8 (7307) contro APK installati v7 (73069) e v8 (73079)
        check(PcUpdateLogic.installedBase(73079) == 7307, "base 73079");
        check(PcUpdateLogic.isNewer(7307, 73069), "v8 piu' nuova di v7");
        check(!PcUpdateLogic.isNewer(7307, 73079), "v8 non piu' nuova di v8");
        check(!PcUpdateLogic.isNewer(7307, 73080), "v8 non piu' nuova di una build piu' recente");
        check(PcUpdateLogic.isNewer(7308, 73079), "v9 piu' nuova di v8");
        check(PcUpdateLogic.isNewer(7305, 12), "build di prova con codice basso");
        // tempi
        long h = 3600_000L, now = 100 * h;
        check(PcUpdateLogic.shouldAutoCheck(now, 0, 0, 6 * h, 900_000L), "mai controllato");
        check(!PcUpdateLogic.shouldAutoCheck(now, now - 1 * h, now - 1 * h, 6 * h, 900_000L), "controllato da 1 ora");
        check(PcUpdateLogic.shouldAutoCheck(now, now - 7 * h, now - 7 * h, 6 * h, 900_000L), "controllato da 7 ore");
        check(!PcUpdateLogic.shouldAutoCheck(now, now - 7 * h, now - 60_000L, 6 * h, 900_000L), "fallito 1 minuto fa: aspetta");
        check(PcUpdateLogic.shouldAutoCheck(now, now - 7 * h, now - 20 * 60_000L, 6 * h, 900_000L), "fallito 20 minuti fa: riprova");
        // host
        check(PcUpdateLogic.allowedHost("https://github.com/x/y"), "github");
        check(PcUpdateLogic.allowedHost("https://release-assets.githubusercontent.com/a?b=c"), "cdn");
        check(!PcUpdateLogic.allowedHost("http://github.com/x"), "http rifiutato");
        check(!PcUpdateLogic.allowedHost("https://evilgithub.com/x"), "host simile");
        check(!PcUpdateLogic.allowedHost("https://github.com.evil.io/x"), "suffisso");
        check(PcUpdateLogic.resolve("https://github.com/a/b/c", "/d/e").equals("https://github.com/d/e"), "redirect relativo");
        // manifest reale (stesso formato prodotto da android.yml, con virgolette e accenti nel changelog)
        String sha = "9f513ae27613377ff762a722bac7d098530cc221f3baa6f029052c6ad66ce58d";
        String json = "{\n  \"versionCode\": 7307,\n  \"versionName\": \"13.2.2-v8\",\n  \"apkUrl\": \"" + PcUpdateLogic.APK_PREFIX
            + "v13.2.2-v8/porcodio-luxury-chat-13.2.2-v8.apk\",\n  \"sha256\":\"" + sha + "\",\n  \"size\": 42127910,\n  \"changelog\": \"v8: pulizia, l'account \\\"ufficiale\\\" \\u00e8 pronto\"\n}\n";
        PcUpdateLogic.Info i = PcUpdateLogic.parse(json, false);
        check(i.versionCode == 7307 && i.versionName.equals("13.2.2-v8") && i.size == 42127910L && i.sha256.equals(sha), "parse manifest");
        check(i.changelog.contains("\"ufficiale\"") && i.changelog.contains("\u00e8"), "escape changelog");
        // manifest non validi
        String[] bad = {
            json.replace(PcUpdateLogic.APK_PREFIX, "https://evil.example/"),
            json.replace(sha, "abc"),
            json.replace("\"versionCode\": 7307,", ""),
            "non json",
            json.replace("v13.2.2-v8.apk", "v13.2.2-v8.zip").replace("chat-13.2.2-v8.apk", "chat.zip"),
        };
        for (int k = 0; k < bad.length; k++) {
            boolean threw = false;
            try {
                PcUpdateLogic.parse(bad[k], false);
            } catch (Exception e) {
                threw = true;
            }
            check(threw, "manifest non valido #" + k);
        }
        String test = json.replace(PcUpdateLogic.APK_PREFIX + "v13.2.2-v8/", "http://10.0.2.2:8000/");
        boolean threw = false;
        try {
            PcUpdateLogic.parse(test, false);
        } catch (Exception e) {
            threw = true;
        }
        check(threw, "URL di prova rifiutato in release");
        check(PcUpdateLogic.parse(test, true).apkUrl.startsWith("http://10.0.2.2:8000/"), "URL di prova ammesso in debug");
        if (fails > 0) {
            System.out.println(fails + " controlli falliti");
            System.exit(1);
        }
        System.out.println("PcUpdateLogic: tutti i controlli ok");
    }
}
