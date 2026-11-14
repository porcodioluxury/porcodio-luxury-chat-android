package org.telegram.porcodio;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Sicurezza dei messaggi in arrivo (v8). Tutto locale tranne le due privacy del server (inviti ai gruppi e chiamate).
 *  - Blocca gruppi sconosciuti: privacy server "chi può aggiungermi ai gruppi" (Solo contatti / Nessuno) + lato client i gruppi nuovi,
 *    che non ho creato e in cui non ho scritto, finiscono in Richieste/Spam, con uscita automatica opzionale.
 *  - Richieste di contatto: Tutti / Contatti dei contatti / Nessuno. Il primo messaggio di un non contatto va in Richieste;
 *    con "Contatti dei contatti" restano in Richieste solo quelli con almeno un gruppo in comune, gli altri vengono archiviati e silenziati;
 *    con "Nessuno" vengono archiviati e silenziati tutti.
 *  - Chi può scrivermi: Tutti / Solo contatti / Solo verificati / Nessuno (solo chat già esistenti). E' un filtro del client: il messaggio
 *    arriva comunque al telefono, qui viene nascosto (e senza notifica), non bloccato dal server. Per bloccarlo davvero serve "Blocca" sul profilo.
 */
public class PcPolicy {
    public static final int REQ_ALL = 0, REQ_CONTACTS_OF_CONTACTS = 1, REQ_NOBODY = 2;
    public static final int WRITE_ALL = 0, WRITE_CONTACTS = 1, WRITE_VERIFIED = 2, WRITE_NOBODY = 3;
    public static final int INVITE_CONTACTS = 0, INVITE_NOBODY = 1;

    private static final String PREFS = "pc_policy";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- impostazioni
    public static boolean blockGroups() {
        return prefs().getBoolean("blockGroups", false);
    }

    public static int groupInvite() {
        return prefs().getInt("groupInvite", INVITE_CONTACTS);
    }

    public static boolean autoLeave() {
        return prefs().getBoolean("autoLeave", false);
    }

    public static int reqMode() {
        return prefs().getInt("reqMode", REQ_ALL);
    }

    public static int writeMode() {
        return prefs().getInt("writeMode", WRITE_ALL);
    }

    public static void setAutoLeave(boolean on) {
        prefs().edit().putBoolean("autoLeave", on).apply();
    }

    public static void setGroupInvite(int account, int v) {
        prefs().edit().putInt("groupInvite", v).apply();
        if (blockGroups()) {
            applyServerInvite(account);
        }
    }

    public static void setBlockGroups(int account, boolean on) {
        SharedPreferences.Editor e = prefs().edit();
        e.putBoolean("blockGroups", on);
        if (on) {
            snapshotGroups(account, e);
        }
        e.apply();
        if (on) {
            applyServerInvite(account);
        }
        PcHome.postReload(account);
    }

    public static void setReqMode(int account, int v) {
        prefs().edit().putInt("reqMode", v).apply();
        PcHome.postReload(account);
    }

    public static void setWriteMode(int account, int v) {
        SharedPreferences.Editor e = prefs().edit();
        e.putInt("writeMode", v);
        if (v != WRITE_ALL) {
            snapshotPrivate(account, e);
        }
        e.apply();
        if (v != WRITE_ALL) {
            applyServerCalls(account, v);
        }
        PcHome.postReload(account);
    }

    // ---- istantanee: le chat che esistono quando si attiva la regola restano come sono
    private static void snapshotGroups(int account, SharedPreferences.Editor e) {
        Set<String> s = new HashSet<>(prefs().getStringSet("bg:" + account, new HashSet<>()));
        for (TLRPC.Dialog d : new ArrayList<>(MessagesController.getInstance(account).getAllDialogs())) {
            if (d.id < 0) {
                s.add(String.valueOf(d.id));
            }
        }
        e.putStringSet("bg:" + account, s);
    }

    private static void snapshotPrivate(int account, SharedPreferences.Editor e) {
        Set<String> s = new HashSet<>(prefs().getStringSet("bp:" + account, new HashSet<>()));
        for (TLRPC.Dialog d : new ArrayList<>(MessagesController.getInstance(account).getAllDialogs())) {
            if (d.id > 0) {
                s.add(String.valueOf(d.id));
            }
        }
        e.putStringSet("bp:" + account, s);
    }

    private static boolean inSet(String key, long id) {
        return prefs().getStringSet(key, new HashSet<>()).contains(String.valueOf(id));
    }

    private static synchronized void addTo(String key, long id) {
        Set<String> s = new HashSet<>(prefs().getStringSet(key, new HashSet<>()));
        s.add(String.valueOf(id));
        prefs().edit().putStringSet(key, s).apply();
    }

    // ---- privacy del server
    private static void applyServerInvite(int account) {
        sendPrivacy(account, new TLRPC.TL_inputPrivacyKeyChatInvite(), groupInvite() == INVITE_NOBODY);
    }

    private static void applyServerCalls(int account, int mode) {
        sendPrivacy(account, new TLRPC.TL_inputPrivacyKeyPhoneCall(), mode == WRITE_NOBODY);
    }

    private static void sendPrivacy(int account, TLRPC.InputPrivacyKey key, boolean nobody) {
        TL_account.setPrivacy req = new TL_account.setPrivacy();
        req.key = key;
        if (nobody) {
            req.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
        } else {
            req.rules.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
        });
    }

    // ---- classificazione
    private static boolean lastIsMine(MessagesController mc, long id) {
        ArrayList<MessageObject> msgs = mc.dialogMessage.get(id);
        MessageObject last = msgs != null && !msgs.isEmpty() ? msgs.get(0) : null;
        return last != null && last.isOutOwner();
    }

    /** Gruppo (non canale) comparso dopo l'attivazione, che non ho creato e in cui non ho scritto. */
    public static boolean unknownGroup(MessagesController mc, TLRPC.Dialog d) {
        if (!blockGroups() || d.id >= 0 || PcHome.isAccepted(d.id)) {
            return false;
        }
        int account = mc.pcAccount();
        if (inSet("bg:" + account, d.id)) {
            return false;
        }
        TLRPC.Chat c = mc.getChat(-d.id);
        if (c == null || c.creator || (ChatObject.isChannel(c) && !c.megagroup) || ChatObject.isNotInChat(c)) {
            return false;
        }
        return !lastIsMine(mc, d.id);
    }

    private static boolean verified(int account, TLRPC.User u) {
        return u.verified || PcRanking.isOfficial(u.id) || PcRanking.verifiedSource.isVerified(u.id);
    }

    /** "Chi può scrivermi": vero se il filtro locale deve nascondere questa chat privata (mai bloccata: solo nascosta). */
    public static boolean hiddenWriter(MessagesController mc, TLRPC.Dialog d) {
        int mode = writeMode();
        if (mode == WRITE_ALL || d.id <= 0 || d instanceof TLRPC.TL_dialogFolder || PcHome.isAccepted(d.id)) {
            return false;
        }
        int account = mc.pcAccount();
        if (inSet("bp:" + account, d.id) || lastIsMine(mc, d.id)) {
            return false;
        }
        TLRPC.User u = mc.getUser(d.id);
        if (u == null || u.self || u.bot || u.id == 777000 || u.support) {
            return false;
        }
        boolean contact = u.contact || u.mutual_contact || ContactsController.getInstance(account).isContact(u.id);
        switch (mode) {
            case WRITE_CONTACTS: return !contact;
            case WRITE_VERIFIED: return !(contact || verified(account, u));
            default: return true;
        }
    }

    /** Nessuna notifica per ciò che il filtro nasconde. */
    public static boolean suppressNotification(int account, MessageObject mo) {
        try {
            if (mo == null || mo.isOutOwner() || mo.messageOwner == null) {
                return false;
            }
            long did = mo.getDialogId();
            if (did <= 0) {
                return false;
            }
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.Dialog d = mc.dialogs_dict.get(did);
            if (d != null) {
                return hiddenWriter(mc, d);
            }
            TLRPC.Dialog fake = new TLRPC.TL_dialog();
            fake.id = did;
            return hiddenWriter(mc, fake);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- gestione automatica (richieste di contatto e uscita dai gruppi sconosciuti)
    private static final Set<Long> pending = new HashSet<>();
    private static boolean scheduled;

    /** Chiamato dalla lista chat per ogni richiesta o gruppo sconosciuto: l'azione vera parte dopo, fuori dal ciclo di ordinamento. */
    public static void consider(final MessagesController mc, TLRPC.Dialog d) {
        final int account = mc.pcAccount();
        if (d.id > 0 && reqMode() == REQ_ALL || d.id < 0 && !autoLeave()) {
            return;
        }
        if (inSet("done:" + account, d.id)) {
            return;
        }
        synchronized (pending) {
            pending.add(d.id);
            if (scheduled) {
                return;
            }
            scheduled = true;
        }
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<Long> todo;
            synchronized (pending) {
                todo = new ArrayList<>(pending);
                pending.clear();
                scheduled = false;
            }
            for (long id : todo) {
                handle(account, id);
            }
        }, 1500);
    }

    private static void handle(final int account, final long id) {
        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Dialog d = mc.dialogs_dict.get(id);
        if (d == null || d.folder_id != 0 || PcHome.isAccepted(id)) {
            return;
        }
        if (id < 0) {
            if (autoLeave() && unknownGroup(mc, d)) {
                TLRPC.Chat c = mc.getChat(-id);
                if (c != null && !c.creator) {
                    addTo("done:" + account, id);
                    TLRPC.User me = mc.getUser(UserConfig.getInstance(account).getClientUserId());
                    mc.deleteParticipantFromChat(-id, me, null, false, false);
                    mc.deleteDialog(id, 0, false);
                }
            }
            return;
        }
        final TLRPC.User u = mc.getUser(id);
        if (u == null) {
            return;
        }
        final int mode = reqMode();
        if (mode == REQ_NOBODY) {
            quiet(account, id);
        } else if (mode == REQ_CONTACTS_OF_CONTACTS) {
            mc.loadFullUser(u, 0, false, full -> {
                if (full == null) {
                    return;
                }
                if (full.common_chats_count > 0) {
                    addTo("done:" + account, id); // ha un gruppo in comune: resta tra le Richieste
                } else {
                    quiet(account, id);
                }
            });
        }
    }

    private static void quiet(int account, long id) {
        addTo("done:" + account, id);
        addTo("quiet:" + account, id);
        MessagesController.getInstance(account).addDialogToFolder(id, 1, -1, 0);
        NotificationsController.getInstance(account).muteDialog(id, 0, true);
    }

    // ---- azioni sulle richieste
    /** Accetta: aggiunge ai contatti se e' una persona, toglie dall'archivio e dal silenzio se l'avevamo messa noi. */
    public static void accept(int account, long id, boolean addContact) {
        MessagesController mc = MessagesController.getInstance(account);
        PcHome.accept(id);
        if (id > 0 && addContact) {
            TLRPC.User u = mc.getUser(id);
            if (u != null && !u.contact) {
                ContactsController.getInstance(account).addContact(u, false);
            }
        }
        if (inSet("quiet:" + account, id)) {
            TLRPC.Dialog d = mc.dialogs_dict.get(id);
            if (d != null && d.folder_id == 1) {
                mc.addDialogToFolder(id, 0, -1, 0);
            }
            NotificationsController.getInstance(account).muteDialog(id, 0, false);
        }
        mc.sortDialogs(null);
        PcHome.postReload(account);
    }

    /** Rifiuta: elimina la chat (persona) o esce dal gruppo; con block anche blocca la persona. */
    public static void reject(int account, long id, boolean block) {
        MessagesController mc = MessagesController.getInstance(account);
        if (id > 0) {
            if (block) {
                mc.blockPeer(id);
            }
            mc.deleteDialog(id, 0, false);
        } else {
            TLRPC.Chat c = mc.getChat(-id);
            if (c != null && !c.creator && !ChatObject.isNotInChat(c)) {
                TLRPC.User me = mc.getUser(UserConfig.getInstance(account).getClientUserId());
                mc.deleteParticipantFromChat(-id, me, null, false, false);
            }
            mc.deleteDialog(id, 0, false);
        }
        mc.sortDialogs(null);
        PcHome.postReload(account);
    }

    /** Chat nascoste dal filtro "Chi può scrivermi" (per poterle rivedere e accettare). */
    public static ArrayList<TLRPC.Dialog> hiddenDialogs(MessagesController mc) {
        ArrayList<TLRPC.Dialog> res = new ArrayList<>();
        for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
            if (d.folder_id == 0 && hiddenWriter(mc, d)) {
                res.add(d);
            }
        }
        return res;
    }

    /** Chat archiviate e silenziate in automatico da questa regola. */
    public static ArrayList<TLRPC.Dialog> quietDialogs(MessagesController mc) {
        ArrayList<TLRPC.Dialog> res = new ArrayList<>();
        Set<String> q = prefs().getStringSet("quiet:" + mc.pcAccount(), new HashSet<>());
        for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
            if (d.folder_id == 1 && q.contains(String.valueOf(d.id))) {
                res.add(d);
            }
        }
        return res;
    }
}
