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
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Pulizia rapida: elenca gruppi inattivi (nessun messaggio da oltre 90 giorni) o silenziati e inattivi da oltre 30 giorni,
 * e canali mai aperti o con molti non letti accumulati. Selezione multipla -> archivia o esci, sempre con conferma.
 */
public class PcCleanupActivity extends PcListFragment {
    private static final int DAYS_INACTIVE = 90, DAYS_MUTED = 30, PILE = 100;
    private static final int T_HEADER = 0, T_ITEM = 1, T_HINT = 2;

    private static class Item {
        int type;
        String title, sub;
        long dialogId;
        TLRPC.Chat chat;
        boolean checked;
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private TextView archiveBtn, leaveBtn;

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcQuickCleanup);
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
    public View createView(Context context) {
        View v = super.createView(context);
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        archiveBtn = button(context);
        leaveBtn = button(context);
        archiveBtn.setOnClickListener(x -> act(false));
        leaveBtn.setOnClickListener(x -> act(true));
        bar.addView(archiveBtn, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 6, 0));
        bar.addView(leaveBtn, LayoutHelper.createLinear(0, 48, 1f, 6, 0, 0, 0));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM));
        build();
        updateButtons();
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

    private void build() {
        items.clear();
        final MessagesController mc = getMessagesController();
        final int now = getConnectionsManager().getCurrentTime();
        final ArrayList<Item> groups = new ArrayList<>(), channels = new ArrayList<>();
        for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
            if (!(d instanceof TLRPC.TL_dialog) || d.id >= 0 || d.folder_id != 0) {
                continue;
            }
            TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || ChatObject.isNotInChat(chat) || chat.creator) {
                continue;
            }
            int last = d.last_message_date;
            if (last == 0) {
                java.util.ArrayList<org.telegram.messenger.MessageObject> m = mc.dialogMessage.get(d.id);
                if (m != null && !m.isEmpty() && m.get(0).messageOwner != null) {
                    last = m.get(0).messageOwner.date;
                }
            }
            int days = last > 0 ? Math.max(0, (now - last) / 86400) : 9999;
            boolean muted = mc.isDialogMuted(d.id, 0);
            boolean channel = ChatObject.isChannel(chat) && !chat.megagroup;
            Item it = new Item();
            it.type = T_ITEM;
            it.dialogId = d.id;
            it.chat = chat;
            it.title = chat.title;
            if (!channel) {
                if (days >= DAYS_INACTIVE) {
                    it.sub = LocaleController.formatString(R.string.PcInactiveFor, days);
                } else if (muted && days >= DAYS_MUTED) {
                    it.sub = LocaleController.formatString(R.string.PcMutedFor, days);
                } else {
                    continue;
                }
                groups.add(it);
            } else {
                int unread = mc.getDialogUnreadCount(d);
                if (unread > 0 && d.read_inbox_max_id == 0) {
                    it.sub = LocaleController.formatString(R.string.PcUnreadNever, unread);
                } else if (unread >= PILE) {
                    it.sub = LocaleController.formatString(R.string.PcUnreadPile, unread);
                } else {
                    continue;
                }
                channels.add(it);
            }
        }
        Item hint = new Item();
        hint.type = T_HINT;
        hint.title = LocaleController.getString(groups.isEmpty() && channels.isEmpty() ? R.string.PcCleanupEmpty : R.string.PcCleanupHint);
        items.add(hint);
        if (!groups.isEmpty()) {
            items.add(header(LocaleController.getString(R.string.PcCleanupInactive)));
            items.addAll(groups);
        }
        if (!channels.isEmpty()) {
            items.add(header(LocaleController.getString(R.string.PcCleanupUnopened)));
            items.addAll(channels);
        }
        notifyData();
    }

    private Item header(String t) {
        Item h = new Item();
        h.type = T_HEADER;
        h.title = t;
        return h;
    }

    private ArrayList<Item> selected() {
        ArrayList<Item> r = new ArrayList<>();
        for (Item i : items) {
            if (i.type == T_ITEM && i.checked) {
                r.add(i);
            }
        }
        return r;
    }

    private void updateButtons() {
        int n = selected().size();
        archiveBtn.setText(LocaleController.formatString(R.string.PcArchiveN, n));
        leaveBtn.setText(LocaleController.formatString(R.string.PcLeaveN, n));
        archiveBtn.setEnabled(n > 0);
        leaveBtn.setEnabled(n > 0);
        int on = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), off = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        archiveBtn.setTextColor(n > 0 ? on : off);
        leaveBtn.setTextColor(n > 0 ? on : off);
        archiveBtn.invalidate();
        leaveBtn.invalidate();
    }

    private void act(boolean leave) {
        final ArrayList<Item> sel = selected();
        if (sel.isEmpty() || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(leave ? R.string.PcConfirmLeaveTitle : R.string.PcConfirmArchiveTitle));
        b.setMessage(LocaleController.formatString(leave ? R.string.PcConfirmLeaveText : R.string.PcConfirmArchiveText, sel.size()));
        b.setPositiveButton(LocaleController.getString(leave ? R.string.PcLeaveN_short : R.string.PcArchive_short), (d, w) -> {
            final MessagesController mc = getMessagesController();
            for (Item i : sel) {
                if (leave) {
                    TLRPC.User me = mc.getUser(getUserConfig().getClientUserId());
                    mc.deleteParticipantFromChat(-i.dialogId, me, null, false, false);
                } else {
                    mc.addDialogToFolder(i.dialogId, 1, -1, 0);
                }
                items.remove(i);
            }
            build();
            updateButtons();
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

    @Override
    protected void onRowClick(View view, int position) {
        if (position < 0 || position >= items.size() || items.get(position).type != T_ITEM) {
            return;
        }
        Item it = items.get(position);
        it.checked = !it.checked;
        ((Row) view).bind(it);
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

        void bind(Item it) {
            AvatarDrawable ad = new AvatarDrawable();
            ad.setInfo(currentAccount, it.chat);
            avatar.setForUserOrChat(it.chat, ad);
            title.setText(it.title);
            sub.setText(it.sub);
            checked = it.checked;
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
