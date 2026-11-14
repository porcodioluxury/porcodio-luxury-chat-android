package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Eliminazione e gestione in blocco delle chat (Private, Gruppi, Canali, Bot, Archiviate).
 * Filtri rapidi (tutte, private, gruppi, canali, bot, archiviate, non lette, inattive da N giorni), selezione singola o "Seleziona tutto"
 * sul filtro corrente, azioni elimina / esci ed elimina / archivia / rimuovi dall'archivio / segna lette. Esecuzione a lotti con
 * barra di avanzamento, annulla, pausa automatica su FLOOD_WAIT, riepilogo finale e doppia conferma per le azioni che cancellano.
 */
public class PcBulkActivity extends PcListFragment {
    private static final int T_CHIPS = 0, T_ITEM = 1, T_HINT = 2;
    private static final int F_ALL = 0, F_PRIVATE = 1, F_GROUPS = 2, F_CHANNELS = 3, F_BOTS = 4, F_ARCHIVED = 5, F_UNREAD = 6, F_INACTIVE = 7;
    private static final int[] DAYS = {7, 30, 90, 180, 365};

    private static class Item {
        int type;
        String title, sub;
        long dialogId;
        TLObject peer;
        boolean group, channel, secret, bot, creator;
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private int filter = F_ALL;
    private int inactiveDays = 30;
    private TextView selectBtn, actionBtn;
    private AlertDialog progressDialog;
    private TextView progressText;
    private ProgressBar progressBar;

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcBulkTitle);
    }

    @Override
    protected int bottomReserve() {
        return 64;
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
    public boolean onFragmentCreate() {
        try {
            getMessagesController().loadDialogs(1, 0, 100, true); // carica anche l'archivio
        } catch (Throwable ignore) {
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PcBulk r = PcBulk.running();
        if (r != null) {
            r.setListener(null); // il lavoro continua in background, senza schermata
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        selectBtn = button(context);
        actionBtn = button(context);
        selectBtn.setOnClickListener(x -> toggleAll());
        actionBtn.setOnClickListener(x -> showActions());
        bar.addView(selectBtn, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 6, 0));
        bar.addView(actionBtn, LayoutHelper.createLinear(0, 48, 1f, 6, 0, 0, 0));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM));
        build();
        updateButtons();
        PcBulk r = PcBulk.running();
        if (r != null) {
            showProgress(r);
        }
        return v;
    }

    private TextView button(Context context) {
        TextView t = new TextView(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                float sw = Math.max(1f, AndroidUtilities.density);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setColor(Theme.getColor(isEnabled() ? Theme.key_chats_actionBackground : Theme.key_divider));
                RectF r = AndroidUtilities.rectTmp;
                r.set(sw / 2, sw / 2, getWidth() - sw / 2, getHeight() - sw / 2);
                canvas.drawRoundRect(r, dp(4), dp(4), p);
                super.onDraw(canvas);
            }
        };
        t.setGravity(Gravity.CENTER);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextSize(14);
        t.setWillNotDraw(false);
        return t;
    }

    // ---- elenco

    private boolean matches(Item it, TLRPC.Dialog d, int now) {
        switch (filter) {
            case F_PRIVATE: return !it.group && !it.channel && !it.bot && d.folder_id != 1;
            case F_GROUPS: return it.group && !it.channel;
            case F_CHANNELS: return it.channel;
            case F_BOTS: return it.bot;
            case F_ARCHIVED: return d.folder_id == 1;
            case F_UNREAD: return getMessagesController().getDialogUnreadCount(d) > 0 || d.unread_mark;
            case F_INACTIVE: return d.last_message_date > 0 ? (now - d.last_message_date) / 86400 >= inactiveDays : true;
            default: return true;
        }
    }

    private void build() {
        items.clear();
        final MessagesController mc = getMessagesController();
        final int now = getConnectionsManager().getCurrentTime();
        final long me = getUserConfig().getClientUserId();
        ArrayList<Item> list = new ArrayList<>();
        for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
            if (!(d instanceof TLRPC.TL_dialog) || d.id == me || d.id == 777000) {
                continue;
            }
            Item it = new Item();
            it.type = T_ITEM;
            it.dialogId = d.id;
            if (DialogObject.isEncryptedDialog(d.id)) {
                it.secret = true;
                TLRPC.EncryptedChat ec = mc.getEncryptedChat(DialogObject.getEncryptedChatId(d.id));
                TLRPC.User u = ec != null ? mc.getUser(ec.user_id) : null;
                if (u == null) {
                    continue;
                }
                it.peer = u;
                it.title = org.telegram.messenger.UserObject.getUserName(u);
            } else if (d.id > 0) {
                TLRPC.User u = mc.getUser(d.id);
                if (u == null) {
                    continue;
                }
                it.peer = u;
                it.bot = u.bot;
                it.title = org.telegram.messenger.UserObject.getUserName(u);
            } else {
                TLRPC.Chat c = mc.getChat(-d.id);
                if (c == null || ChatObject.isNotInChat(c)) {
                    continue;
                }
                it.peer = c;
                it.group = true;
                it.channel = ChatObject.isChannel(c) && !c.megagroup;
                it.creator = c.creator;
                it.title = c.title;
            }
            if (!matches(it, d, now)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(LocaleController.getString(it.secret ? R.string.PcBulkKindSecret : it.channel ? R.string.PcBulkKindChannel : it.group ? R.string.PcBulkKindGroup : it.bot ? R.string.PcBulkKindBot : R.string.PcBulkKindPrivate));
            int unread = mc.getDialogUnreadCount(d);
            if (unread > 0) {
                sb.append(" · ").append(LocaleController.formatString(R.string.PcBulkUnread, unread));
            }
            if (d.last_message_date > 0) {
                int days = Math.max(0, (now - d.last_message_date) / 86400);
                sb.append(" · ").append(LocaleController.formatString(R.string.PcBulkDaysAgo, days));
            }
            if (d.folder_id == 1) {
                sb.append(" · ").append(LocaleController.getString(R.string.PcBulkArchivedTag));
            }
            it.sub = sb.toString();
            list.add(it);
        }
        if (filter == F_INACTIVE) {
            final MessagesController m2 = mc;
            java.util.Collections.sort(list, (a, b) -> {
                TLRPC.Dialog x = m2.dialogs_dict.get(a.dialogId), y = m2.dialogs_dict.get(b.dialogId);
                return Integer.compare(x == null ? 0 : x.last_message_date, y == null ? 0 : y.last_message_date);
            });
        }
        Item chips = new Item();
        chips.type = T_CHIPS;
        items.add(chips);
        Item hint = new Item();
        hint.type = T_HINT;
        hint.title = list.isEmpty() ? LocaleController.getString(R.string.PcBulkEmpty) : LocaleController.formatString(R.string.PcBulkCount, list.size());
        items.add(hint);
        items.addAll(list);
        selected.retainAll(idsOf(list));
        notifyData();
    }

    private Set<Long> idsOf(ArrayList<Item> list) {
        Set<Long> s = new HashSet<>();
        for (Item i : list) {
            s.add(i.dialogId);
        }
        return s;
    }

    private ArrayList<Item> visibleItems() {
        ArrayList<Item> r = new ArrayList<>();
        for (Item i : items) {
            if (i.type == T_ITEM) {
                r.add(i);
            }
        }
        return r;
    }

    private void toggleAll() {
        ArrayList<Item> v = visibleItems();
        if (selected.size() >= v.size() && !v.isEmpty()) {
            selected.clear();
        } else {
            selected.clear();
            selected.addAll(idsOf(v));
        }
        notifyData();
        updateButtons();
    }

    private void updateButtons() {
        int n = selected.size();
        boolean all = !visibleItems().isEmpty() && n >= visibleItems().size();
        selectBtn.setText(LocaleController.getString(all ? R.string.PcBulkSelectNone : R.string.PcBulkSelectAll));
        actionBtn.setText(LocaleController.formatString(R.string.PcBulkActions, n));
        actionBtn.setEnabled(n > 0);
        int on = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), off = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        selectBtn.setTextColor(on);
        actionBtn.setTextColor(n > 0 ? on : off);
        selectBtn.invalidate();
        actionBtn.invalidate();
    }

    // ---- azioni

    private int applicable(int action) {
        int n = 0;
        MessagesController mc = getMessagesController();
        for (Item i : visibleItems()) {
            if (!selected.contains(i.dialogId)) {
                continue;
            }
            TLRPC.Dialog d = mc.dialogs_dict.get(i.dialogId);
            if (d == null) {
                continue;
            }
            switch (action) {
                case PcBulk.DELETE: if (!i.group) n++; break;
                case PcBulk.LEAVE: if (i.group && !i.creator) n++; break;
                case PcBulk.ARCHIVE: if (d.folder_id != 1) n++; break;
                case PcBulk.UNARCHIVE: if (d.folder_id == 1) n++; break;
                case PcBulk.MARK_READ: if (mc.getDialogUnreadCount(d) > 0 || d.unread_mark) n++; break;
            }
        }
        return n;
    }

    private void showActions() {
        if (selected.isEmpty() || getParentActivity() == null) {
            return;
        }
        if (PcBulk.running() != null) {
            showProgress(PcBulk.running());
            return;
        }
        final int[] acts = {PcBulk.DELETE, PcBulk.LEAVE, PcBulk.ARCHIVE, PcBulk.UNARCHIVE, PcBulk.MARK_READ};
        final int[] names = {R.string.PcBulkDelete, R.string.PcBulkLeave, R.string.PcBulkArchive, R.string.PcBulkUnarchive, R.string.PcBulkMarkRead};
        CharSequence[] labels = new CharSequence[acts.length];
        for (int i = 0; i < acts.length; i++) {
            labels[i] = LocaleController.getString(names[i]) + " (" + applicable(acts[i]) + ")";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.formatString(R.string.PcBulkActions, selected.size()));
        b.setItems(labels, (d, which) -> confirm(acts[which], names[which]));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void confirm(final int action, int nameRes) {
        final int n = applicable(action);
        if (n == 0) {
            PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBulkNoneApplicable));
            return;
        }
        final boolean destructive = action == PcBulk.DELETE || action == PcBulk.LEAVE;
        int skipped = selected.size() - n;
        String msg = LocaleController.formatString(R.string.PcBulkConfirm1, LocaleController.getString(nameRes), n)
            + (skipped > 0 ? "\n" + LocaleController.formatString(R.string.PcBulkSkipNote, skipped) : "")
            + (destructive ? "\n" + LocaleController.getString(R.string.PcBulkIrreversible) : "");
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(nameRes));
        b.setMessage(msg);
        b.setPositiveButton(LocaleController.getString(R.string.PcBulkContinue), (d, w) -> {
            if (!destructive) {
                run(action);
                return;
            }
            AlertDialog.Builder b2 = new AlertDialog.Builder(getParentActivity());
            b2.setTitle(LocaleController.getString(R.string.PcBulkSure));
            b2.setMessage(LocaleController.formatString(R.string.PcBulkConfirm2, n));
            b2.setPositiveButton(LocaleController.getString(R.string.PcBulkDoIt), (d2, w2) -> run(action));
            b2.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b2.create());
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void run(int action) {
        ArrayList<Long> ids = new ArrayList<>();
        for (Item i : visibleItems()) {
            if (selected.contains(i.dialogId)) {
                ids.add(i.dialogId);
            }
        }
        PcBulk r = PcBulk.start(currentAccount, action, ids, null);
        if (r == null) {
            return;
        }
        selected.clear();
        updateButtons();
        showProgress(r);
    }

    private void showProgress(final PcBulk r) {
        if (getParentActivity() == null) {
            return;
        }
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignore) {
            }
        }
        Context ctx = getParentActivity();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        progressText = new TextView(ctx);
        progressText.setTextSize(15);
        progressText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        box.addView(progressText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(Math.max(1, r.total()));
        box.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16, 0, 12, 0, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(LocaleController.getString(R.string.PcBulkRunning));
        b.setView(box);
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> r.cancel());
        progressDialog = b.create();
        progressDialog.setCanceledOnTouchOutside(false);
        showDialog(progressDialog);
        r.setListener(new PcBulk.Listener() {
            @Override
            public void onProgress(int done, int total, int waitSeconds) {
                if (progressText == null) {
                    return;
                }
                progressBar.setProgress(done);
                progressText.setText(waitSeconds > 0
                    ? LocaleController.formatString(R.string.PcBulkWait, done, total, waitSeconds)
                    : LocaleController.formatString(R.string.PcBulkProgress, done, total));
            }

            @Override
            public void onFinished(PcBulk.Summary s) {
                try {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                    }
                } catch (Throwable ignore) {
                }
                progressDialog = null;
                progressText = null;
                r.setListener(null);
                build();
                updateButtons();
                if (getParentActivity() != null) {
                    String msg = LocaleController.formatString(s.cancelled ? R.string.PcBulkSummaryCancelled : R.string.PcBulkSummary, s.done, s.skipped, s.total);
                    if (s.floods > 0) {
                        msg += "\n" + LocaleController.formatString(R.string.PcBulkSummaryFlood, s.floods, s.pausedSeconds);
                    }
                    AlertDialog.Builder b2 = new AlertDialog.Builder(getParentActivity());
                    b2.setTitle(LocaleController.getString(R.string.PcBulkSummaryTitle));
                    b2.setMessage(msg);
                    b2.setPositiveButton(LocaleController.getString(R.string.OK), null);
                    showDialog(b2.create());
                }
            }
        });
    }

    // ---- righe

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_ITEM) {
            return new Row(context);
        }
        if (viewType == T_CHIPS) {
            FrameLayout f = new FrameLayout(context);
            f.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
            return f;
        }
        TextView t = new TextView(context);
        t.setPadding(dp(16), dp(12), dp(16), dp(8));
        t.setTextSize(14);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        t.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
        return t;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Item it = items.get(position);
        if (viewType == T_ITEM) {
            ((Row) row).bind(it, selected.contains(it.dialogId));
        } else if (viewType == T_CHIPS) {
            bindChips((FrameLayout) row);
        } else {
            ((TextView) row).setText(it.title);
        }
    }

    private void bindChips(FrameLayout f) {
        f.removeAllViews();
        Context context = f.getContext();
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(context);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(context);
        final int[] names = {R.string.PcBulkFAll, R.string.PcBulkFPrivate, R.string.PcBulkFGroups, R.string.PcBulkFChannels, R.string.PcBulkFBots, R.string.PcBulkFArchived, R.string.PcBulkFUnread, R.string.PcBulkFInactive};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            String label = LocaleController.getString(names[i]);
            if (i == F_INACTIVE) {
                label = LocaleController.formatString(R.string.PcBulkFInactiveN, inactiveDays);
            }
            TextView t = PcUi.button(context, label, filter == i, v -> {
                if (idx == F_INACTIVE && filter == F_INACTIVE) {
                    pickDays();
                    return;
                }
                filter = idx;
                selected.clear();
                build();
                updateButtons();
            });
            r.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, 0, 0, 6, 0));
        }
        sv.addView(r);
        f.addView(sv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 8, 12, 4));
    }

    private void pickDays() {
        CharSequence[] labels = new CharSequence[DAYS.length];
        for (int i = 0; i < DAYS.length; i++) {
            labels[i] = LocaleController.formatString(R.string.PcBulkFInactiveN, DAYS[i]);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcBulkFInactive));
        b.setItems(labels, (d, which) -> {
            inactiveDays = DAYS[which];
            selected.clear();
            build();
            updateButtons();
        });
        showDialog(b.create());
    }

    @Override
    protected void onRowClick(View view, int position) {
        if (position < 0 || position >= items.size() || items.get(position).type != T_ITEM) {
            return;
        }
        Item it = items.get(position);
        if (!selected.remove(it.dialogId)) {
            selected.add(it.dialogId);
        }
        ((Row) view).bind(it, selected.contains(it.dialogId));
        updateButtons();
    }

    private class Row extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView title, sub;
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        Row(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, dp(64)));
            setBackground(Theme.getSelectorDrawable(false));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(40));
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

        void bind(Item it, boolean isChecked) {
            AvatarDrawable ad = new AvatarDrawable();
            if (it.peer instanceof TLRPC.User) {
                ad.setInfo(currentAccount, (TLRPC.User) it.peer);
            } else if (it.peer instanceof TLRPC.Chat) {
                ad.setInfo(currentAccount, (TLRPC.Chat) it.peer);
            }
            avatar.setForUserOrChat(it.peer, ad);
            title.setText(it.title);
            sub.setText(it.sub);
            checked = isChecked;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float sw = Math.max(1.5f, AndroidUtilities.density * 1.5f);
            int s = dp(22);
            float r = getWidth() - dp(16) - s, t = (getHeight() - s) / 2f;
            RectF rect = AndroidUtilities.rectTmp;
            rect.set(r, t, r + s, t + s);
            if (checked) {
                box.setStyle(Paint.Style.FILL);
                box.setColor(Theme.getColor(Theme.key_chats_actionBackground));
                canvas.drawRoundRect(rect, dp(4), dp(4), box);
                box.setStyle(Paint.Style.STROKE);
                box.setStrokeWidth(sw);
                box.setColor(Theme.getColor(Theme.key_chats_actionIcon));
                canvas.drawLine(r + s * 0.25f, t + s * 0.52f, r + s * 0.43f, t + s * 0.70f, box);
                canvas.drawLine(r + s * 0.43f, t + s * 0.70f, r + s * 0.76f, t + s * 0.32f, box);
            } else {
                box.setStyle(Paint.Style.STROKE);
                box.setStrokeWidth(sw);
                box.setColor(Theme.getColor(Theme.key_divider));
                canvas.drawRoundRect(rect, dp(4), dp(4), box);
            }
        }
    }
}
