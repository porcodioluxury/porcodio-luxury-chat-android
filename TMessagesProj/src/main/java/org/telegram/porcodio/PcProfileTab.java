package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Profilo (quinta scheda): "+" / username / ☰ in alto, avatar con anello, contatori, nome, bio, link,
 * card dashboard (visualizzazioni 30 giorni), Modifica/Condividi profilo e le tab griglia, reel, ripubblicati, taggati.
 * Le Impostazioni si aprono da ☰.
 */
public class PcProfileTab extends PcListFragment implements org.telegram.ui.MainTabsActivity.TabFragmentDelegate {
    private static final int T_HEAD = 0, T_DASH = 1, T_BUTTONS = 2, T_TABS = 3, T_GRID = 4, T_NOTE = 5;

    private final ArrayList<PcBoard.Post> mine = new ArrayList<>();
    private PcBoard.Post links;
    private final ArrayList<ArrayList<PcBoard.Post>> grid = new ArrayList<>();
    private int tab; // 0 griglia, 1 reel, 2 ripubblicati, 3 taggati
    private boolean loading = true;
    private long views30;

    public PcProfileTab(Bundle args) {
        super(args);
    }

    @Override
    protected String screenTitle() {
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        String u = me != null ? UserObject.getPublicUsername(me) : null;
        if (!TextUtils.isEmpty(u)) {
            return u;
        }
        return me != null ? UserObject.getUserName(me) : "";
    }

    @Override
    public boolean onFragmentCreate() {
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(0xFF121212);
        if (simpleActionBar != null) {
            simpleActionBar.setBackButtonImage(R.drawable.ic_pc_plus);
            simpleActionBar.getTitleTextView().setTypeface(PcUi.serif(true, false));
            simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        Bundle a = new Bundle();
                        a.putString("pc_mode", "home");
                        a.putBoolean("pc_open_plus", true);
                        presentFragment(new PcFeedActivity(a));
                    } else if (id == 1) {
                        Bundle a = new Bundle();
                        a.putBoolean("settings_menu", true);
                        presentFragment(new PcSettingsActivity(a));
                    }
                }
            });
            simpleActionBar.createMenu().addItem(1, R.drawable.ic_pc_menu);
        }
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    private void reload() {
        final long me = UserConfig.getInstance(currentAccount).getClientUserId();
        PcBoard.loadFeed(currentAccount, 60, true, list -> {
            loading = false;
            mine.clear();
            links = null;
            views30 = 0;
            final int since = (int) (System.currentTimeMillis() / 1000L) - 30 * 86400;
            for (PcBoard.Post p : list) {
                if (p.authorId != me) {
                    continue;
                }
                if (PcBoard.isTagged(p, PcBoard.TAG_LINKS)) {
                    if (links == null) links = p;
                    continue;
                }
                if (PcBoard.isTagged(p, PcBoard.TAG_COVER)) {
                    continue;
                }
                mine.add(p);
                if (p.date >= since) {
                    views30 += Math.max(0, p.views);
                }
            }
            rebuildGrid();
            notifyData();
        });
        rebuildGrid();
        notifyData();
    }

    private void rebuildGrid() {
        grid.clear();
        if (tab == 2 || tab == 3) {
            return;
        }
        ArrayList<PcBoard.Post> g = null;
        for (PcBoard.Post p : mine) {
            if (!PcBoard.hasMedia(p) || (tab == 1 && !isReel(p))) {
                continue;
            }
            if (g == null || g.size() == 3) {
                g = new ArrayList<>();
                grid.add(g);
            }
            g.add(p);
        }
    }

    private static boolean isReel(PcBoard.Post p) {
        return PcBoard.isVideoPost(p) || (p.text != null && p.text.toLowerCase(java.util.Locale.ROOT).contains("#reel"));
    }

    @Override
    protected int itemCount() {
        return 4 + grid.size() + 1;
    }

    @Override
    protected int itemViewType(int position) {
        if (position < 4) {
            return position;
        }
        return position - 4 < grid.size() ? T_GRID : T_NOTE;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    private TextView text(Context context, String t, float size, boolean bold, int color) {
        TextView v = new TextView(context);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) {
            v.setTypeface(AndroidUtilities.bold());
        }
        return v;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_GRID) {
            LinearLayout r = new LinearLayout(context);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            r.setPadding(dp(1), dp(1), dp(1), dp(1));
            return r;
        }
        if (viewType == T_NOTE) {
            TextView t = text(context, "", 13, false, 0xFFBFBFBF);
            t.setTypeface(PcUi.serif(false, true));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(24), dp(24), dp(org.telegram.ui.DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 24));
            t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return t;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return box;
    }

    private String count(long n) {
        if (n >= 1000000) {
            return String.format(java.util.Locale.getDefault(), "%.1f mln", n / 1000000f);
        }
        if (n >= 10000) {
            return String.format(java.util.Locale.getDefault(), "%.1f mila", n / 1000f);
        }
        return String.valueOf(n);
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        final Context context = row.getContext();
        if (viewType == T_GRID) {
            LinearLayout r = (LinearLayout) row;
            r.removeAllViews();
            final int size = (AndroidUtilities.displaySize.x - dp(2)) / 3 - dp(2);
            ArrayList<PcBoard.Post> items = grid.get(position - 4);
            for (int i = 0; i < 3; i++) {
                FrameLayout cell = new FrameLayout(context);
                cell.setBackgroundColor(0xFF1A1A1A);
                if (i < items.size()) {
                    final PcBoard.Post p = items.get(i);
                    BackupImageView img = new BackupImageView(context);
                    PcBoard.setMedia(img, p, 400);
                    cell.addView(img, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                    LinearLayout views = new LinearLayout(context);
                    views.setGravity(Gravity.CENTER_VERTICAL);
                    ImageView eye = new ImageView(context);
                    eye.setImageResource(R.drawable.ic_pc_eye);
                    eye.setColorFilter(0xFFF2F2F2);
                    views.addView(eye, LayoutHelper.createLinear(16, 16));
                    views.addView(text(context, count(Math.max(0, p.views)), 12, true, 0xFFF2F2F2), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 0));
                    cell.addView(views, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 6, 0, 0, 6));
                    if (PcBoard.isVideoPost(p)) {
                        ImageView play = new ImageView(context);
                        play.setImageResource(R.drawable.ic_pc_tab_reels);
                        play.setColorFilter(0xFFF2F2F2);
                        cell.addView(play, LayoutHelper.createFrame(18, 18, Gravity.TOP | Gravity.RIGHT, 0, 6, 6, 0));
                    }
                    cell.setOnClickListener(v -> {
                        Bundle b = new Bundle();
                        b.putLong("chat_id", p.chatId);
                        b.putInt("message_id", p.msgId);
                        presentFragment(new ChatActivity(b));
                    });
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                r.addView(cell, lp);
            }
            return;
        }
        if (viewType == T_NOTE) {
            TextView t = (TextView) row;
            if (tab == 2) {
                t.setText(LocaleController.getString(R.string.PcProfileRepostsEmpty));
            } else if (tab == 3) {
                t.setText(LocaleController.getString(R.string.PcProfileTaggedEmpty));
            } else if (loading) {
                t.setText(LocaleController.getString(R.string.PcFeedLoading));
            } else if (grid.isEmpty()) {
                t.setText(LocaleController.getString(PcBoard.isEnabled(currentAccount) ? R.string.PcProfileNoPosts : R.string.PcProfileBoardOff));
            } else {
                t.setText("");
            }
            return;
        }
        LinearLayout box = (LinearLayout) row;
        box.removeAllViews();
        final TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (viewType == T_HEAD) {
            box.setPadding(dp(16), dp(8), dp(16), dp(8));
            LinearLayout top = new LinearLayout(context);
            top.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout ring = new FrameLayout(context);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(0);
            g.setStroke(dp(2.5f), 0xFF9DAAC8);
            ring.setBackground(g);
            BackupImageView av = new BackupImageView(context);
            av.setRoundRadius(dp(40));
            AvatarDrawable ad = new AvatarDrawable();
            if (me != null) {
                ad.setInfo(currentAccount, me);
                av.setForUserOrChat(me, ad);
            }
            ring.addView(av, LayoutHelper.createFrame(80, 80, Gravity.CENTER));
            ring.setOnClickListener(v -> presentFragment(new org.telegram.ui.UserInfoActivity()));
            top.addView(ring, LayoutHelper.createLinear(92, 92));
            LinearLayout stats = new LinearLayout(context);
            stats.setWeightSum(3);
            long followers = 0;
            long chat = PcBoard.myBoardChat(currentAccount);
            TLRPC.Chat ch = chat != 0 ? getMessagesController().getChat(chat) : null;
            if (ch != null) {
                followers = ch.participants_count;
            }
            stats.addView(stat(context, String.valueOf(mine.size()), LocaleController.getString(R.string.PcProfilePosts)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            stats.addView(stat(context, count(followers), LocaleController.getString(R.string.PcProfileFollowers)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            stats.addView(stat(context, String.valueOf(PcBoard.followedCount()), LocaleController.getString(R.string.PcProfileFollowing)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            top.addView(stats, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
            box.addView(top);
            TextView name = text(context, me != null ? UserObject.getUserName(me) : "", 16, true, 0xFFF2F2F2);
            box.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            TLRPC.UserFull full = me != null ? getMessagesController().getUserFull(me.id) : null;
            String about = full != null && full.about != null ? full.about.replace(PcBoard.MARKER, "").trim() : "";
            if (!about.isEmpty()) {
                box.addView(text(context, about, 14, false, 0xFFF2F2F2), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            }
            String lk = PcBoard.linksOf(links);
            if (!lk.isEmpty()) {
                TextView l = text(context, lk, 14, true, 0xFF9DAAC8);
                l.setMaxLines(2);
                box.addView(l, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            }
            // chip: Wallet / Shop / Aggiungi
            android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(context);
            sv.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(context);
            chips.addView(PcFeedActivity.chip(context, "Shop porcodioluxury.com", false, v -> presentFragment(new PcShopActivity(new Bundle()))), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
            chips.addView(PcFeedActivity.chip(context, "+ " + LocaleController.getString(R.string.PcProfileAddLink), false, v -> presentFragment(new PcProfileEditActivity())), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
            sv.addView(chips);
            box.addView(sv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        } else if (viewType == T_DASH) {
            box.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(PcFeedActivity.pill(0xFF1A1A1A, 0).mutate());
            ((android.graphics.drawable.GradientDrawable) card.getBackground()).setCornerRadius(dp(12));
            card.addView(text(context, LocaleController.getString(R.string.PcProfileDashboard), 15, true, 0xFFF2F2F2));
            card.addView(text(context, LocaleController.formatString(R.string.PcProfileViews30, count(views30)), 13, false, 0xFFBFBFBF), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            box.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else if (viewType == T_BUTTONS) {
            box.setPadding(dp(14), dp(6), dp(14), dp(10));
            LinearLayout r = new LinearLayout(context);
            TextView edit = actionButton(context, LocaleController.getString(R.string.PcSetEditProfile));
            edit.setOnClickListener(v -> presentFragment(new PcProfileEditActivity()));
            TextView share = actionButton(context, LocaleController.getString(R.string.PcProfileShare));
            share.setOnClickListener(v -> shareProfile());
            r.addView(edit, LayoutHelper.createLinear(0, 38, 1f, 0, 0, 4, 0));
            r.addView(share, LayoutHelper.createLinear(0, 38, 1f, 4, 0, 0, 0));
            box.addView(r);
        } else if (viewType == T_TABS) {
            LinearLayout r = new LinearLayout(context);
            int[] icons = {R.drawable.ic_pc_grid3, R.drawable.ic_pc_tab_reels, R.drawable.ic_pc_repost, R.drawable.ic_pc_tagged};
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                FrameLayout f = new FrameLayout(context);
                ImageView iv = new ImageView(context);
                iv.setImageResource(icons[i]);
                iv.setColorFilter(tab == i ? 0xFFF2F2F2 : 0xFF8A8F9C);
                f.addView(iv, LayoutHelper.createFrame(26, 26, Gravity.CENTER, 0, 0, 0, 2));
                if (tab == i) {
                    View line = new View(context);
                    line.setBackgroundColor(0xFFF2F2F2);
                    f.addView(line, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.BOTTOM));
                }
                f.setOnClickListener(v -> {
                    tab = idx;
                    rebuildGrid();
                    notifyData();
                });
                r.addView(f, LayoutHelper.createLinear(0, 46, 1f));
            }
            box.addView(r);
        }
    }

    private TextView actionButton(Context context, String t) {
        TextView v = text(context, t, 14, true, 0xFFF2F2F2);
        v.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF242833);
        g.setCornerRadius(dp(10));
        v.setBackground(g);
        return v;
    }

    private View stat(Context context, String n, String label) {
        LinearLayout c = new LinearLayout(context);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(text(context, n, 17, true, 0xFFF2F2F2));
        c.addView(text(context, label, 13, false, 0xFFF2F2F2));
        return c;
    }

    private void shareProfile() {
        if (getParentActivity() == null) {
            return;
        }
        final TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        String u = me != null ? UserObject.getPublicUsername(me) : null;
        String link = !TextUtils.isEmpty(u) ? "https://t.me/" + u : "https://t.me/" + PcBoard.username(UserConfig.getInstance(currentAccount).getClientUserId());
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(android.content.Intent.EXTRA_TEXT, link);
        getParentActivity().startActivity(android.content.Intent.createChooser(i, LocaleController.getString(R.string.PcProfileShare)));
    }
}
