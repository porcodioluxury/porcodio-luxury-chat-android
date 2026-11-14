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
public class PcFeedActivity extends PcListFragment implements org.telegram.ui.MainTabsActivity.TabFragmentDelegate {
    private static final int T_BOARD = 0, T_POST = 1, T_NOTE = 2, T_CHIPS = 3;
    private int section; // 0 feed, 1 storie, 2 reel, 3 esplora, 4 attività (solo nella scheda Social)

    private final long onlyAuthor;
    private final ArrayList<PcBoard.Post> posts = new ArrayList<>();
    private boolean loading = true, finding;
    private String findNote;

    public PcFeedActivity(Bundle args) {
        super(args);
        onlyAuthor = args != null ? args.getLong("author", 0) : 0;
    }

    @Override
    public boolean onFragmentCreate() {
        reload();
        return super.onFragmentCreate();
    }

    private void reload() {
        loading = true;
        PcBoard.loadFeed(currentAccount, list -> {
            loading = false;
            posts.clear();
            for (PcBoard.Post p : list) {
                if (onlyAuthor == 0 || p.authorId == onlyAuthor) {
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

    @Override
    protected int itemCount() {
        return (tabMode() ? 1 : 0) + (showBoardCard() ? 1 : 0) + shownPosts() + 1;
    }

    private int shownPosts() {
        return section == 0 ? posts.size() : 0;
    }

    private int shift() {
        return (tabMode() ? 1 : 0) + (showBoardCard() ? 1 : 0);
    }

    @Override
    protected int itemViewType(int position) {
        if (tabMode() && position == 0) {
            return T_CHIPS;
        }
        if (showBoardCard() && position == (tabMode() ? 1 : 0)) {
            return T_BOARD;
        }
        return position - shift() < shownPosts() ? T_POST : T_NOTE;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
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
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(24), dp(24), dp(tabMode() ? org.telegram.ui.DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 24 : 96));
        t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return t;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        if (viewType == T_CHIPS) {
            ((ChipsRow) row).bind();
        } else if (viewType == T_BOARD) {
            ((BoardRow) row).bind();
        } else if (viewType == T_POST) {
            ((PostRow) row).bind(posts.get(position - shift()));
        } else {
            TextView t = (TextView) row;
            if (section != 0) {
                t.setText(LocaleController.getString(R.string.PcSocialSoon));
            } else if (loading) {
                t.setText(LocaleController.getString(R.string.PcFeedLoading));
            } else if (posts.isEmpty()) {
                t.setText(LocaleController.getString(R.string.PcFeedEmpty));
            } else {
                t.setText("");
            }
        }
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
            text.setText(p.text);
            text.setVisibility(TextUtils.isEmpty(p.text) ? GONE : VISIBLE);
            stats.setText((p.likes > 0 ? "\u2764 " + p.likes + "   " : "") + (p.views > 0 ? LocaleController.formatString(R.string.PcViews, p.views) : ""));
            stats.setVisibility(p.likes > 0 || p.views > 0 ? VISIBLE : GONE);
        }
    }
}
