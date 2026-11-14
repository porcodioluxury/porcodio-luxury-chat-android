package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Profilo degli interessi: calcolato e salvato SOLO sul dispositivo, cifrato con una chiave dell'Android Keystore.
 * Nessun dato lascia il telefono. Vettore argomento -> peso e affinità per account, entrambi con decadimento nel tempo.
 */
public class PcInterests {
    // ---- pesi dei segnali (config unica)
    public static final double W_DWELL_PER_SEC = 0.15, DWELL_CAP = 3.0, W_COMPLETED = 2.0, W_REWATCH = 1.5;
    public static final double W_LIKE = 3, W_COMMENT = 4, W_SHARE = 5, W_SAVE = 4, W_PROFILE_VISIT = 2, W_OPEN = 1.5;
    public static final double W_SWIPE_AWAY = -1, W_NOT_INTERESTED = -5, W_HIDE = -8;
    public static final double HALF_LIFE_DAYS = 14;
    public static final double TOPIC_REF = 8; // peso per cui la somiglianza di un argomento vale 1
    public static final double AUTHOR_REF = 12;
    public static final int MAX_TOPICS = 200, MAX_AUTHORS = 200;
    public static final double HASHTAG_W = 1.0, KEYWORD_W = 0.3;

    private static final String PREFS = "pc_interests", KEY = "v1";
    private static final double MS_PER_DAY = 86400_000.0;

    private static final class E {
        double w;
        long ts;
    }

    private static final LinkedHashMap<String, E> topics = new LinkedHashMap<>();
    private static final LinkedHashMap<Long, E> authors = new LinkedHashMap<>();
    private static final Set<String> hiddenPosts = new HashSet<>();
    private static final Set<Long> hiddenAuthors = new HashSet<>();
    private static boolean loaded;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static double decayed(E e, long now) {
        return e.w * Math.pow(0.5, (now - e.ts) / MS_PER_DAY / HALF_LIFE_DAYS);
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String enc = prefs().getString(KEY, null);
            if (enc == null) {
                return;
            }
            byte[] raw = PcSecret.unwrap(enc);
            if (raw == null) {
                return;
            }
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONObject t = o.optJSONObject("t");
            if (t != null) {
                for (Iterator<String> it = t.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = t.getJSONObject(k);
                    E e = new E();
                    e.w = v.getDouble("w");
                    e.ts = v.getLong("ts");
                    topics.put(k, e);
                }
            }
            JSONObject a = o.optJSONObject("a");
            if (a != null) {
                for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = a.getJSONObject(k);
                    E e = new E();
                    e.w = v.getDouble("w");
                    e.ts = v.getLong("ts");
                    authors.put(Long.parseLong(k), e);
                }
            }
            org.json.JSONArray hp = o.optJSONArray("hp");
            if (hp != null) {
                for (int i = 0; i < hp.length(); i++) hiddenPosts.add(hp.getString(i));
            }
            org.json.JSONArray ha = o.optJSONArray("ha");
            if (ha != null) {
                for (int i = 0; i < ha.length(); i++) hiddenAuthors.add(ha.getLong(i));
            }
        } catch (Throwable ignore) {
        }
    }

    private static synchronized void save() {
        try {
            JSONObject o = new JSONObject();
            JSONObject t = new JSONObject();
            for (Map.Entry<String, E> en : topics.entrySet()) {
                t.put(en.getKey(), new JSONObject().put("w", en.getValue().w).put("ts", en.getValue().ts));
            }
            JSONObject a = new JSONObject();
            for (Map.Entry<Long, E> en : authors.entrySet()) {
                a.put(String.valueOf(en.getKey()), new JSONObject().put("w", en.getValue().w).put("ts", en.getValue().ts));
            }
            o.put("t", t).put("a", a);
            o.put("hp", new org.json.JSONArray(hiddenPosts));
            org.json.JSONArray ha = new org.json.JSONArray();
            for (long id : hiddenAuthors) ha.put(id);
            o.put("ha", ha);
            prefs().edit().putString(KEY, PcSecret.wrap(o.toString().getBytes(StandardCharsets.UTF_8))).apply();
        } catch (Throwable ignore) {
        }
    }

    // ---- argomenti di un contenuto: hashtag (peso pieno) e parole chiave della didascalia (peso basso)
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
        "della", "delle", "degli", "dello", "questo", "questa", "questi", "queste", "sono", "come", "anche", "quando", "dove", "perché", "perche",
        "molto", "tutti", "tutto", "nella", "nelle", "negli", "alla", "alle", "agli", "allo", "dalla", "dalle", "essere", "stato", "cosa", "così",
        "with", "this", "that", "from", "have", "your", "about", "there", "their", "which", "would", "could", "where", "when", "what", "more"));

    public static LinkedHashMap<String, Double> topicsOf(String text) {
        LinkedHashMap<String, Double> res = new LinkedHashMap<>();
        if (text == null) {
            return res;
        }
        String low = text.toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#([\\p{L}\\p{N}_]{2,32})").matcher(low);
        while (m.find()) {
            String tag = m.group(1);
            if (tag.equals("cover") || tag.equals("links")) {
                continue;
            }
            res.put(tag, HASHTAG_W);
        }
        java.util.regex.Matcher w = java.util.regex.Pattern.compile("[\\p{L}]{5,24}").matcher(low.replaceAll("#[\\p{L}\\p{N}_]+", " ").replaceAll("https?://\\S+", " "));
        int n = 0;
        while (w.find() && n < 12) {
            String word = w.group();
            if (!STOP.contains(word) && !res.containsKey(word)) {
                res.put(word, KEYWORD_W);
                n++;
            }
        }
        return res;
    }

    // ---- segnali
    public static synchronized void record(PcBoard.Post p, double signal) {
        load();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Double> t : topicsOf(p.text).entrySet()) {
            E e = topics.get(t.getKey());
            if (e == null) {
                e = new E();
                topics.put(t.getKey(), e);
            }
            e.w = Math.max(-TOPIC_REF * 2, Math.min(TOPIC_REF * 3, decayed(e, now) + signal * t.getValue()));
            e.ts = now;
        }
        E a = authors.get(p.authorId);
        if (a == null) {
            a = new E();
            authors.put(p.authorId, a);
        }
        a.w = Math.max(-AUTHOR_REF, Math.min(AUTHOR_REF * 2, decayed(a, now) + signal));
        a.ts = now;
        trim();
        save();
    }

    public static synchronized void recordAuthor(long authorId, double signal) {
        load();
        long now = System.currentTimeMillis();
        E a = authors.get(authorId);
        if (a == null) {
            a = new E();
            authors.put(authorId, a);
        }
        a.w = Math.max(-AUTHOR_REF, Math.min(AUTHOR_REF * 2, decayed(a, now) + signal));
        a.ts = now;
        save();
    }

    public static synchronized void recordDwell(PcBoard.Post p, long millis, boolean video) {
        if (millis < 1000) {
            record(p, W_SWIPE_AWAY);
            return;
        }
        double sec = millis / 1000.0;
        double s = Math.min(DWELL_CAP, sec * W_DWELL_PER_SEC);
        if (video && sec >= 12) {
            s += W_COMPLETED;
        }
        record(p, s);
    }

    private static void trim() {
        while (topics.size() > MAX_TOPICS) {
            String k = topics.keySet().iterator().next();
            topics.remove(k);
        }
        while (authors.size() > MAX_AUTHORS) {
            Long k = authors.keySet().iterator().next();
            authors.remove(k);
        }
    }

    // ---- letture
    /** Somiglianza di un contenuto agli interessi, 0..1 (solo argomenti con peso positivo). */
    public static synchronized double similarity(PcBoard.Post p) {
        load();
        long now = System.currentTimeMillis();
        double sum = 0, tot = 0;
        for (Map.Entry<String, Double> t : topicsOf(p.text).entrySet()) {
            E e = topics.get(t.getKey());
            tot += t.getValue();
            if (e != null) {
                double w = decayed(e, now);
                sum += t.getValue() * Math.max(-1, Math.min(1, w / TOPIC_REF));
            }
        }
        return tot == 0 ? 0 : Math.max(0, Math.min(1, sum / Math.max(1, tot)));
    }

    public static synchronized double negativeSimilarity(PcBoard.Post p) {
        load();
        long now = System.currentTimeMillis();
        double sum = 0, tot = 0;
        for (Map.Entry<String, Double> t : topicsOf(p.text).entrySet()) {
            E e = topics.get(t.getKey());
            tot += t.getValue();
            if (e != null) {
                double w = decayed(e, now);
                if (w < 0) {
                    sum += t.getValue() * Math.min(1, -w / TOPIC_REF);
                }
            }
        }
        return tot == 0 ? 0 : Math.min(1, sum / Math.max(1, tot));
    }

    /** Affinità con l'autore, 0..1 (le interazioni negative non scendono sotto 0: le gestisce la penalità). */
    public static synchronized double authorAffinity(long authorId) {
        load();
        E e = authors.get(authorId);
        if (e == null) {
            return 0;
        }
        return Math.max(0, Math.min(1, decayed(e, System.currentTimeMillis()) / AUTHOR_REF));
    }

    public static synchronized boolean hasAuthor(long authorId) {
        load();
        return authors.containsKey(authorId);
    }

    public static synchronized ArrayList<Object[]> topTopics(int limit) {
        load();
        long now = System.currentTimeMillis();
        ArrayList<Object[]> res = new ArrayList<>();
        for (Map.Entry<String, E> en : topics.entrySet()) {
            res.add(new Object[]{en.getKey(), decayed(en.getValue(), now)});
        }
        java.util.Collections.sort(res, (x, y) -> Double.compare(Math.abs((Double) y[1]), Math.abs((Double) x[1])));
        return new ArrayList<>(res.subList(0, Math.min(limit, res.size())));
    }

    public static synchronized void adjustTopic(String topic, double delta) {
        load();
        long now = System.currentTimeMillis();
        E e = topics.get(topic);
        if (e == null) {
            e = new E();
            topics.put(topic, e);
        }
        e.w = Math.max(-TOPIC_REF * 2, Math.min(TOPIC_REF * 3, decayed(e, now) + delta));
        e.ts = now;
        save();
    }

    public static synchronized void reset() {
        load();
        topics.clear();
        authors.clear();
        hiddenPosts.clear();
        hiddenAuthors.clear();
        prefs().edit().remove(KEY).apply();
    }

    public static synchronized void hidePost(PcBoard.Post p) {
        load();
        hiddenPosts.add(p.chatId + ":" + p.msgId);
        record(p, W_NOT_INTERESTED);
    }

    public static synchronized void hideAuthor(long authorId) {
        load();
        hiddenAuthors.add(authorId);
        recordAuthor(authorId, W_HIDE);
    }

    public static synchronized boolean isHidden(PcBoard.Post p) {
        load();
        return hiddenPosts.contains(p.chatId + ":" + p.msgId) || hiddenAuthors.contains(p.authorId);
    }

    private static final Set<String> reported = new HashSet<>();

    public static synchronized void report(PcBoard.Post p) {
        reported.add(p.chatId + ":" + p.msgId);
        prefs().edit().putStringSet("reported", new HashSet<>(reported)).apply();
    }

    public static synchronized boolean isReported(PcBoard.Post p) {
        if (reported.isEmpty()) {
            reported.addAll(prefs().getStringSet("reported", new HashSet<>()));
        }
        return reported.contains(p.chatId + ":" + p.msgId);
    }
}
