package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.UserInfoActivity;

import java.util.ArrayList;

/**
 * Il mio profilo (si apre dalla card in Impostazioni): copertina, link e griglia dei miei post.
 * Copertina e link sono post speciali della Bacheca (#cover, #links), così li vedono anche gli altri utenti dell'app.
 */
public class PcProfileEditActivity extends PcListFragment {
    private static final int T_COVER = 0, T_LINKS = 1, T_EDIT = 2, T_GRID = 3, T_NOTE = 4;
    private static final int REQ_COVER = 7201;

    private final ArrayList<PcBoard.Post> mine = new ArrayList<>();
    private PcBoard.Post cover, links;
    private final ArrayList<ArrayList<PcBoard.Post>> grid = new ArrayList<>();
    private boolean loading = true;

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcMyProfile);
    }

    @Override
    public boolean onFragmentCreate() {
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return v;
    }

    private void reload() {
        loading = true;
        final long me = UserConfig.getInstance(currentAccount).getClientUserId();
        PcBoard.loadFeed(currentAccount, list -> {
            loading = false;
            mine.clear();
            grid.clear();
            cover = null;
            links = null;
            ArrayList<PcBoard.Post> g = null;
            for (PcBoard.Post p : list) {
                if (p.authorId != me) {
                    continue;
                }
                if (PcBoard.isTagged(p, PcBoard.TAG_COVER)) {
                    if (cover == null) cover = p;
                    continue;
                }
                if (PcBoard.isTagged(p, PcBoard.TAG_LINKS)) {
                    if (links == null) links = p;
                    continue;
                }
                mine.add(p);
                if (PcBoard.hasMedia(p)) {
                    if (g == null || g.size() == 3) {
                        g = new ArrayList<>();
                        grid.add(g);
                    }
                    g.add(p);
                }
            }
            notifyData();
        });
        notifyData();
    }

    @Override
    protected int itemCount() {
        return 3 + grid.size() + 1;
    }

    @Override
    protected int itemViewType(int position) {
        if (position == 0) return T_COVER;
        if (position == 1) return T_LINKS;
        if (position == 2) return T_EDIT;
        return position - 3 < grid.size() ? T_GRID : T_NOTE;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    private LinearLayout card(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return box;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_NOTE) {
            TextView t = PcUi.label(context, "", 13, false, Theme.key_windowBackgroundWhiteGrayText);
            t.setTypeface(PcUi.serif(false, true));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(24), dp(24), dp(96));
            t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return t;
        }
        if (viewType == T_GRID) {
            LinearLayout row = new LinearLayout(context);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            row.setPadding(dp(1), dp(1), dp(1), dp(1));
            return row;
        }
        return card(context);
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        final Context context = row.getContext();
        if (viewType == T_NOTE) {
            ((TextView) row).setText(LocaleController.getString(loading ? R.string.PcFeedLoading : (PcBoard.isEnabled(currentAccount) ? (mine.isEmpty() ? R.string.PcFeedEmpty : R.string.PcMyGridNote) : R.string.PcMyBoardOff)));
            return;
        }
        if (viewType == T_GRID) {
            LinearLayout r = (LinearLayout) row;
            r.removeAllViews();
            ArrayList<PcBoard.Post> items = grid.get(position - 3);
            final int size = (AndroidUtilities.displaySize.x - dp(2)) / 3 - dp(2);
            for (int i = 0; i < 3; i++) {
                BackupImageView img = new BackupImageView(context);
                img.setBackgroundColor(0xFF1A1A1A);
                if (i < items.size()) {
                    PcBoard.setMedia(img, items.get(i), 400);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                r.addView(img, lp);
            }
            return;
        }
        LinearLayout box = (LinearLayout) row;
        box.removeAllViews();
        if (viewType == T_COVER) {
            TextView h = PcUi.label(context, LocaleController.getString(R.string.PcMyCover), 13, true, Theme.key_windowBackgroundWhiteGrayText);
            h.setLetterSpacing(0.12f);
            box.addView(h);
            BackupImageView img = new BackupImageView(context);
            img.setBackgroundColor(0xFF1A1A1A);
            img.setRoundRadius(dp(10));
            if (cover != null) {
                PcBoard.setMedia(img, cover, 800);
            }
            box.addView(img, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160, 0, 8, 0, 8));
            box.addView(PcUi.button(context, LocaleController.getString(R.string.PcMyCoverChange), false, v -> pickCover()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));
        } else if (viewType == T_LINKS) {
            TextView h = PcUi.label(context, LocaleController.getString(R.string.PcMyLinks), 13, true, Theme.key_windowBackgroundWhiteGrayText);
            h.setLetterSpacing(0.12f);
            box.addView(h);
            String cur = PcBoard.linksOf(links);
            TextView t = PcUi.label(context, TextUtils.isEmpty(cur) ? LocaleController.getString(R.string.PcMyLinksNone) : cur, 15, false, Theme.key_windowBackgroundWhiteBlackText);
            t.setTypeface(PcUi.serif(false, true));
            box.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8));
            box.addView(PcUi.button(context, LocaleController.getString(R.string.PcMyLinksEdit), false, v -> editLinks(cur)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));
        } else if (viewType == T_EDIT) {
            box.addView(PcUi.button(context, LocaleController.getString(R.string.PcSetEditProfile), true, v -> presentFragment(new UserInfoActivity())), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        }
    }

    private boolean needBoard() {
        if (!PcBoard.isEnabled(currentAccount)) {
            PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcMyBoardOff));
            return true;
        }
        return false;
    }

    private void pickCover() {
        if (needBoard()) {
            return;
        }
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, REQ_COVER);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQ_COVER || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            String path = null;
            try (java.io.InputStream in = org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                java.io.File f = new java.io.File(org.telegram.messenger.ApplicationLoader.applicationContext.getCacheDir(), "pc_cover_" + System.currentTimeMillis() + ".jpg");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                path = f.getAbsolutePath();
            } catch (Throwable ignore) {
            }
            final String fp = path;
            AndroidUtilities.runOnUIThread(() -> {
                if (fp == null) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
                    return;
                }
                org.telegram.messenger.SendMessagesHelper.SendingMediaInfo info = new org.telegram.messenger.SendMessagesHelper.SendingMediaInfo();
                info.path = fp;
                info.caption = PcBoard.TAG_COVER;
                ArrayList<org.telegram.messenger.SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
                list.add(info);
                org.telegram.messenger.SendMessagesHelper.prepareSendingMedia(org.telegram.messenger.AccountInstance.getInstance(currentAccount), list, -PcBoard.myBoardChat(currentAccount), null, null, null, null,
                    false, false, null, true, 0, 0, 0, false, null, org.telegram.messenger.SendMessageChatArguments.EMPTY, 0, false, 0, 0, null);
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcMyCoverSaved));
                AndroidUtilities.runOnUIThread(this::reload, 6000);
            });
        });
    }

    private void editLinks(String current) {
        if (needBoard() || getParentActivity() == null) {
            return;
        }
        final EditText edit = new EditText(getParentActivity());
        edit.setHint(LocaleController.getString(R.string.PcMyLinksHint));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setMinLines(3);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setText(current);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcMyLinksEdit));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardPublish), (d, w) -> {
            String t = edit.getText().toString().trim();
            if (!t.isEmpty()) {
                PcBoard.publish(currentAccount, PcBoard.TAG_LINKS + "\n" + t);
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcMyLinksSaved));
                AndroidUtilities.runOnUIThread(this::reload, 3000);
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }
}
