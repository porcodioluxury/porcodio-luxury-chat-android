package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Feed interno (solo per chi usa l'app): post delle Bacheche degli amici e la tua, come righe di un social.
 * Con l'argomento "author" mostra solo la Bacheca di quella persona.
 */
public class PcFeedActivity extends PcListFragment implements org.telegram.ui.MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {
    private static final int T_BOARD = 0, T_POST = 1, T_NOTE = 2, T_CHIPS = 3, T_STORIES = 4, T_GRID = 5;
    private static final int REQ_PHOTO = 7101, REQ_VIDEO = 7102;
    private int section; // 0 feed, 1 storie, 2 reel, 3 esplora, 4 attività (solo nella scheda Social)

    private final long onlyAuthor;
    private final ArrayList<PcBoard.Post> posts = new ArrayList<>();
    private boolean loading = true, finding;
    private String findNote;

    public PcFeedActivity(Bundle args) {
        super(args);
        onlyAuthor = args != null ? args.getLong("author", 0) : 0;
    }

    {
        // il primo caricamento parte alla creazione del frammento
    }

    private void reload() {
        loading = true;
        PcBoard.loadFeed(currentAccount, list -> {
            loading = false;
            posts.clear();
            for (PcBoard.Post p : list) {
                if ((onlyAuthor == 0 || p.authorId == onlyAuthor) && !PcBoard.isSpecial(p)) {
                    posts.add(p);
                }
            }
            if (pull != null) {
                pull.setRefreshing(false);
            }
            notifyData();
        });
        notifyData();
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    private boolean tabMode() {
        return hasMainTabs;
    }

    @Override
    protected String screenTitle() {
        if (tabMode()) {
            return LocaleController.getString(R.string.PcTabSocial);
        }
        if (onlyAuthor != 0) {
            TLRPC.User u = getMessagesController().getUser(onlyAuthor);
            return u == null ? LocaleController.getString(R.string.PcFeedTitle) : LocaleController.formatString(R.string.PcFeedFrom, UserObject.getFirstName(u));
        }
        return LocaleController.getString(R.string.PcFeedTitle);
    }

    @Override
    protected boolean withPullToRefresh() {
        return true;
    }

    @Override
    protected void onPullRefresh() {
        reload();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return v;
    }

    private boolean showBoardCard() {
        return onlyAuthor == 0;
    }

    private static class Row {
        int type;
        PcBoard.Post post;
        ArrayList<PcBoard.Post> grid;

        Row(int type) {
            this.type = type;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    private static boolean isVideo(PcBoard.Post p) {
        return p.message != null && p.message.media instanceof TLRPC.TL_messageMediaDocument && p.message.media.document != null
            && org.telegram.messenger.MessageObject.isVideoDocument(p.message.media.document);
    }

    private static boolean isPhoto(PcBoard.Post p) {
        return p.message != null && p.message.media instanceof TLRPC.TL_messageMediaPhoto && p.message.media.photo != null;
    }

    private void rebuild() {
        rows.clear();
        if (tabMode()) {
            rows.add(new Row(T_STORIES));
            rows.add(new Row(T_CHIPS));
        }
        if (showBoardCard()) {
            rows.add(new Row(T_BOARD));
        }
        final long me = UserConfig.getInstance(currentAccount).getClientUserId();
        if (!tabMode() || section == 0) {
            for (PcBoard.Post p : posts) {
                Row r = new Row(T_POST);
                r.post = p;
                rows.add(r);
            }
        } else if (section == 2) {
            for (PcBoard.Post p : posts) {
                if (isVideo(p)) {
                    Row r = new Row(T_POST);
                    r.post = p;
                    rows.add(r);
                }
            }
        } else if (section == 3) {
            Row g = null;
            for (PcBoard.Post p : posts) {
                if (isPhoto(p) || isVideo(p)) {
                    if (g == null || g.grid.size() == 3) {
                        g = new Row(T_GRID);
                        g.grid = new ArrayList<>();
                        rows.add(g);
                    }
                    g.grid.add(p);
                }
            }
        } else if (section == 4) {
            for (PcBoard.Post p : posts) {
                if (p.authorId == me) {
                    Row r = new Row(T_POST);
                    r.post = p;
                    rows.add(r);
                }
            }
        }
        rows.add(new Row(T_NOTE));
    }

    @Override
    protected void notifyData() {
        rebuild();
        super.notifyData();
    }

    @Override
    public boolean onFragmentCreate() {
        reload();
        getNotificationCenter().addObserver(this, NotificationCenter.storiesUpdated);
        try {
            getMessagesController().getStoriesController().loadStories();
        } catch (Throwable ignore) {
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.storiesUpdated && tabMode()) {
            notifyData();
        }
    }

    @Override
    protected int itemCount() {
        if (rows.isEmpty()) {
            rebuild();
        }
        return rows.size();
    }

    @Override
    protected int itemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_STORIES) {
            return new StoriesRow(context);
        }
        if (viewType == T_GRID) {
            return new GridRow(context);
        }
        if (viewType == T_CHIPS) {
            ChipsRow c = new ChipsRow(context);
            c.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return c;
        }
        if (viewType == T_BOARD) {
            return new BoardRow(context);
        }
        if (viewType == T_POST) {
            return new PostRow(context);
        }
        TextView t = PcUi.label(context, "", 14, false, Theme.key_windowBackgroundWhiteGrayText);
        t.setTypeface(PcUi.serif(false, true));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(24), dp(24), dp(tabMode() ? org.telegram.ui.DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 24 : 96));
        t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return t;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Row r = rows.get(position);
        if (viewType == T_STORIES) {
            ((StoriesRow) row).bind();
        } else if (viewType == T_GRID) {
            ((GridRow) row).bind(r.grid);
        } else if (viewType == T_CHIPS) {
            ((ChipsRow) row).bind();
        } else if (viewType == T_BOARD) {
            ((BoardRow) row).bind();
        } else if (viewType == T_POST) {
            ((PostRow) row).bind(r.post);
        } else {
            TextView t = (TextView) row;
            boolean emptySection = true;
            for (Row x : rows) {
                if (x.type == T_POST || x.type == T_GRID) {
                    emptySection = false;
                    break;
                }
            }
            if (tabMode() && section == 1) {
                t.setText(LocaleController.getString(R.string.PcSocialStoriesNote));
            } else if (loading) {
                t.setText(LocaleController.getString(R.string.PcFeedLoading));
            } else if (emptySection && tabMode() && section != 0) {
                t.setText(LocaleController.getString(R.string.PcSocialSectionEmpty));
            } else if (emptySection) {
                t.setText(LocaleController.getString(R.string.PcFeedEmpty));
            } else {
                t.setText("");
            }
        }
    }

    // ---- pubblicazione: post con foto, reel (video), storia
    private String pendingCaption;

    private void onPlus() {
        if (getParentActivity() == null) {
            return;
        }
        if (!PcBoard.isEnabled(currentAccount)) {
            activate();
            return;
        }
        CharSequence[] items = {
            LocaleController.getString(R.string.PcPlusPhoto),
            LocaleController.getString(R.string.PcPlusReel),
            LocaleController.getString(R.string.PcPlusStory),
            LocaleController.getString(R.string.PcPlusText)};
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcPlusTitle));
        b.setItems(items, (d, which) -> {
            if (which == 0 || which == 1) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                i.setType(which == 0 ? "image/*" : "video/*");
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, which == 0 ? REQ_PHOTO : REQ_VIDEO);
            } else if (which == 2) {
                try {
                    org.telegram.ui.Stories.recorder.StoryRecorder.getInstance(getParentActivity(), currentAccount).open(null);
                } catch (Throwable t) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
                }
            } else {
                write();
            }
        });
        showDialog(b.create());
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if ((requestCode != REQ_PHOTO && requestCode != REQ_VIDEO) || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final boolean video = requestCode == REQ_VIDEO;
        final android.net.Uri uri = data.getData();
        PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcPlusPreparing));
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final String path = copyToCache(uri, video);
            AndroidUtilities.runOnUIThread(() -> {
                if (path == null) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
                } else {
                    askCaption(path, video);
                }
            });
        });
    }

    private String copyToCache(android.net.Uri uri, boolean video) {
        try (java.io.InputStream in = org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            java.io.File f = new java.io.File(org.telegram.messenger.ApplicationLoader.applicationContext.getCacheDir(), "pc_post_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg"));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private void askCaption(final String path, final boolean video) {
        if (getParentActivity() == null) {
            return;
        }
        final EditText edit = new EditText(getParentActivity());
        edit.setHint(LocaleController.getString(R.string.PcPlusCaption));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(2);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(video ? R.string.PcPlusReel : R.string.PcPlusPhoto));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardPublish), (d, w) -> {
            org.telegram.messenger.SendMessagesHelper.SendingMediaInfo info = new org.telegram.messenger.SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.isVideo = video;
            info.caption = edit.getText().toString().trim();
            ArrayList<org.telegram.messenger.SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
            list.add(info);
            long chat = PcBoard.myBoardChat(currentAccount);
            org.telegram.messenger.SendMessagesHelper.prepareSendingMedia(org.telegram.messenger.AccountInstance.getInstance(currentAccount), list, -chat, null, null, null, null,
                false, false, null, true, 0, 0, 0, false, null, org.telegram.messenger.SendMessageChatArguments.EMPTY, 0, false, 0, 0, null);
            PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardPublished));
            AndroidUtilities.runOnUIThread(this::reload, 6000);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    // ---- azioni Bacheca
    private void activate() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcBoardOffTitle));
        b.setMessage(LocaleController.getString(R.string.PcBoardOffText).replace("{tag}", PcBoard.MARKER));
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardOn), (d, w) -> {
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            String title = (me != null ? UserObject.getUserName(me) : "") + " - " + LocaleController.getString(R.string.PcProfileBoard);
            PcBoard.enable(this, title, ok -> {
                if (!ok) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
                }
                notifyData();
                reload();
            });
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void deactivate() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcBoardConfirmOffTitle));
        b.setMessage(LocaleController.getString(R.string.PcBoardConfirmOffText));
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardOffBtn), (d, w) -> PcBoard.disable(this, ok -> {
            if (!ok) {
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
            }
            notifyData();
            reload();
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void write() {
        if (getParentActivity() == null) {
            return;
        }
        final EditText edit = new EditText(getParentActivity());
        edit.setHint(LocaleController.getString(R.string.PcBoardHint));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(4);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcBoardWrite));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardPublish), (d, w) -> {
            String text = edit.getText().toString();
            if (!text.trim().isEmpty()) {
                PcBoard.publish(currentAccount, text);
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardPublished));
                AndroidUtilities.runOnUIThread(this::reload, 2000);
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void openMyBoard() {
        long chat = PcBoard.myBoardChat(currentAccount);
        if (chat == 0) {
            return;
        }
        Bundle b = new Bundle();
        b.putLong("chat_id", chat);
        presentFragment(new ChatActivity(b));
    }

    private void findFriends() {
        if (finding) {
            return;
        }
        if (PcBoard.budgetLeft(currentAccount) <= 0) {
            findNote = LocaleController.getString(R.string.PcBoardNoBudget);
            notifyData();
            return;
        }
        finding = true;
        findNote = LocaleController.getString(R.string.PcBoardFinding);
        notifyData();
        PcBoard.findFriends(currentAccount, res -> {
            finding = false;
            findNote = res[0] > 0 ? LocaleController.formatString(R.string.PcBoardFound, res[0]) : LocaleController.getString(R.string.PcBoardNoFound);
            notifyData();
            if (res[0] > 0) {
                reload();
            }
        });
    }

    private void openPost(PcBoard.Post p) {
        Bundle b = new Bundle();
        b.putLong("chat_id", p.chatId);
        b.putInt("message_id", p.msgId);
        presentFragment(new ChatActivity(b));
    }

    private class ChipsRow extends FrameLayout {
        ChipsRow(Context context) {
            super(context);
        }

        void bind() {
            removeAllViews();
            final Context context = getContext();
            android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(context);
            sv.setHorizontalScrollBarEnabled(false);
            LinearLayout r = new LinearLayout(context);
            TextView plus = PcUi.button(context, "+", true, v -> onPlus());
            plus.setTextSize(20);
            r.addView(plus, LayoutHelper.createLinear(44, 38, 0, 0, 10, 0));
            final int[] names = {R.string.PcSocialFeed, R.string.PcSocialStories, R.string.PcSocialReels, R.string.PcSocialExplore, R.string.PcSocialActivity};
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                TextView t = PcUi.button(context, LocaleController.getString(names[i]), section == i, v -> {
                    section = idx;
                    notifyData();
                });
                r.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, 0, 0, 6, 0));
            }
            sv.addView(r);
            addView(sv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 8, 12, 4));
        }
    }

    /** Riga delle storie: "La mia storia" con + e i contatti con storie (viewer nativo dell'API). */
    private class StoriesRow extends FrameLayout {
        StoriesRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        }

        private View item(Context context, String label, TLRPC.User user, boolean plus, boolean unread, View.OnClickListener click) {
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout holder = new FrameLayout(context);
            holder.setBackground(ring(unread));
            BackupImageView img = new BackupImageView(context);
            img.setRoundRadius(dp(30));
            if (plus) {
                TextView p = PcUi.label(context, "+", 30, false, Theme.key_windowBackgroundWhiteBlackText);
                p.setGravity(Gravity.CENTER);
                p.setBackground(PcUi.filled(0xFF1A1A1A));
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(0xFF1A1A1A);
                p.setBackground(g);
                holder.addView(p, LayoutHelper.createFrame(60, 60, Gravity.CENTER));
            } else {
                AvatarDrawable ad = new AvatarDrawable();
                if (user != null) {
                    ad.setInfo(currentAccount, user);
                    img.setForUserOrChat(user, ad);
                }
                holder.addView(img, LayoutHelper.createFrame(60, 60, Gravity.CENTER));
            }
            col.addView(holder, LayoutHelper.createLinear(68, 68));
            TextView t = PcUi.label(context, label, 12, false, Theme.key_windowBackgroundWhiteBlackText);
            t.setTypeface(PcUi.serif(false, false));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            col.addView(t, LayoutHelper.createLinear(72, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            col.setOnClickListener(click);
            return col;
        }

        private android.graphics.drawable.Drawable ring(boolean unread) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(0);
            g.setStroke(Math.max(2, dp(1.5f)), unread ? 0xFFF3EEE6 : 0x55F3EEE6);
            return g;
        }

        void bind() {
            removeAllViews();
            final Context context = getContext();
            android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(context);
            sv.setHorizontalScrollBarEnabled(false);
            LinearLayout r = new LinearLayout(context);
            r.addView(item(context, LocaleController.getString(R.string.PcMyStory), null, true, false, v -> {
                try {
                    org.telegram.ui.Stories.recorder.StoryRecorder.getInstance(getParentActivity(), currentAccount).open(null);
                } catch (Throwable t) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
                }
            }), LayoutHelper.createLinear(76, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
            try {
                ArrayList<org.telegram.tgnet.tl.TL_stories.PeerStories> list = getMessagesController().getStoriesController().getDialogListStories();
                final long me = UserConfig.getInstance(currentAccount).getClientUserId();
                for (org.telegram.tgnet.tl.TL_stories.PeerStories ps : list) {
                    final long did = org.telegram.messenger.DialogObject.getPeerDialogId(ps.peer);
                    if (did <= 0 || did == me) {
                        continue;
                    }
                    TLRPC.User u = getMessagesController().getUser(did);
                    if (u == null) {
                        continue;
                    }
                    boolean unread = getMessagesController().getStoriesController().hasUnreadStories(did);
                    r.addView(item(context, UserObject.getFirstName(u), u, false, unread, v -> {
                        if (getParentActivity() != null) {
                            getOrCreateStoryViewer().open(getParentActivity(), did, org.telegram.ui.Stories.StoriesListPlaceProvider.of(listView));
                        }
                    }), LayoutHelper.createLinear(76, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
                }
            } catch (Throwable ignore) {
            }
            sv.addView(r);
            addView(sv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 14, 10, 8, 2));
        }
    }

    /** Tre miniature per riga (Esplora / griglia). */
    private class GridRow extends LinearLayout {
        GridRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            setPadding(dp(1), dp(1), dp(1), dp(1));
        }

        void bind(ArrayList<PcBoard.Post> items) {
            removeAllViews();
            final int size = (AndroidUtilities.displaySize.x - dp(2)) / 3 - dp(2);
            for (int i = 0; i < 3; i++) {
                BackupImageView img = new BackupImageView(getContext());
                img.setBackgroundColor(0xFF1A1A1A);
                if (i < items.size()) {
                    final PcBoard.Post p = items.get(i);
                    setMedia(img, p, 400);
                    img.setOnClickListener(v -> openPost(p));
                }
                addView(img, LayoutHelper.createLinear(0, 0));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                img.setLayoutParams(lp);
            }
        }
    }

    private static void setMedia(BackupImageView img, PcBoard.Post p, int side) {
        try {
            if (isPhoto(p)) {
                TLRPC.Photo photo = p.message.media.photo;
                TLRPC.PhotoSize sz = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, side);
                img.setImage(ImageLocation.getForPhoto(sz, photo), side + "_" + side, (android.graphics.drawable.Drawable) null, p.message);
            } else if (isVideo(p)) {
                TLRPC.Document doc = p.message.media.document;
                TLRPC.PhotoSize sz = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, side);
                if (sz != null) {
                    img.setImage(ImageLocation.getForDocument(sz, doc), side + "_" + side, (android.graphics.drawable.Drawable) null, p.message);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private class BoardRow extends FrameLayout {
        private final LinearLayout box;

        BoardRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(14), dp(14), dp(14));
            box.setBackground(PcUi.panel(true));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 8, 12, 4));
        }

        void bind() {
            final Context context = getContext();
            box.removeAllViews();
            if (!PcBoard.isEnabled(currentAccount)) {
                box.addView(PcUi.label(context, LocaleController.getString(R.string.PcBoardOffTitle), 16, true, Theme.key_windowBackgroundWhiteBlackText));
                TextView info = PcUi.label(context, LocaleController.getString(R.string.PcBoardOffText).replace("{tag}", PcBoard.MARKER), 13, false, Theme.key_windowBackgroundWhiteGrayText);
                box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 10));
                box.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardOn), true, v -> activate()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
            } else {
                LinearLayout r1 = new LinearLayout(context);
                r1.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardWrite), true, v -> write()), LayoutHelper.createLinear(0, 44, 1f, 0, 0, 4, 0));
                r1.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardOpen), false, v -> openMyBoard()), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 0, 0));
                box.addView(r1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                LinearLayout r2 = new LinearLayout(context);
                r2.addView(PcUi.button(context, LocaleController.formatString(R.string.PcBoardFind, PcBoard.budgetLeft(currentAccount)), false, v -> findFriends()), LayoutHelper.createLinear(0, 44, 2f, 0, 0, 4, 0));
                r2.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardOffBtn), false, v -> deactivate()), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 0, 0));
                box.addView(r2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            }
            if (findNote != null) {
                box.addView(PcUi.label(context, findNote, 12, false, Theme.key_windowBackgroundWhiteGrayText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            }
        }
    }

    private class PostRow extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView name, date, text, stats;
        private final TextView likeBtn;
        private final BackupImageView media;
        private final TextView playBadge;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private PcBoard.Post post;

        PostRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            box.setBackground(PcUi.panel(true));
            LinearLayout head = new LinearLayout(context);
            head.setGravity(Gravity.CENTER_VERTICAL);
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(4));
            head.addView(avatar, LayoutHelper.createLinear(40, 40));
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            name = PcUi.label(context, "", 15, true, Theme.key_windowBackgroundWhiteBlackText);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            date = PcUi.label(context, "", 12, false, Theme.key_windowBackgroundWhiteGrayText);
            col.addView(name);
            col.addView(date);
            head.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
            box.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            FrameLayout mediaBox = new FrameLayout(context);
            media = new BackupImageView(context);
            media.setBackgroundColor(0xFF1A1A1A);
            media.setOnClickListener(v -> {
                if (post != null) openPost(post);
            });
            mediaBox.addView(media, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            playBadge = PcUi.label(context, "\u25B6", 28, false, Theme.key_windowBackgroundWhiteBlackText);
            playBadge.setTextColor(0xFFF3EEE6);
            playBadge.setGravity(Gravity.CENTER);
            mediaBox.addView(playBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
            mediaBox.setTag("mediabox");
            box.addView(mediaBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 300, 0, 10, 0, 0));
            text = PcUi.label(context, "", 15, false, Theme.key_windowBackgroundWhiteBlackText);
            text.setMaxLines(12);
            text.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            stats = PcUi.label(context, "", 12, false, Theme.key_windowBackgroundWhiteGrayText);
            box.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            LinearLayout actions = new LinearLayout(context);
            likeBtn = PcUi.button(context, LocaleController.getString(R.string.PcLike), false, v -> like());
            actions.addView(likeBtn, LayoutHelper.createLinear(0, 38, 1f, 0, 0, 4, 0));
            actions.addView(PcUi.button(context, LocaleController.getString(R.string.PcComment), false, v -> {
                if (post != null) openPost(post);
            }), LayoutHelper.createLinear(0, 38, 1f, 4, 0, 4, 0));
            actions.addView(PcUi.button(context, LocaleController.getString(R.string.PcOpenPost), false, v -> {
                if (post != null) openPost(post);
            }), LayoutHelper.createLinear(0, 38, 1f, 4, 0, 0, 0));
            box.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 4, 12, 4));
        }

        private void like() {
            if (post == null) {
                return;
            }
            final PcBoard.Post p = post;
            PcBoard.like(currentAccount, p, ok -> {
                if (ok) {
                    p.likes++;
                    if (post == p) {
                        bind(p);
                    }
                } else {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcLikeFailed));
                }
            });
        }

        void bind(PcBoard.Post p) {
            post = p;
            TLRPC.User u = getMessagesController().getUser(p.authorId);
            if (u != null) {
                avatarDrawable.setInfo(currentAccount, u);
                avatar.setForUserOrChat(u, avatarDrawable);
                name.setText(UserObject.getUserName(u));
            } else {
                name.setText("");
            }
            date.setText(LocaleController.formatShortDateTime(p.date));
            View mb = findViewWithTag("mediabox");
            boolean hasMedia = isPhoto(p) || isVideo(p);
            if (mb != null) {
                mb.setVisibility(hasMedia ? VISIBLE : GONE);
                if (hasMedia) {
                    setMedia(media, p, 800);
                    playBadge.setVisibility(isVideo(p) ? VISIBLE : GONE);
                }
            }
            text.setText(p.text);
            text.setVisibility(TextUtils.isEmpty(p.text) ? GONE : VISIBLE);
            stats.setText((p.likes > 0 ? "\u2764 " + p.likes + "   " : "") + (p.views > 0 ? LocaleController.formatString(R.string.PcViews, p.views) : ""));
            stats.setVisibility(p.likes > 0 || p.views > 0 ? VISIBLE : GONE);
        }
    }
}
