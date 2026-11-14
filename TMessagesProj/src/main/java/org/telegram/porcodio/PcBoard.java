package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

/**
 * Bacheca Porcodio: il "social interno". Ogni utente che lo attiva (consenso esplicito) ottiene un canale pubblico
 * con username deterministico {@code plcb_<userId>}, gestito dall'app, e un marcatore discreto nella bio.
 * Chi usa l'app trova le bacheche degli altri, verifica che il creatore del canale sia davvero quell'utente e le
 * mostra come feed. Nessun server nostro: tutto passa dalle API pubbliche di Telegram, con ricerche lente e limitate.
 */
public class PcBoard {
    public static final String MARKER = "\u25C6PLC";
    public static final String ABOUT = "PLCB1 - Bacheca Porcodio Luxury Chat";
    private static final String PREFS = "pc_board";
    private static final long TTL_OK = 7L * 86400_000, TTL_NO = 24L * 3600_000;
    public static final int DAILY_LIMIT = 20;
    private static final long MIN_GAP_MS = 2500;

    public static final String TAG_COVER = "#cover";
    public static final String TAG_LINKS = "#links";

    public static boolean isTagged(Post p, String tag) {
        return p != null && p.text != null && p.text.trim().toLowerCase().startsWith(tag);
    }

    public static boolean isSpecial(Post p) {
        return isTagged(p, TAG_COVER) || isTagged(p, TAG_LINKS);
    }

    public static String linksOf(Post p) {
        if (p == null || p.text == null) {
            return "";
        }
        return p.text.trim().substring(TAG_LINKS.length()).trim();
    }

    public static boolean isVideoPost(Post p) {
        return p.message != null && p.message.media instanceof TLRPC.TL_messageMediaDocument && p.message.media.document != null
            && org.telegram.messenger.MessageObject.isVideoDocument(p.message.media.document);
    }

    public static boolean isPhotoPost(Post p) {
        return p.message != null && p.message.media instanceof TLRPC.TL_messageMediaPhoto && p.message.media.photo != null;
    }

    public static boolean hasMedia(Post p) {
        return isPhotoPost(p) || isVideoPost(p);
    }

    public static void setMedia(org.telegram.ui.Components.BackupImageView img, Post p, int side) {
        try {
            if (isPhotoPost(p)) {
                TLRPC.Photo photo = p.message.media.photo;
                TLRPC.PhotoSize sz = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(photo.sizes, side);
                img.setImage(org.telegram.messenger.ImageLocation.getForPhoto(sz, photo), side + "_" + side, (android.graphics.drawable.Drawable) null, p.message);
            } else if (isVideoPost(p)) {
                TLRPC.Document doc = p.message.media.document;
                TLRPC.PhotoSize sz = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, side);
                if (sz != null) {
                    img.setImage(org.telegram.messenger.ImageLocation.getForDocument(sz, doc), side + "_" + side, (android.graphics.drawable.Drawable) null, p.message);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    public static class Post {
        public long authorId, chatId;
        public int msgId, date, views, likes, comments, shares;
        public String text;
        public TLRPC.Message message;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String username(long uid) {
        return "plcb_" + uid;
    }

    // ---- diagnostica: ultimo errore leggibile + log
    public static volatile String lastError = "";

    public static void fail(String where, String why) {
        lastError = why == null ? "" : why;
        org.telegram.messenger.FileLog.e("PcBoard " + where + ": " + why);
    }

    // ultimo errore RPC visto dall'app (per spiegare un invio rifiutato)
    public static volatile String lastRpcText = "";
    public static volatile int lastRpcCode;
    public static volatile String lastRpcRequest = "";
    public static volatile long lastRpcAt;

    public static void noteRpc(int code, String text, String request) {
        lastRpcCode = code;
        lastRpcText = text == null ? "" : text;
        lastRpcRequest = request == null ? "" : request;
        lastRpcAt = System.currentTimeMillis();
        org.telegram.messenger.FileLog.e("PcBoard rpc error " + code + " " + text + " in " + request);
    }

    /** Descrizione dell'ultimo errore RPC recente (ultimi 3 minuti) con codice preciso, o "" se non ce ne sono. */
    public static String recentRpc() {
        if (System.currentTimeMillis() - lastRpcAt > 180000 || lastRpcText.isEmpty()) {
            return "";
        }
        return lastRpcText + " (codice " + lastRpcCode + ", " + lastRpcRequest + ")";
    }

    public static String rpcText(TLRPC.TL_error e) {
        if (e == null) {
            return "risposta vuota";
        }
        String t = e.text == null ? "" : e.text;
        if (t.startsWith("FLOOD_WAIT")) {
            return "troppe richieste, riprova tra poco (" + t + ")";
        }
        if (t.contains("CHANNELS_TOO_MUCH")) {
            return "hai troppi canali/gruppi: esci da qualcuno e riprova (CHANNELS_TOO_MUCH)";
        }
        if (t.contains("CHANNELS_ADMIN_PUBLIC_TOO_MUCH") || t.contains("USERNAMES_ACTIVE_TOO_MUCH")) {
            return "troppi canali pubblici su questo account (" + t + ")";
        }
        if (t.contains("USERNAME_OCCUPIED") || t.contains("USERNAME_INVALID")) {
            return "username della bacheca non disponibile (" + t + ")";
        }
        if (t.contains("CHAT_ADMIN_REQUIRED") || t.contains("CHAT_WRITE_FORBIDDEN") || t.contains("CHAT_SEND")) {
            return "non hai il permesso di scrivere nella tua bacheca (" + t + ")";
        }
        return t + " (" + e.code + ")";
    }

    /** Garantisce che il canale Bacheca sia noto al client (dopo un riavvio la cache puo' essere vuota) prima di inviare. */
    public static void withBoard(final int account, final Runnable ok, final Result err) {
        final long chat = myBoardChat(account);
        final MessagesController mc = MessagesController.getInstance(account);
        if (chat == 0) {
            fail("withBoard", "la Bacheca non e' attiva");
            err.done(false);
            return;
        }
        if (mc.getChat(chat) != null && mc.getInputPeer(-chat) != null) {
            ok.run();
            return;
        }
        final long me = UserConfig.getInstance(account).getClientUserId();
        mc.getUserNameResolver().resolve(username(me), peer -> {
            if (mc.getChat(chat) != null && mc.getInputPeer(-chat) != null) {
                ok.run();
            } else {
                fail("withBoard", "canale Bacheca " + chat + " non trovato (@" + username(me) + "): riattiva la Bacheca");
                err.done(false);
            }
        });
    }

    // ---- seguiti (locale): usati da Reel > Amici e dal contatore del profilo
    public static boolean isFollowed(long uid) {
        return prefs().getBoolean("fol_" + uid, false);
    }

    public static void setFollowed(long uid, boolean on) {
        prefs().edit().putBoolean("fol_" + uid, on).apply();
    }

    public static int followedCount() {
        int n = 0;
        for (java.util.Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
            if (e.getKey().startsWith("fol_") && Boolean.TRUE.equals(e.getValue())) {
                n++;
            }
        }
        return n;
    }

    // ---- stato proprio
    public static long myBoardChat(int account) {
        return prefs().getLong("mine_" + account, 0);
    }

    public static boolean isEnabled(int account) {
        return myBoardChat(account) != 0;
    }

    public static boolean aboutHasMarker(String about) {
        return about != null && about.contains(MARKER);
    }

    // ---- cache scoperte: uid -> {chat, ts, ok}
    private static JSONObject cache(int account) {
        try {
            return new JSONObject(prefs().getString("cache_" + account, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void saveCache(int account, JSONObject o) {
        prefs().edit().putString("cache_" + account, o.toString()).apply();
    }

    /** chatId della bacheca verificata di un utente (0 se sconosciuta/assente). */
    public static long knownBoard(int account, long uid) {
        JSONObject e = cache(account).optJSONObject(String.valueOf(uid));
        if (e != null && e.optBoolean("ok") && System.currentTimeMillis() - e.optLong("ts") < TTL_OK) {
            return e.optLong("chat");
        }
        return 0;
    }

    public static boolean isAppUser(int account, long uid, String about) {
        return aboutHasMarker(about) || knownBoard(account, uid) != 0 || uid == UserConfig.getInstance(account).getClientUserId() && isEnabled(account);
    }

    public static ArrayList<long[]> knownBoards(int account) {
        ArrayList<long[]> res = new ArrayList<>();
        JSONObject c = cache(account);
        Iterator<String> it = c.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject e = c.optJSONObject(k);
            if (e != null && e.optBoolean("ok") && System.currentTimeMillis() - e.optLong("ts") < TTL_OK) {
                res.add(new long[]{Long.parseLong(k), e.optLong("chat")});
            }
        }
        return res;
    }

    // ---- limite giornaliero e distanza minima tra ricerche
    private static long lastResolve;

    private static boolean takeBudget(int account) {
        SharedPreferences p = prefs();
        long day = System.currentTimeMillis() / 86400_000L;
        int used = p.getLong("day_" + account, -1) == day ? p.getInt("used_" + account, 0) : 0;
        if (used >= DAILY_LIMIT) {
            return false;
        }
        p.edit().putLong("day_" + account, day).putInt("used_" + account, used + 1).apply();
        return true;
    }

    public static int budgetLeft(int account) {
        SharedPreferences p = prefs();
        long day = System.currentTimeMillis() / 86400_000L;
        int used = p.getLong("day_" + account, -1) == day ? p.getInt("used_" + account, 0) : 0;
        return Math.max(0, DAILY_LIMIT - used);
    }

    private static boolean floodBackoff;

    /** Cerca la bacheca di un utente (rispetta cache, budget, distanza minima). cb(chatId o 0). */
    public static void discover(int account, long uid, Utilities.Callback<Long> cb) {
        long known = knownBoard(account, uid);
        if (known != 0) {
            if (cb != null) cb.run(known);
            return;
        }
        JSONObject e = cache(account).optJSONObject(String.valueOf(uid));
        if (e != null && !e.optBoolean("ok") && System.currentTimeMillis() - e.optLong("ts") < TTL_NO) {
            if (cb != null) cb.run(0L);
            return;
        }
        if (floodBackoff || !takeBudget(account)) {
            if (cb != null) cb.run(0L);
            return;
        }
        long wait = Math.max(0, lastResolve + MIN_GAP_MS - System.currentTimeMillis());
        lastResolve = System.currentTimeMillis() + wait;
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            mc.getUserNameResolver().resolve(username(uid), peer -> {
                if (peer == null || peer >= 0 || peer == Long.MAX_VALUE) {
                    remember(account, uid, 0, false);
                    if (cb != null) cb.run(0L);
                    return;
                }
                final long chatId = -peer;
                TLRPC.Chat chat = mc.getChat(chatId);
                if (chat == null || !ChatObject.isChannel(chat) || chat.megagroup) {
                    remember(account, uid, 0, false);
                    if (cb != null) cb.run(0L);
                    return;
                }
                verifyCreator(account, chatId, uid, ok -> {
                    remember(account, uid, ok ? chatId : 0, ok);
                    if (cb != null) cb.run(ok ? chatId : 0L);
                });
            });
        }, wait);
    }

    private static void remember(int account, long uid, long chat, boolean ok) {
        try {
            JSONObject c = cache(account);
            JSONObject e = new JSONObject();
            e.put("chat", chat);
            e.put("ok", ok);
            e.put("ts", System.currentTimeMillis());
            c.put(String.valueOf(uid), e);
            saveCache(account, c);
            if (ok) {
                PcBadge.add(uid);
            }
        } catch (Exception ignore) {
        }
    }

    /** Il creatore del canale deve essere l'utente: altrimenti qualcuno potrebbe occupare lo username. */
    private static void verifyCreator(int account, long chatId, long uid, Utilities.Callback<Boolean> cb) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_channels_getParticipants req = new TLRPC.TL_channels_getParticipants();
        req.channel = mc.getInputChannel(chatId);
        req.filter = new TLRPC.TL_channelParticipantsAdmins();
        req.offset = 0;
        req.limit = 20;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            boolean ok = false;
            if (response instanceof TLRPC.TL_channels_channelParticipants) {
                TLRPC.TL_channels_channelParticipants res = (TLRPC.TL_channels_channelParticipants) response;
                for (TLRPC.ChannelParticipant p : res.participants) {
                    if (p instanceof TLRPC.TL_channelParticipantCreator && p.user_id == uid) {
                        ok = true;
                    }
                }
            } else if (error != null && error.text != null && error.text.contains("FLOOD_WAIT")) {
                floodBackoff = true;
            }
            cb.run(ok);
        }));
    }


    private static boolean freshNegative(int account, long uid) {
        JSONObject e = cache(account).optJSONObject(String.valueOf(uid));
        return e != null && !e.optBoolean("ok") && System.currentTimeMillis() - e.optLong("ts") < TTL_NO;
    }

    /**
     * Cerca le bacheche tra i contatti. Prima chi ha già il marcatore nella bio (profilo scaricato), poi gli altri,
     * sempre nei limiti del budget giornaliero. done.run({trovate, controllati}).
     */
    public static void findFriends(int account, Utilities.Callback<int[]> done) {
        final long me = UserConfig.getInstance(account).getClientUserId();
        final MessagesController mc = MessagesController.getInstance(account);
        ArrayList<Long> first = new ArrayList<>(), rest = new ArrayList<>();
        for (TLRPC.TL_contact c : new ArrayList<>(org.telegram.messenger.ContactsController.getInstance(account).contacts)) {
            long uid = c.user_id;
            if (uid == me || knownBoard(account, uid) != 0 || freshNegative(account, uid)) {
                continue;
            }
            TLRPC.User u = mc.getUser(uid);
            if (u == null || u.bot || u.deleted) {
                continue;
            }
            TLRPC.UserFull f = mc.getUserFull(uid);
            if (f != null && aboutHasMarker(f.about)) {
                first.add(uid);
            } else {
                rest.add(uid);
            }
        }
        first.addAll(rest);
        final int max = Math.min(first.size(), Math.min(budgetLeft(account), 12));
        final ArrayList<Long> queue = new ArrayList<>(first.subList(0, max));
        final int[] res = new int[]{0, 0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (queue.isEmpty()) {
                done.run(res);
                return;
            }
            long uid = queue.remove(0);
            res[1]++;
            discover(account, uid, chat -> {
                if (chat != null && chat != 0) {
                    res[0]++;
                }
                step[0].run();
            });
        };
        step[0].run();
    }

    // ---- attivazione / disattivazione (solo da schermata esplicita)
    public interface Result {
        void done(boolean ok);
    }

    public static void enable(BaseFragment fragment, String title, Result result) {
        final int account = fragment.getCurrentAccount();
        final MessagesController mc = MessagesController.getInstance(account);
        final long uid = UserConfig.getInstance(account).getClientUserId();
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        // un tentativo precedente può aver creato il canale senza riuscire a dargli lo username: lo riprendo
        final long pending = prefs().getLong("pending_" + account, 0);
        if (pending != 0 && mc.getChat(pending) != null) {
            finishSetup(fragment, account, uid, pending, result);
            return;
        }
        final NotificationCenter.NotificationCenterDelegate[] d = new NotificationCenter.NotificationCenterDelegate[1];
        d[0] = (id, acc, args) -> {
            if (id == NotificationCenter.chatDidCreated) {
                nc.removeObserver(d[0], NotificationCenter.chatDidCreated);
                nc.removeObserver(d[0], NotificationCenter.chatDidFailCreate);
                final long chatId = (Long) args[0];
                prefs().edit().putLong("pending_" + account, chatId).apply();
                finishSetup(fragment, account, uid, chatId, result);
            } else if (id == NotificationCenter.chatDidFailCreate) {
                nc.removeObserver(d[0], NotificationCenter.chatDidCreated);
                nc.removeObserver(d[0], NotificationCenter.chatDidFailCreate);
                fail("enable", "creazione del canale rifiutata dal server (vedi avviso mostrato)");
                result.done(false);
            }
        };
        nc.addObserver(d[0], NotificationCenter.chatDidCreated);
        nc.addObserver(d[0], NotificationCenter.chatDidFailCreate);
        mc.createChat(title, new ArrayList<>(), ABOUT, ChatObject.CHAT_TYPE_CHANNEL, false, null, null, 0, fragment);
    }

    private static void finishSetup(BaseFragment fragment, int account, long uid, long chatId, Result result) {
        MessagesController.getInstance(account).updateChannelUserName(fragment, chatId, username(uid), () -> {
            prefs().edit().putLong("mine_" + account, chatId).remove("pending_" + account).apply();
            enableReactions(account, chatId);
            setMarker(account, true, result);
        }, () -> {
            fail("finishSetup", "username @" + username(uid) + " non assegnato al canale " + chatId);
            result.done(false);
        }); // username occupato, troppi canali pubblici, limite di richieste: l'utente può riprovare
    }

    private static void enableReactions(int account, long chatId) {
        try {
            TLRPC.TL_messages_setChatAvailableReactions req = new TLRPC.TL_messages_setChatAvailableReactions();
            req.peer = MessagesController.getInstance(account).getInputPeer(-chatId);
            req.available_reactions = new TLRPC.TL_chatReactionsAll();
            ConnectionsManager.getInstance(account).sendRequest(req, (r, e) -> {
            });
        } catch (Throwable ignore) {
        }
    }

    public static void disable(BaseFragment fragment, Result result) {
        final int account = fragment.getCurrentAccount();
        final long chatId = myBoardChat(account);
        prefs().edit().remove("mine_" + account).apply();
        if (chatId != 0) {
            // il canale resta (i post non si perdono) ma non è più raggiungibile dallo username
            MessagesController.getInstance(account).updateChannelUserName(fragment, chatId, "", null, null);
        }
        setMarker(account, false, result);
    }

    // ---- v12: la Bacheca e' invisibile e automatica

    /** True se il dialogo e' il canale Bacheca (mio o noto): non va mostrato nella lista chat, nei "condividi" e nelle ricerche. */
    public static boolean isBoardDialog(int account, long dialogId) {
        if (dialogId >= 0) {
            return false;
        }
        long chat = -dialogId;
        SharedPreferences p = prefs();
        if (chat == p.getLong("mine_" + account, 0) || chat == p.getLong("pending_" + account, 0)) {
            return true;
        }
        try {
            TLRPC.Chat c = MessagesController.getInstance(account).getChat(chat);
            return c != null && ChatObject.isChannelAndNotMegaGroup(c) && c.username != null && c.username.startsWith("plcb_");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Garantisce il canale di pubblicazione (lo crea da solo, in silenzio, alla prima pubblicazione) e poi esegue ok.
     * L'utente non sa che esiste: nessuna conferma, nessun pulsante "Attiva".
     */
    public static void ensure(final BaseFragment fragment, final Runnable ok, final Result failed) {
        final int account = fragment.getCurrentAccount();
        if (isEnabled(account)) {
            ok.run();
            return;
        }
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        String name = me != null ? org.telegram.messenger.UserObject.getUserName(me) : "";
        enable(fragment, (name + " - Porcodio").trim(), done -> {
            if (done) {
                ok.run();
            } else {
                failed.done(false);
            }
        });
    }

    /** Segna una volta sola (in silenzio) il profilo come "usa PORCODIO LUXURY Chat", cosi' gli altri utenti dell'app lo riconoscono. */
    public static void ensureMarker(final int account) {
        final SharedPreferences p = prefs();
        if (p.getBoolean("marker_done_" + account, false)) {
            return;
        }
        final long uid = UserConfig.getInstance(account).getClientUserId();
        if (uid == 0) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.UserFull full = mc.getUserFull(uid);
        if (full == null) {
            mc.loadFullUser(mc.getUser(uid), 0, true); // riprova al prossimo avvio quando e' nota la bio vera
            return;
        }
        if (aboutHasMarker(full.about)) {
            p.edit().putBoolean("marker_done_" + account, true).apply();
            PcBadge.add(uid);
            return;
        }
        setMarker(account, true, ok -> {
            if (ok) {
                p.edit().putBoolean("marker_done_" + account, true).apply();
                PcBadge.add(uid);
            }
        });
    }

    /** Vero al massimo una volta ogni 12 ore: la ricerca automatica di chi usa l'app tra i contatti (dentro il budget giornaliero). */
    public static boolean autoFindDue() {
        SharedPreferences p = prefs();
        long last = p.getLong("auto_find", 0);
        if (System.currentTimeMillis() - last < 12L * 3600_000L) {
            return false;
        }
        p.edit().putLong("auto_find", System.currentTimeMillis()).apply();
        return true;
    }

    /** Aggiunge o toglie il marcatore dalla bio (account.updateProfile). */
    public static void setMarker(int account, boolean on, Result result) {
        final long uid = UserConfig.getInstance(account).getClientUserId();
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.UserFull full = mc.getUserFull(uid);
        if (full == null) {
            // senza la bio attuale non si scrive nulla: si rischierebbe di cancellarla
            fail("setMarker", "bio non ancora caricata");
            result.done(false);
            return;
        }
        String about = full != null && full.about != null ? full.about : "";
        about = about.replace(" " + MARKER, "").replace(MARKER, "").trim();
        if (on) {
            about = (about + " " + MARKER).trim();
        }
        final String newAbout = about;
        final TL_account.updateProfile req = new TL_account.updateProfile();
        req.about = newAbout;
        req.flags |= 4;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error == null && response instanceof TLRPC.User) {
                if (full != null) {
                    full.about = newAbout;
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad, uid, full);
                }
                result.done(true);
            } else {
                fail("setMarker", rpcText(error));
                result.done(false);
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    // ---- feed
    public interface FeedCallback {
        void onPosts(ArrayList<Post> posts);
    }

    public static void loadFeed(int account, FeedCallback cb) {
        loadFeed(account, 8, false, cb);
    }

    /** limit = messaggi per Bacheca; onlyMine = solo la mia Bacheca (profilo). */
    public static void loadFeed(int account, final int limit, boolean onlyMine, FeedCallback cb) {
        final MessagesController mc = MessagesController.getInstance(account);
        final long me = UserConfig.getInstance(account).getClientUserId();
        final ArrayList<long[]> sources = onlyMine ? new ArrayList<>() : knownBoards(account);
        long mine = myBoardChat(account);
        if (mine != 0) {
            sources.add(new long[]{me, mine});
        }
        final ArrayList<Post> all = new ArrayList<>();
        if (sources.isEmpty()) {
            cb.onPosts(all);
            return;
        }
        final int[] left = {sources.size()};
        for (long[] s : sources) {
            final long author = s[0], chatId = s[1];
            // il canale deve essere noto al client: lo risolvo dallo username (cache locale di 1 ora)
            Runnable fetch = () -> {
                TLRPC.InputPeer peer = mc.getInputPeer(-chatId);
                if (peer == null) {
                    finishOne(left, all, cb);
                    return;
                }
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = peer;
                req.limit = limit;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (error != null) {
                        fail("loadFeed", rpcText(error));
                    }
                    if (response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        mc.putUsers(res.users, false);
                        mc.putChats(res.chats, false);
                        for (TLRPC.Message m : res.messages) {
                            if (m instanceof TLRPC.TL_messageEmpty || m.action != null) {
                                continue;
                            }
                            Post p = new Post();
                            p.authorId = author;
                            p.chatId = chatId;
                            p.msgId = m.id;
                            p.date = m.date;
                            p.views = m.views;
                            p.shares = m.forwards;
                            p.comments = m.replies != null ? m.replies.replies : 0;
                            p.text = m.message == null ? "" : m.message;
                            p.message = m;
                            if (m.reactions != null && m.reactions.results != null) {
                                for (TLRPC.ReactionCount rc : m.reactions.results) {
                                    p.likes += rc.count;
                                }
                            }
                            all.add(p);
                        }
                    }
                    finishOne(left, all, cb);
                }));
            };
            if (mc.getChat(chatId) != null) {
                fetch.run();
            } else {
                mc.getUserNameResolver().resolve(username(author), peer -> fetch.run());
            }
        }
    }

    private static void finishOne(int[] left, ArrayList<Post> all, FeedCallback cb) {
        if (--left[0] == 0) {
            Collections.sort(all, (a, b) -> b.date - a.date);
            cb.onPosts(all);
        }
    }

    /** Mi piace = reazione cuore sul post del canale. Se Telegram rifiuta (canale non unito), ok=false. */
    public static void like(int account, Post post, Result result) {
        TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        req.peer = MessagesController.getInstance(account).getInputPeer(-post.chatId);
        if (req.peer == null) {
            result.done(false);
            return;
        }
        req.msg_id = post.msgId;
        TLRPC.TL_reactionEmoji r = new TLRPC.TL_reactionEmoji();
        r.emoticon = "\u2764";
        req.reaction.add(r);
        req.flags |= 1;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                result.done(true);
            } else {
                result.done(false);
            }
        }));
    }

    /** Salva il post nei "Messaggi salvati" (inoltro a se stessi). Esito vero solo se il server lo accetta. */
    public static void saveToSaved(int account, Post post, Result result) {
        final long me = UserConfig.getInstance(account).getClientUserId();
        TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
        req.from_peer = MessagesController.getInstance(account).getInputPeer(-post.chatId);
        req.to_peer = MessagesController.getInstance(account).getInputPeer(me);
        if (req.from_peer == null || req.to_peer == null) {
            result.done(false);
            return;
        }
        req.id.add(post.msgId);
        req.random_id.add(Utilities.random.nextLong());
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                result.done(true);
            } else {
                result.done(false);
            }
        }));
    }

    /** Vero se tra i commenti del post c'e' un mio commento piu' recente di sinceDate (secondi). Usato per il segnale "commento". */
    public static void hasMyCommentSince(int account, Post post, int sinceDate, Result result) {
        final long me = UserConfig.getInstance(account).getClientUserId();
        TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = MessagesController.getInstance(account).getInputPeer(-post.chatId);
        if (req.peer == null) {
            result.done(false);
            return;
        }
        req.msg_id = post.msgId;
        req.limit = 30;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            boolean mine = false;
            if (response instanceof TLRPC.messages_Messages) {
                for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) {
                    if (m != null && m.date >= sinceDate && m.from_id != null && m.from_id.user_id == me) {
                        mine = true;
                        break;
                    }
                }
            }
            result.done(mine);
        }));
    }

    public static void publish(int account, String text) {
        publish(account, text, ok -> {
        });
    }

    public static void publish(final int account, final String text, final Result result) {
        final long chat = myBoardChat(account);
        if (chat == 0 || text == null || text.trim().isEmpty()) {
            fail("publish", chat == 0 ? "la Bacheca non e' attiva" : "testo vuoto");
            result.done(false);
            return;
        }
        withBoard(account, () -> {
            org.telegram.messenger.SendMessagesHelper.prepareSendingText(AccountInstance.getInstance(account), text.trim(), -chat, true, 0, 0, 0);
            result.done(true);
        }, result);
    }
}
