package org.telegram.porcodio;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Algoritmo di distribuzione del social interno (post, reel, storie). Tutto lato client, a costo zero.
 * Documentazione con esempi numerici: docs/ALGORITMO.md. Tutti i pesi sono in {@link Config}.
 */
public class PcRanking {
    /** Un'unica config per tutti i pesi. */
    public static final class Config {
        // freschezza
        public double halfLifeHours = 24;          // feed
        public double exploreHalfLifeHours = 72;   // Esplora premia anche contenuti meno recenti
        // qualità / coinvolgimento globale
        public double wLike = 1, wComment = 2, wShare = 3, wSave = 2.5, wCompletion = 10, wView = 0.05;
        public double engagementRef = 200;         // punteggio S che porta Q al massimo
        public double qualityMax = 2.0;            // Q va da 1 a qualityMax
        // somiglianza e affinità
        public double wSimilarity = 1.0, wAffinity = 0.8, contactBonus = 0.3;
        public double exploreSimilarity = 0.5, noveltyBonus = 0.2;
        public double negativeInterestPenalty = 0.5; // moltiplicatore massimo tolto da argomenti non graditi
        // verificati (attivi solo se la verifica è attiva)
        public double verifiedMultiplier = 1.5, exploreBoost = 1.5;
        public int exploreVerifiedEvery = 5;
        // penalità
        public double spamPenalty = 0.25, reportedPenalty = 0.1;
        public double diversityFactor = 0.7;       // ogni post consecutivo dello stesso autore vale x0.7 in più
        // esplorazione nel feed
        public double explorationShare = 0.15;
        // account ufficiale
        public int officialExploreIndex = 1;       // posizione (0-based) del post ufficiale in Esplora, sempre entro le prime 3
        // visualizzazione
        public String officialLabelKey = "PcOfficial";
    }

    public static final Config C = new Config();

    public interface VerifiedSource {
        boolean isVerified(long userId);
    }

    /** Fonte dello stato verificato. Di default nessuno è verificato (la verifica a pagamento è dietro flag, v8). */
    public static VerifiedSource verifiedSource = userId -> false;

    // ---- account ufficiale (ID configurabili: res/values/pc_config.xml, stringa pc_official_user_ids)
    private static long[] officialIds;

    public static synchronized long[] officialIds() {
        if (officialIds == null) {
            ArrayList<Long> ids = new ArrayList<>();
            try {
                int id = ApplicationLoader.applicationContext.getResources().getIdentifier("pc_official_user_ids", "string", ApplicationLoader.applicationContext.getPackageName());
                String s = id == 0 ? "" : ApplicationLoader.applicationContext.getString(id);
                for (String part : s.split("[,\\s]+")) {
                    if (!part.isEmpty()) {
                        ids.add(Long.parseLong(part));
                    }
                }
            } catch (Throwable ignore) {
            }
            officialIds = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                officialIds[i] = ids.get(i);
            }
        }
        return officialIds;
    }

    public static boolean isOfficial(long userId) {
        for (long id : officialIds()) {
            if (id == userId) {
                return true;
            }
        }
        return false;
    }

    // ---- punteggio
    public static final class Explain {
        public double fresh, quality, similarity, affinity, interestFactor, verified = 1, penalty = 1, total;
        public boolean official, exploration, spam, reported, contact, explore;
        public String topics = "";
    }

    private static final String[] SPAM_WORDS = {"crypto", "cripto", "bitcoin", "airdrop", "forex", "guadagn", "casino", "scommess", "giveaway",
        "clicca qui", "click here", "link in bio", "soldi facili", "onlyfans", "prestito"};

    public static boolean looksSpam(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase();
        int hits = 0;
        for (String w : SPAM_WORDS) {
            if (low.contains(w)) {
                hits++;
            }
        }
        int links = low.split("https?://", -1).length - 1;
        return hits >= 2 || links >= 3;
    }

    public static Explain explain(int account, PcBoard.Post p, boolean explore, long now) {
        Explain x = new Explain();
        x.explore = explore;
        double ageH = Math.max(0, (now / 1000.0 - p.date) / 3600.0);
        x.fresh = Math.pow(0.5, ageH / (explore ? C.exploreHalfLifeHours : C.halfLifeHours));
        double s = C.wLike * p.likes + C.wComment * p.comments + C.wShare * p.shares + C.wView * p.views;
        x.quality = 1 + Math.min(1, Math.log(1 + s) / Math.log(1 + C.engagementRef)) * (C.qualityMax - 1);
        x.similarity = PcInterests.similarity(p);
        x.contact = ContactsController.getInstance(account).isContact(p.authorId);
        x.affinity = Math.min(1, PcInterests.authorAffinity(p.authorId) + (x.contact ? C.contactBonus : 0));
        double neg = PcInterests.negativeSimilarity(p);
        if (explore) {
            x.interestFactor = 1 + C.exploreSimilarity * x.similarity + (PcInterests.hasAuthor(p.authorId) ? 0 : C.noveltyBonus);
        } else {
            x.interestFactor = 1 + C.wSimilarity * x.similarity + C.wAffinity * x.affinity;
        }
        x.interestFactor *= 1 - C.negativeInterestPenalty * neg;
        x.verified = verifiedSource.isVerified(p.authorId) ? (explore ? C.exploreBoost : C.verifiedMultiplier) : 1;
        x.spam = looksSpam(p.text);
        x.reported = PcInterests.isReported(p);
        x.penalty = (x.spam ? C.spamPenalty : 1) * (x.reported ? C.reportedPenalty : 1);
        x.official = isOfficial(p.authorId);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String t : PcInterests.topicsOf(p.text).keySet()) {
            if (n++ < 4) {
                sb.append(sb.length() == 0 ? "" : ", ").append(t);
            }
        }
        x.topics = sb.toString();
        x.total = x.fresh * x.quality * x.interestFactor * x.verified * x.penalty;
        return x;
    }

    private static ArrayList<PcBoard.Post> visible(List<PcBoard.Post> in) {
        ArrayList<PcBoard.Post> res = new ArrayList<>();
        for (PcBoard.Post p : in) {
            if (!PcBoard.isSpecial(p) && !PcInterests.isHidden(p)) {
                res.add(p);
            }
        }
        return res;
    }

    private static PcBoard.Post newestOfficial(List<PcBoard.Post> list, boolean needMedia) {
        PcBoard.Post best = null;
        for (PcBoard.Post p : list) {
            if (isOfficial(p.authorId) && (!needMedia || PcBoard.hasMedia(p)) && (best == null || p.date > best.date)) {
                best = p;
            }
        }
        return best;
    }

    /** Diversità: se gli ultimi k post sono dello stesso autore, il candidato vale x(diversityFactor^k). */
    private static ArrayList<PcBoard.Post> diversify(ArrayList<PcBoard.Post> sorted, java.util.IdentityHashMap<PcBoard.Post, Double> score) {
        ArrayList<PcBoard.Post> pool = new ArrayList<>(sorted);
        ArrayList<PcBoard.Post> out = new ArrayList<>();
        while (!pool.isEmpty()) {
            PcBoard.Post bestP = null;
            double best = -1;
            long last = out.isEmpty() ? 0 : out.get(out.size() - 1).authorId;
            int run = 0;
            for (int i = out.size() - 1; i >= 0 && out.get(i).authorId == last; i--) {
                run++;
            }
            int look = Math.min(pool.size(), 12);
            for (int i = 0; i < look; i++) {
                PcBoard.Post c = pool.get(i);
                double s = score.get(c);
                if (c.authorId == last) {
                    s *= Math.pow(C.diversityFactor, run);
                }
                if (s > best) {
                    best = s;
                    bestP = c;
                }
            }
            out.add(bestP);
            pool.remove(bestP);
        }
        return out;
    }

    private static long daySeed(int account) {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR) + account * 7919L;
    }

    /**
     * Feed "Per te" (chronological=false) oppure "Seguiti" (cronologico). Il post ufficiale più recente sta in uno slot fisso in cima in entrambi.
     */
    public static ArrayList<PcBoard.Post> rankFeed(int account, List<PcBoard.Post> all, boolean chronological) {
        ArrayList<PcBoard.Post> list = visible(all);
        PcBoard.Post official = newestOfficial(list, false);
        if (official != null) {
            list.remove(official);
        }
        ArrayList<PcBoard.Post> out;
        if (chronological) {
            Collections.sort(list, (a, b) -> b.date - a.date);
            out = list;
        } else {
            long now = System.currentTimeMillis();
            final java.util.IdentityHashMap<PcBoard.Post, Double> score = new java.util.IdentityHashMap<>();
            for (PcBoard.Post p : list) {
                score.put(p, explain(account, p, false, now).total);
            }
            Collections.sort(list, (a, b) -> Double.compare(score.get(b), score.get(a)));
            ArrayList<PcBoard.Post> ranked = diversify(list, score);
            out = injectExploration(account, ranked, score);
        }
        if (official != null) {
            out.add(0, official);
        }
        return out;
    }

    /** ~15% del feed: contenuti nuovi o fuori interesse (somiglianza bassa e autore mai interagito), scelti con seme giornaliero. */
    private static ArrayList<PcBoard.Post> injectExploration(int account, ArrayList<PcBoard.Post> ranked, java.util.IdentityHashMap<PcBoard.Post, Double> score) {
        int n = ranked.size();
        int quota = (int) Math.floor(n * C.explorationShare);
        if (quota == 0) {
            return ranked;
        }
        ArrayList<PcBoard.Post> pool = new ArrayList<>();
        for (int i = n - 1; i >= 0 && pool.size() < quota * 3; i--) {
            PcBoard.Post p = ranked.get(i);
            if (PcInterests.similarity(p) < 0.1 && PcInterests.authorAffinity(p.authorId) < 0.05) {
                pool.add(p);
            }
        }
        if (pool.isEmpty()) {
            return ranked;
        }
        Collections.sort(pool, (a, b) -> Double.compare(score.get(b), score.get(a)));
        Random rnd = new Random(daySeed(account));
        ArrayList<PcBoard.Post> chosen = new ArrayList<>(pool.subList(0, Math.min(quota, pool.size())));
        ArrayList<PcBoard.Post> rest = new ArrayList<>(ranked);
        rest.removeAll(chosen);
        Set<Integer> slots = new HashSet<>();
        int tries = 0;
        while (slots.size() < chosen.size() && tries++ < 200) {
            slots.add(2 + rnd.nextInt(Math.max(1, n - 2)));
        }
        ArrayList<Integer> order = new ArrayList<>(slots);
        Collections.sort(order);
        ArrayList<PcBoard.Post> out = new ArrayList<>();
        int ci = 0, ri = 0;
        for (int i = 0; i < n; i++) {
            if (ci < chosen.size() && order.contains(i)) {
                out.add(chosen.get(ci++));
            } else if (ri < rest.size()) {
                out.add(rest.get(ri++));
            }
        }
        while (ci < chosen.size()) {
            out.add(chosen.get(ci++));
        }
        return out;
    }

    public static boolean isExplorationPick(int account, PcBoard.Post p) {
        return PcInterests.similarity(p) < 0.1 && PcInterests.authorAffinity(p.authorId) < 0.05;
    }

    /** Esplora e ricerca: più novità, boost verificati x1,5 e un posto garantito ogni 5, ufficiale entro le prime 3. */
    public static ArrayList<PcBoard.Post> rankExplore(int account, List<PcBoard.Post> all) {
        ArrayList<PcBoard.Post> list = visible(all);
        PcBoard.Post official = newestOfficial(list, true);
        if (official != null) {
            list.remove(official);
        }
        long now = System.currentTimeMillis();
        final java.util.IdentityHashMap<PcBoard.Post, Double> score = new java.util.IdentityHashMap<>();
        for (PcBoard.Post p : list) {
            score.put(p, explain(account, p, true, now).total);
        }
        Collections.sort(list, (a, b) -> Double.compare(score.get(b), score.get(a)));
        ArrayList<PcBoard.Post> out = diversify(list, score);
        // posto garantito ogni N risultati ai verificati
        ArrayList<PcBoard.Post> verified = new ArrayList<>();
        for (PcBoard.Post p : out) {
            if (verifiedSource.isVerified(p.authorId)) {
                verified.add(p);
            }
        }
        for (int slot = C.exploreVerifiedEvery - 1; slot < out.size() && !verified.isEmpty(); slot += C.exploreVerifiedEvery) {
            if (verifiedSource.isVerified(out.get(slot).authorId)) {
                verified.remove(out.get(slot));
                continue;
            }
            PcBoard.Post v = verified.remove(0);
            out.remove(v);
            out.add(Math.min(slot, out.size()), v);
        }
        if (official != null) {
            out.add(Math.min(C.officialExploreIndex, out.size()), official);
        }
        return out;
    }

    /** Righe per la pagina "Perché vedo questo". */
    public static String whyText(int account, PcBoard.Post p, boolean explore) {
        Explain x = explain(account, p, explore, System.currentTimeMillis());
        StringBuilder sb = new StringBuilder();
        if (x.official) {
            sb.append(LocaleController.getString(R.string.PcWhyOfficial)).append("\n\n");
        }
        sb.append(LocaleController.formatString(R.string.PcWhyFresh, String.format("%.2f", x.fresh))).append('\n');
        sb.append(LocaleController.formatString(R.string.PcWhyQuality, String.format("%.2f", x.quality))).append('\n');
        sb.append(LocaleController.formatString(R.string.PcWhyInterest, String.format("%.2f", x.interestFactor),
            String.format("%d%%", Math.round(x.similarity * 100)), String.format("%d%%", Math.round(x.affinity * 100)))).append('\n');
        if (!x.topics.isEmpty()) {
            sb.append(LocaleController.formatString(R.string.PcWhyTopics, x.topics)).append('\n');
        }
        if (x.verified != 1) {
            sb.append(LocaleController.formatString(R.string.PcWhyVerified, String.format("%.1f", x.verified))).append('\n');
        }
        if (x.penalty != 1) {
            sb.append(LocaleController.formatString(R.string.PcWhyPenalty, String.format("%.2f", x.penalty))).append('\n');
        }
        if (!explore && isExplorationPick(account, p)) {
            sb.append(LocaleController.getString(R.string.PcWhyExploration)).append('\n');
        }
        sb.append('\n').append(LocaleController.formatString(R.string.PcWhyTotal, String.format("%.3f", x.total)));
        sb.append("\n\n").append(LocaleController.getString(R.string.PcWhyPrivacy));
        return sb.toString();
    }
}
