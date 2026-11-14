package org.telegram.porcodio;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/** Porcodio Luxury Chat: azioni antispam lato client (sempre dietro conferma). */
public class PcAntispam {

    public static void showMenu(BaseFragment fragment, int account, Runnable onChanged) {
        final Context ctx = fragment.getParentActivity();
        if (ctx == null) {
            return;
        }
        final CharSequence[] items = new CharSequence[]{
            LocaleController.getString(R.string.PcBlockReportRequests),
            LocaleController.getString(R.string.PcBlockReportSpam),
            LocaleController.getString(R.string.PcAcceptRequests),
            LocaleController.getString(R.string.PcMuteAllGroups),
            LocaleController.getString(R.string.PcQuickCleanup),
            LocaleController.getString(R.string.PcBulkTitle),
            LocaleController.getString(R.string.PcReqTitle)
        };
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(LocaleController.getString(R.string.PcAntispamMenu));
        b.setItems(items, (d, which) -> {
            switch (which) {
                case 0: blockAndReport(fragment, account, PcHome.TAB_REQUESTS, onChanged); break;
                case 1: blockAndReport(fragment, account, PcHome.TAB_SPAM, onChanged); break;
                case 2: acceptAll(fragment, account, onChanged); break;
                case 3: muteAllGroups(fragment, account, onChanged); break;
                case 4: fragment.presentFragment(new PcCleanupActivity()); break;
                case 5: fragment.presentFragment(new PcBulkActivity()); break;
                case 6: fragment.presentFragment(new PcRequestsActivity()); break;
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
    }

    private static void confirm(BaseFragment fragment, int titleRes, String message, int actionRes, Runnable action) {
        final Context ctx = fragment.getParentActivity();
        if (ctx == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(LocaleController.getString(titleRes));
        b.setMessage(message);
        b.setPositiveButton(LocaleController.getString(actionRes), (d, w) -> action.run());
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
    }

    private static void nothing(BaseFragment fragment) {
        final Context ctx = fragment.getParentActivity();
        if (ctx == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setMessage(LocaleController.getString(R.string.PcNothingToDo));
        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(b.create());
    }

    public static void blockAndReport(BaseFragment fragment, int account, int tab, Runnable onChanged) {
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Dialog> list = PcHome.dialogsOf(mc, tab);
        if (list.isEmpty()) {
            nothing(fragment);
            return;
        }
        confirm(fragment, R.string.PcConfirmBlockTitle, LocaleController.formatString(R.string.PcConfirmBlockText, list.size()), R.string.PcDoBlock, () -> {
            for (TLRPC.Dialog d : list) {
                TLRPC.User user = mc.getUser(d.id);
                if (user != null) {
                    mc.reportSpam(d.id, user, null, null, false);
                    mc.blockPeer(d.id);
                }
            }
            mc.sortDialogs(null);
            PcHome.postReload(account);
            if (onChanged != null) onChanged.run();
        });
    }

    public static void acceptAll(BaseFragment fragment, int account, Runnable onChanged) {
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Dialog> list = PcHome.dialogsOf(mc, PcHome.TAB_REQUESTS);
        list.addAll(PcHome.dialogsOf(mc, PcHome.TAB_SPAM));
        if (list.isEmpty()) {
            nothing(fragment);
            return;
        }
        confirm(fragment, R.string.PcConfirmAcceptTitle, LocaleController.formatString(R.string.PcConfirmAcceptText, list.size()), R.string.PcDoAccept, () -> {
            for (TLRPC.Dialog d : list) {
                PcHome.accept(d.id);
            }
            mc.sortDialogs(null);
            PcHome.postReload(account);
            if (onChanged != null) onChanged.run();
        });
    }

    public static void muteAllGroups(BaseFragment fragment, int account, Runnable onChanged) {
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Dialog> list = new ArrayList<>();
        for (TLRPC.Dialog d : PcHome.dialogsOf(mc, PcHome.TAB_GROUPS)) {
            if (!mc.isDialogMuted(d.id, 0)) {
                list.add(d);
            }
        }
        if (list.isEmpty()) {
            nothing(fragment);
            return;
        }
        confirm(fragment, R.string.PcConfirmMuteTitle, LocaleController.formatString(R.string.PcConfirmMuteText, list.size()), R.string.PcDoMute, () -> {
            for (TLRPC.Dialog d : list) {
                NotificationsController.getInstance(account).muteDialog(d.id, 0, true);
            }
            if (onChanged != null) onChanged.run();
        });
    }
}
