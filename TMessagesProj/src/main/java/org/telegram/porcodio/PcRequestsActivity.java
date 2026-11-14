package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Richieste: persone e gruppi sconosciuti (Richieste e Spam), chat nascoste dal filtro "Chi può scrivermi" e chat archiviate in
 * automatico dalle richieste di contatto. Per ognuna: Accetta (aggiunge ai contatti se è una persona), Rifiuta (elimina la chat o esce
 * dal gruppo), Rifiuta e blocca, Apri.
 */
public class PcRequestsActivity extends PcListFragment {
    private static final int T_HEADER = 0, T_ITEM = 1, T_HINT = 2;

    private static class Item {
        int type;
        String title, sub;
        long dialogId;
        TLObject peer;
    }

    private final ArrayList<Item> items = new ArrayList<>();

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcReqTitle);
    }

    @Override
    protected int itemCount() {
        return items.size();
    }

    @Override
    protected int itemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return position >= 0 && position < items.size() && items.get(position).type == T_ITEM;
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        build();
        return v;
    }

    private Item header(String t) {
        Item h = new Item();
        h.type = T_HEADER;
        h.title = t;
        return h;
    }

    private void add(ArrayList<Item> out, TLRPC.Dialog d, String why) {
        MessagesController mc = getMessagesController();
        Item it = new Item();
        it.type = T_ITEM;
        it.dialogId = d.id;
        if (d.id > 0) {
            TLRPC.User u = mc.getUser(d.id);
            if (u == null) {
                return;
            }
            it.peer = u;
            it.title = UserObject.getUserName(u);
        } else {
            TLRPC.Chat c = mc.getChat(-d.id);
            if (c == null) {
                return;
            }
            it.peer = c;
            it.title = c.title;
        }
        String last = "";
        java.util.ArrayList<org.telegram.messenger.MessageObject> m = mc.dialogMessage.get(d.id);
        if (m != null && !m.isEmpty() && m.get(0).messageText != null) {
            last = m.get(0).messageText.toString().replace('\n', ' ');
            if (last.length() > 60) {
                last = last.substring(0, 60) + "...";
            }
        }
        it.sub = why + (last.isEmpty() ? "" : " · " + last);
        out.add(it);
    }

    private void build() {
        items.clear();
        final MessagesController mc = getMessagesController();
        ArrayList<Item> req = new ArrayList<>(), hid = new ArrayList<>(), quiet = new ArrayList<>();
        for (TLRPC.Dialog d : PcHome.dialogsOf(mc, PcHome.TAB_REQUESTS)) {
            if (PcPolicy.hiddenWriter(mc, d)) {
                continue;
            }
            add(req, d, LocaleController.getString(d.id < 0 ? R.string.PcReqWhyGroup : R.string.PcReqWhyPerson));
        }
        for (TLRPC.Dialog d : PcHome.dialogsOf(mc, PcHome.TAB_SPAM)) {
            if (PcPolicy.hiddenWriter(mc, d)) {
                continue;
            }
            add(req, d, LocaleController.getString(R.string.PcReqWhySpam));
        }
        for (TLRPC.Dialog d : PcPolicy.hiddenDialogs(mc)) {
            add(hid, d, LocaleController.getString(R.string.PcReqWhyHidden));
        }
        for (TLRPC.Dialog d : PcPolicy.quietDialogs(mc)) {
            add(quiet, d, LocaleController.getString(R.string.PcReqWhyQuiet));
        }
        Item hint = new Item();
        hint.type = T_HINT;
        hint.title = LocaleController.getString(req.isEmpty() && hid.isEmpty() && quiet.isEmpty() ? R.string.PcReqEmpty : R.string.PcReqHint);
        items.add(hint);
        if (!req.isEmpty()) {
            items.add(header(LocaleController.getString(R.string.PcReqSecRequests)));
            items.addAll(req);
        }
        if (!hid.isEmpty()) {
            items.add(header(LocaleController.getString(R.string.PcReqSecHidden)));
            items.addAll(hid);
        }
        if (!quiet.isEmpty()) {
            items.add(header(LocaleController.getString(R.string.PcReqSecQuiet)));
            items.addAll(quiet);
        }
        notifyData();
    }

    @Override
    protected void onRowClick(View view, int position) {
        if (position < 0 || position >= items.size() || items.get(position).type != T_ITEM || getParentActivity() == null) {
            return;
        }
        final Item it = items.get(position);
        final boolean person = it.dialogId > 0;
        final ArrayList<CharSequence> labels = new ArrayList<>();
        labels.add(LocaleController.getString(R.string.PcReqAccept));
        labels.add(LocaleController.getString(person ? R.string.PcReqReject : R.string.PcReqRejectGroup));
        if (person) {
            labels.add(LocaleController.getString(R.string.PcReqRejectBlock));
        }
        labels.add(LocaleController.getString(R.string.PcReqOpen));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(it.title);
        b.setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
            String label = labels.get(which).toString();
            if (which == 0) {
                PcPolicy.accept(currentAccount, it.dialogId, true);
                build();
            } else if (label.equals(LocaleController.getString(R.string.PcReqOpen))) {
                android.os.Bundle a = new android.os.Bundle();
                if (person) {
                    a.putLong("user_id", it.dialogId);
                } else {
                    a.putLong("chat_id", -it.dialogId);
                }
                presentFragment(new ChatActivity(a));
            } else {
                final boolean block = person && which == 2;
                AlertDialog.Builder c = new AlertDialog.Builder(getParentActivity());
                c.setTitle(it.title);
                c.setMessage(LocaleController.getString(block ? R.string.PcReqConfirmBlock : R.string.PcReqConfirmReject));
                c.setPositiveButton(LocaleController.getString(R.string.PcBulkDoIt), (d2, w2) -> {
                    PcPolicy.reject(currentAccount, it.dialogId, block);
                    build();
                });
                c.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                showDialog(c.create());
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_ITEM) {
            return new Row(context);
        }
        TextView t = new TextView(context);
        t.setPadding(dp(16), dp(viewType == T_HEADER ? 16 : 12), dp(16), dp(8));
        t.setTextSize(viewType == T_HEADER ? 13 : 14);
        if (viewType == T_HEADER) {
            t.setTypeface(AndroidUtilities.bold());
            t.setAllCaps(true);
        }
        t.setTextColor(Theme.getColor(viewType == T_HEADER ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        t.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
        return t;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Item it = items.get(position);
        if (viewType == T_ITEM) {
            ((Row) row).bind(it);
        } else {
            ((TextView) row).setText(it.title);
        }
    }

    private class Row extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView title, sub;

        Row(Context context) {
            super(context);
            setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, dp(64)));
            setBackground(Theme.getSelectorDrawable(false));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(4));
            addView(avatar, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
            title = new TextView(context);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextSize(16);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 11, 56, 0));
            sub = new TextView(context);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setTextSize(13);
            sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(sub, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 35, 56, 0));
        }

        void bind(Item it) {
            AvatarDrawable ad = new AvatarDrawable();
            if (it.peer instanceof TLRPC.User) {
                ad.setInfo(currentAccount, (TLRPC.User) it.peer);
            } else if (it.peer instanceof TLRPC.Chat) {
                ad.setInfo(currentAccount, (TLRPC.Chat) it.peer);
            }
            avatar.setForUserOrChat(it.peer, ad);
            title.setText(it.title);
            sub.setText(it.sub);
        }

    }
}
