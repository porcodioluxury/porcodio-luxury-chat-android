package org.telegram.porcodio;

import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * Azioni in blocco sulle chat, a lotti e in background. Un solo lavoro alla volta.
 * - lotti di BATCH chat, poi pausa breve tra un lotto e l'altro (per non scatenare i limiti del server);
 * - se qualunque richiesta riceve FLOOD_WAIT_n (segnalato da ConnectionsManager con noteError) il lavoro si ferma n secondi e riprende da solo;
 * - si puo' annullare in ogni momento: le chat gia' fatte restano fatte, le altre non vengono toccate;
 * - alla fine c'e' un riepilogo (eseguite, saltate perche' non applicabili, quante pause per i limiti).
 */
public class PcBulk {
    public static final int DELETE = 0, LEAVE = 1, ARCHIVE = 2, UNARCHIVE = 3, MARK_READ = 4;
    private static final int BATCH = 4;
    private static final long PAUSE_MS = 1500;

    /** Fino a quando (elapsedRealtime) il server ci ha chiesto di aspettare. */
    public static volatile long floodUntil;
    public static volatile int floodEvents;

    /** Chiamato da ConnectionsManager per ogni errore RPC. */
    public static void noteError(int code, String text) {
        try {
            if (text == null || code != 420) {
                return;
            }
            int i = text.lastIndexOf('_');
            int secs = i >= 0 ? Integer.parseInt(text.substring(i + 1)) : 30;
            long until = android.os.SystemClock.elapsedRealtime() + secs * 1000L + 500;
            if (until > floodUntil) {
                floodUntil = until;
                floodEvents++;
            }
        } catch (Throwable ignore) {
        }
    }

    public interface Listener {
        void onProgress(int done, int total, int waitSeconds);

        void onFinished(Summary s);
    }

    public static class Summary {
        public int action, total, done, skipped, floods, pausedSeconds;
        public boolean cancelled;
    }

    private static PcBulk current;

    public static synchronized PcBulk running() {
        return current != null && !current.finished ? current : null;
    }

    private final int account, action;
    private final ArrayList<Long> ids;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Summary summary = new Summary();
    private Listener listener;
    private int index;
    private boolean cancelled, finished;
    private long pausedSince;

    private PcBulk(int account, int action, List<Long> ids) {
        this.account = account;
        this.action = action;
        this.ids = new ArrayList<>(ids);
        summary.action = action;
        summary.total = ids.size();
    }

    public static synchronized PcBulk start(int account, int action, List<Long> ids, Listener l) {
        if (running() != null) {
            return null;
        }
        PcBulk b = new PcBulk(account, action, ids);
        b.listener = l;
        current = b;
        b.floodEventsAtStart = floodEvents;
        b.handler.post(b::tick);
        return b;
    }

    private int floodEventsAtStart;

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onProgress(summary.done + summary.skipped, summary.total, 0);
        }
    }

    public void cancel() {
        cancelled = true;
    }

    public int total() {
        return summary.total;
    }

    public int action() {
        return action;
    }

    private void finish() {
        finished = true;
        summary.cancelled = cancelled;
        summary.floods = floodEvents - floodEventsAtStart;
        if (listener != null) {
            listener.onFinished(summary);
        }
    }

    private void tick() {
        if (cancelled || index >= ids.size()) {
            finish();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < floodUntil) {
            if (pausedSince == 0) {
                pausedSince = now;
            }
            int wait = (int) Math.ceil((floodUntil - now) / 1000.0);
            if (listener != null) {
                listener.onProgress(summary.done + summary.skipped, summary.total, wait);
            }
            handler.postDelayed(this::tick, Math.min(1000, floodUntil - now));
            return;
        }
        if (pausedSince != 0) {
            summary.pausedSeconds += (int) ((now - pausedSince) / 1000);
            pausedSince = 0;
        }
        int n = 0;
        while (n < BATCH && index < ids.size() && !cancelled && android.os.SystemClock.elapsedRealtime() >= floodUntil) {
            long id = ids.get(index++);
            if (apply(id)) {
                summary.done++;
            } else {
                summary.skipped++;
            }
            n++;
        }
        if (listener != null) {
            listener.onProgress(summary.done + summary.skipped, summary.total, 0);
        }
        handler.postDelayed(this::tick, PAUSE_MS);
    }

    private boolean apply(long did) {
        try {
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.Dialog d = mc.dialogs_dict.get(did);
            if (d == null) {
                return false;
            }
            boolean isChat = did < 0 && !DialogObject.isEncryptedDialog(did);
            switch (action) {
                case DELETE:
                    if (isChat) {
                        return false;
                    }
                    mc.deleteDialog(did, 0, false);
                    return true;
                case LEAVE: {
                    if (!isChat) {
                        return false;
                    }
                    TLRPC.Chat chat = mc.getChat(-did);
                    if (chat == null || chat.creator) {
                        return false; // chi ha creato il gruppo o canale non esce: andrebbe eliminato, e qui non lo facciamo
                    }
                    if (!ChatObject.isNotInChat(chat)) {
                        TLRPC.User me = mc.getUser(UserConfig.getInstance(account).getClientUserId());
                        mc.deleteParticipantFromChat(-did, me, null, false, false);
                    }
                    mc.deleteDialog(did, 0, false);
                    return true;
                }
                case ARCHIVE:
                    if (d.folder_id == 1) {
                        return false;
                    }
                    mc.addDialogToFolder(did, 1, -1, 0);
                    return true;
                case UNARCHIVE:
                    if (d.folder_id != 1) {
                        return false;
                    }
                    mc.addDialogToFolder(did, 0, -1, 0);
                    return true;
                case MARK_READ:
                    if (mc.getDialogUnreadCount(d) == 0 && !d.unread_mark) {
                        return false;
                    }
                    mc.markDialogAsRead(did, d.top_message, d.top_message, d.last_message_date, false, 0, mc.getDialogUnreadCount(d), true, 0);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
