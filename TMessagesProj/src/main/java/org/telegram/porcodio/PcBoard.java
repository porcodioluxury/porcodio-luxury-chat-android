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

    public static class Post {
        public long authorId, chatId;
        public int msgId, date, views, likes;
        public String text;
        public TLRPC.Message message;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String username(long uid) {
        return "plcb_" + uid;
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

    // ---- attivazione / disattivazione (solo da schermata esplicita)
    public interface Result {
        void done(boolean ok);
    }

    public static void enable(BaseFragment fragment, String title, Result result) {
        final int account = fragment.getCurrentAccount();
        final MessagesController mc = MessagesController.getInstance(account);
        final long uid = UserConfig.getInstance(account).getClientUserId();
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] d = new NotificationCenter.NotificationCenterDelegate[1];
        d[0] = (id, acc, args) -> {
            if (id == NotificationCenter.chatDidCreated) {
                nc.removeObserver(d[0], NotificationCenter.chatDidCreated);
                nc.removeObserver(d[0], NotificationCenter.chatDidFailCreate);
                final long chatId = (Long) args[0];
                mc.updateChannelUserName(fragment, chatId, username(uid), () -> {
                    prefs().edit().putLong("mine_" + account, chatId).apply();
                    enableReactions(account, chatId);
                    setMarker(account, true, result);
                }, () -> result.done(false));
            } else if (id == NotificationCenter.chatDidFailCreate) {
                nc.removeObserver(d[0], NotificationCenter.chatDidCreated);
                nc.removeObserver(d[0], NotificationCenter.chatDidFailCreate);
                result.done(false);
            }
        };
        nc.addObserver(d[0], NotificationCenter.chatDidCreated);
        nc.addObserver(d[0], NotificationCenter.chatDidFailCreate);
        mc.createChat(title, new ArrayList<>(), ABOUT, ChatObject.CHAT_TYPE_CHANNEL, false, null, null, 0, fragment);
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

    /** Aggiunge o toglie il marcatore dalla bio (account.updateProfile). */
    public static void setMarker(int account, boolean on, Result result) {
        final long uid = UserConfig.getInstance(account).getClientUserId();
        final MessagesController mc = MessagesController.getInstance(account);
        final TLRPC.UserFull full = mc.getUserFull(uid);
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
                result.done(false);
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    // ---- feed
    public interface FeedCallback {
        void onPosts(ArrayList<Post> posts);
    }

    public static void loadFeed(int account, FeedCallback cb) {
        final MessagesController mc = MessagesController.getInstance(account);
        final long me = UserConfig.getInstance(account).getClientUserId();
        final ArrayList<long[]> sources = knownBoards(account);
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
                req.limit = 8;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
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

    public static void publish(int account, String text) {
        long chat = myBoardChat(account);
        if (chat == 0 || text == null || text.trim().isEmpty()) {
            return;
        }
        org.telegram.messenger.SendMessagesHelper.prepareSendingText(AccountInstance.getInstance(account), text.trim(), -chat, true, 0, 0, 0);
    }
}
