package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;

/**
 * Reel: video verticali a schermo intero (scorrimento a pagine), in alto "+" / Reel / Amici,
 * azioni a destra (mi piace, commento, invia, salva) e in basso l'autore con il pulsante Segui.
 * I reel sono i video delle Bacheche (tag #reel o video).
 */
public class PcReelsActivity extends PcListFragment implements org.telegram.ui.MainTabsActivity.TabFragmentDelegate {
    private final ArrayList<PcBoard.Post> reels = new ArrayList<>();
    private boolean friendsOnly, loading = true;
    private VideoPlayer player;
    private int playing = -1;
    private TextView tabReel, tabFriends;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            checkVisibility();
            if (root != null) {
                AndroidUtilities.runOnUIThread(this, 600);
            }
        }
    };

    public PcReelsActivity(Bundle args) {
        super(args);
    }

    @Override
    protected String screenTitle() {
        return "Reel";
    }

    @Override
    protected int itemCount() {
        return Math.max(1, reels.size());
    }

    @Override
    protected int itemViewType(int position) {
        return reels.isEmpty() ? 1 : 0;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    private int pageHeight() {
        int h = listView != null ? listView.getMeasuredHeight() : 0;
        if (h < dp(200)) {
            h = AndroidUtilities.displaySize.y - dp(org.telegram.ui.DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
        }
        return h;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == 1) {
            TextView t = PcUi.label(context, "", 15, false, Theme.key_windowBackgroundWhiteGrayText);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(32), 0, dp(32), 0);
            t.setLayoutParams(new RecyclerView.LayoutParams(-1, pageHeight()));
            return t;
        }
        ReelPage p = new ReelPage(context);
        p.setLayoutParams(new RecyclerView.LayoutParams(-1, pageHeight()));
        return p;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        if (viewType == 1) {
            ((TextView) row).setText(loading ? LocaleController.getString(R.string.PcFeedLoading) : LocaleController.getString(R.string.PcReelsEmpty));
            row.getLayoutParams().height = pageHeight();
            return;
        }
        row.getLayoutParams().height = pageHeight();
        ((ReelPage) row).bind(reels.get(position));
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(0xFF000000);
        listView.setBackgroundColor(0xFF000000);
        listView.setItemAnimator(null);
        new PagerSnapHelper().attachToRecyclerView(listView);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    playCurrent();
                }
            }
        });
        // intestazione sopra il video: "+", Reel, Amici
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), AndroidUtilities.statusBarHeight, dp(8), 0);
        ImageView plus = icon(context, R.drawable.ic_pc_plus, 26);
        plus.setOnClickListener(x -> publish());
        top.addView(plus, LayoutHelper.createLinear(44, 44));
        tabReel = headerTab(context, "Reel", true);
        tabReel.setOnClickListener(x -> setFriendsOnly(false));
        tabFriends = headerTab(context, LocaleController.getString(R.string.PcReelsFriends), false);
        tabFriends.setOnClickListener(x -> setFriendsOnly(true));
        top.addView(tabReel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 8, 0, 0, 0));
        top.addView(tabFriends, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 8, 0, 0, 0));
        root.addView(top, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return v;
    }

    @Override
    protected void onViewCreated(View view) {
        super.onViewCreated(view);
        // nessuna barra semplice sopra il video
        if (simpleActionBar != null) {
            simpleActionBar.setVisibility(View.GONE);
        }
        if (listView != null) {
            listView.setPadding(0, 0, 0, 0);
        }
    }

    private ImageView icon(Context context, int res, int size) {
        ImageView i = new ImageView(context);
        i.setImageResource(res);
        i.setColorFilter(0xFFF2F2F2);
        i.setScaleType(ImageView.ScaleType.CENTER);
        return i;
    }

    private TextView headerTab(Context context, String text, boolean selected) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setTextColor(selected ? 0xFFF2F2F2 : 0xFFBFBFBF);
        return t;
    }

    private void setFriendsOnly(boolean v) {
        friendsOnly = v;
        tabReel.setTextColor(v ? 0xFFBFBFBF : 0xFFF2F2F2);
        tabFriends.setTextColor(v ? 0xFFF2F2F2 : 0xFFBFBFBF);
        applyList(lastAll);
    }

    private void publish() {
        // stesso flusso del "+" della Home: scelta Foto / Reel / Storia / Testo
        Bundle a = new Bundle();
        a.putString("pc_mode", "home");
        a.putBoolean("pc_open_plus", true);
        presentFragment(new PcFeedActivity(a));
    }

    private ArrayList<PcBoard.Post> lastAll = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        load();
        return super.onFragmentCreate();
    }

    private static boolean isReel(PcBoard.Post p) {
        if (p.message == null || !(p.message.media instanceof TLRPC.TL_messageMediaDocument) || p.message.media.document == null) {
            return false;
        }
        if (p.text != null && p.text.toLowerCase(java.util.Locale.ROOT).contains("#reel")) {
            return true;
        }
        return MessageObject.isVideoDocument(p.message.media.document);
    }

    private void load() {
        loading = true;
        PcBoard.loadFeed(currentAccount, list -> {
            loading = false;
            lastAll = list;
            applyList(list);
        });
    }

    private void applyList(ArrayList<PcBoard.Post> list) {
        ArrayList<PcBoard.Post> vids = new ArrayList<>();
        final long me = UserConfig.getInstance(currentAccount).getClientUserId();
        for (PcBoard.Post p : list) {
            if (isReel(p) && (!friendsOnly || PcBoard.isFollowed(p.authorId) || p.authorId == me)) {
                vids.add(p);
            }
        }
        reels.clear();
        reels.addAll(PcRanking.rankFeed(currentAccount, vids, false));
        stopPlayer();
        notifyData();
        AndroidUtilities.runOnUIThread(this::playCurrent, 400);
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.runOnUIThread(ticker, 600);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(ticker);
        if (player != null) {
            player.pause();
        }
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(ticker);
        stopPlayer();
        super.onFragmentDestroy();
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    private boolean pageVisible() {
        if (root == null) {
            return false;
        }
        Rect r = new Rect();
        return root.getGlobalVisibleRect(r) && r.width() > root.getWidth() * 0.6f && root.isShown();
    }

    private void checkVisibility() {
        if (player == null) {
            if (pageVisible() && !reels.isEmpty()) {
                playCurrent();
            }
            return;
        }
        if (pageVisible()) {
            if (!player.isPlaying()) {
                player.play();
            }
        } else if (player.isPlaying()) {
            player.pause();
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.releasePlayer(true);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            player = null;
        }
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View c = listView.getChildAt(i);
                if (c instanceof ReelPage) {
                    ((ReelPage) c).showPoster();
                }
            }
        }
        playing = -1;
    }

    private void playCurrent() {
        if (listView == null || reels.isEmpty() || !pageVisible()) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) listView.getLayoutManager();
        int pos = lm.findFirstCompletelyVisibleItemPosition();
        if (pos < 0) {
            pos = lm.findFirstVisibleItemPosition();
        }
        if (pos < 0 || pos >= reels.size() || pos == playing) {
            return;
        }
        View v = lm.findViewByPosition(pos);
        if (!(v instanceof ReelPage)) {
            return;
        }
        stopPlayer();
        final ReelPage page = (ReelPage) v;
        final PcBoard.Post p = reels.get(pos);
        try {
            TLRPC.Document doc = p.message.media.document;
            MessageObject mo = new MessageObject(currentAccount, p.message, false, false);
            Uri uri = FileStreamLoadOperation.prepareUri(currentAccount, doc, mo);
            if (uri == null) {
                PcBoard.fail("reel", "uri video nulla");
                return;
            }
            player = new VideoPlayer();
            player.setTextureView(page.texture);
            player.setLooping(true);
            player.preparePlayer(uri, "other");
            player.play();
            playing = pos;
            PcInterests.record(p, PcInterests.W_OPEN);
            AndroidUtilities.runOnUIThread(() -> {
                if (player != null && playing >= 0) {
                    page.hidePoster();
                }
            }, 900);
        } catch (Throwable t) {
            FileLog.e(t);
            PcBoard.fail("reel", String.valueOf(t));
        }
    }

    private class ReelPage extends FrameLayout {
        final TextureView texture;
        private final BackupImageView poster;
        private final BackupImageView avatar;
        private final TextView name, caption, follow, likeCount, commentCount;
        private final ImageView like, comment, share, save;
        private PcBoard.Post post;
        private final AvatarDrawable ad = new AvatarDrawable();
        private boolean liked, saved;

        ReelPage(Context context) {
            super(context);
            setBackgroundColor(0xFF000000);
            texture = new TextureView(context);
            addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            poster = new BackupImageView(context);
            addView(poster, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            View shade = new View(context);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000});
            shade.setBackground(g);
            addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220, Gravity.BOTTOM));
            // azioni a destra
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            like = action(context, R.drawable.ic_pc_heart);
            likeCount = count(context);
            comment = action(context, R.drawable.ic_pc_comment);
            commentCount = count(context);
            share = action(context, R.drawable.ic_pc_share);
            save = action(context, R.drawable.ic_pc_bookmark);
            col.addView(like, LayoutHelper.createLinear(48, 44));
            col.addView(likeCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
            col.addView(comment, LayoutHelper.createLinear(48, 44));
            col.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
            col.addView(share, LayoutHelper.createLinear(48, 44, 0, 0, 0, 10));
            col.addView(save, LayoutHelper.createLinear(48, 44, 0, 0, 0, 10));
            addView(col, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 6, 28));
            like.setOnClickListener(v -> onLike());
            comment.setOnClickListener(v -> {
                if (post != null) {
                    Bundle b = new Bundle();
                    b.putLong("chat_id", post.chatId);
                    b.putInt("message_id", post.msgId);
                    presentFragment(new ChatActivity(b));
                }
            });
            share.setOnClickListener(v -> onShare());
            save.setOnClickListener(v -> onSave());
            // autore + Segui + didascalia
            LinearLayout bottom = new LinearLayout(context);
            bottom.setOrientation(LinearLayout.VERTICAL);
            LinearLayout who = new LinearLayout(context);
            who.setGravity(Gravity.CENTER_VERTICAL);
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(18));
            who.addView(avatar, LayoutHelper.createLinear(36, 36));
            name = PcUi.label(context, "", 15, true, Theme.key_windowBackgroundWhiteBlackText);
            name.setTextColor(0xFFF2F2F2);
            name.setSingleLine(true);
            who.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 10, 0));
            follow = new TextView(context);
            follow.setTextSize(13);
            follow.setTypeface(AndroidUtilities.bold());
            follow.setGravity(Gravity.CENTER);
            follow.setPadding(dp(14), 0, dp(14), 0);
            follow.setOnClickListener(v -> {
                if (post != null) {
                    PcBoard.setFollowed(post.authorId, !PcBoard.isFollowed(post.authorId));
                    refreshFollow();
                }
            });
            who.addView(follow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30));
            bottom.addView(who);
            caption = PcUi.label(context, "", 14, false, Theme.key_windowBackgroundWhiteBlackText);
            caption.setTextColor(0xFFF2F2F2);
            caption.setMaxLines(2);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bottom.addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 56, 0));
            addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 14, 0, 14, 28));
        }

        private ImageView action(Context context, int res) {
            ImageView i = new ImageView(context);
            i.setImageResource(res);
            i.setColorFilter(0xFFF2F2F2);
            i.setScaleType(ImageView.ScaleType.CENTER);
            return i;
        }

        private TextView count(Context context) {
            TextView t = PcUi.label(context, "", 12, false, Theme.key_windowBackgroundWhiteBlackText);
            t.setTextColor(0xFFF2F2F2);
            t.setGravity(Gravity.CENTER);
            return t;
        }

        void showPoster() {
            poster.setVisibility(VISIBLE);
        }

        void hidePoster() {
            poster.setVisibility(GONE);
        }

        private void refreshFollow() {
            final boolean f = post != null && PcBoard.isFollowed(post.authorId);
            follow.setText(f ? LocaleController.getString(R.string.PcFollowing) : LocaleController.getString(R.string.PcFollow));
            follow.setTextColor(0xFFF2F2F2);
            follow.setBackground(PcFeedActivity.pill(f ? 0x552F3442 : 0x00000000, 0xFFF2F2F2));
            follow.setVisibility(post != null && post.authorId == UserConfig.getInstance(currentAccount).getClientUserId() ? GONE : VISIBLE);
        }

        void bind(PcBoard.Post p) {
            post = p;
            liked = false;
            saved = false;
            like.setColorFilter(0xFFF2F2F2);
            save.setColorFilter(0xFFF2F2F2);
            poster.setVisibility(VISIBLE);
            try {
                TLRPC.Document doc = p.message.media.document;
                TLRPC.PhotoSize th = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 640);
                if (th != null) {
                    poster.setImage(ImageLocation.getForDocument(th, doc), null, (android.graphics.drawable.Drawable) null, p.message);
                }
            } catch (Throwable ignore) {
            }
            TLRPC.User u = getMessagesController().getUser(p.authorId);
            name.setText(u != null ? UserObject.getUserName(u) : "");
            if (u != null) {
                ad.setInfo(currentAccount, u);
                avatar.setForUserOrChat(u, ad);
            }
            String cap = p.text == null ? "" : p.text.replace("#reel", "").trim();
            caption.setText(cap);
            likeCount.setText(p.likes > 0 ? String.valueOf(p.likes) : "");
            commentCount.setText(p.comments > 0 ? String.valueOf(p.comments) : "");
            refreshFollow();
        }

        private void onLike() {
            if (post == null) {
                return;
            }
            PcBoard.like(currentAccount, post, ok -> {
                if (ok) {
                    liked = true;
                    like.setColorFilter(0xFF9DAAC8);
                    PcInterests.recordOnce(post, "like", PcInterests.W_LIKE);
                } else {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcLikeFailed));
                }
            });
        }

        private void onSave() {
            if (post == null) {
                return;
            }
            PcBoard.saveToSaved(currentAccount, post, ok -> {
                if (ok) {
                    saved = true;
                    save.setColorFilter(0xFF9DAAC8);
                    PcInterests.recordOnce(post, "save", PcInterests.W_SAVE);
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcSavedOk));
                }
            });
        }

        private void onShare() {
            if (post == null || getParentActivity() == null) {
                return;
            }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(android.content.Intent.EXTRA_TEXT, "https://t.me/" + PcBoard.username(post.authorId) + "/" + post.msgId);
            getParentActivity().startActivity(android.content.Intent.createChooser(i, LocaleController.getString(R.string.PcShare)));
            PcInterests.recordOnce(post, "share", PcInterests.W_SHARE);
        }
    }
}
