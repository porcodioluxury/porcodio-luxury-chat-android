package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Riga orizzontale di preferiti in cima a Private: contatti con chat fissata, poi i contatti più frequenti
 * (top peers). Avatar quadrati con angoli a 4dp, nome sotto.
 */
public class PcFavoritesRow extends FrameLayout {
    private static final int MAX = 12;
    private final int currentAccount;
    private final BaseFragment fragment;
    private final LinearLayout row;
    private final ArrayList<Long> ids = new ArrayList<>();

    public PcFavoritesRow(Context context, int currentAccount, BaseFragment fragment) {
        super(context);
        this.currentAccount = currentAccount;
        this.fragment = fragment;
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(74), MeasureSpec.EXACTLY));
    }

    /** @return true se c'è almeno un preferito da mostrare */
    public boolean update() {
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
            if (d.pinned && d.folder_id == 0 && d.id > 0 && set.size() < MAX) {
                TLRPC.User u = mc.getUser(d.id);
                if (u != null && !u.self && !u.bot && !UserObject.isDeleted(u)) {
                    set.add(d.id);
                }
            }
        }
        ArrayList<TLRPC.TL_topPeer> hints = MediaDataController.getInstance(currentAccount).hints;
        for (int i = 0; i < hints.size() && set.size() < MAX; i++) {
            TLRPC.TL_topPeer p = hints.get(i);
            if (p.peer instanceof TLRPC.TL_peerUser) {
                long uid = p.peer.user_id;
                TLRPC.User u = mc.getUser(uid);
                if (u != null && !u.self && !u.bot && !UserObject.isDeleted(u) && (u.contact || u.mutual_contact)) {
                    set.add(uid);
                }
            }
        }
        ArrayList<Long> next = new ArrayList<>(set);
        if (!next.equals(ids)) {
            ids.clear();
            ids.addAll(next);
            row.removeAllViews();
            for (long id : ids) {
                row.addView(item(getContext(), id), LayoutHelper.createLinear(64, LayoutHelper.MATCH_PARENT, 0, 0, 4, 0));
            }
        }
        return !ids.isEmpty();
    }

    private View item(Context context, long userId) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(userId);
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(40));
        AvatarDrawable ad = new AvatarDrawable();
        ad.setInfo(currentAccount, user);
        avatar.setForUserOrChat(user, ad);
        cell.addView(avatar, LayoutHelper.createLinear(46, 46, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        TextView name = new TextView(context);
        name.setTextSize(11);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        name.setText(UserObject.getFirstName(user));
        cell.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 2, 4, 2, 0));
        cell.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putLong("user_id", userId);
            fragment.presentFragment(new ChatActivity(args));
        });
        return cell;
    }
}
