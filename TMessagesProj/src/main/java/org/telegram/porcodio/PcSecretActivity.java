package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Elenco delle chat super segrete e accesso alla cartella nascosta (con impronta). */
public class PcSecretActivity extends PcListFragment {
    private static final int T_HEAD = 0, T_ROW = 1, T_NOTE = 2;
    private ArrayList<Long> ids = new ArrayList<>();
    private boolean hiddenUnlocked;

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcSetSecretChats);
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        reload();
        return v;
    }

    private void reload() {
        ids.clear();
        for (long id : PcSecret.enabledDialogs(currentAccount)) {
            if (!PcSecret.isHidden(id) || hiddenUnlocked) {
                ids.add(id);
            }
        }
        notifyData();
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onFragmentDestroy() {
        PcSecret.showHidden = false;
        PcHome.postReload(currentAccount);
        super.onFragmentDestroy();
    }

    @Override
    protected int itemCount() {
        return 1 + ids.size() + (ids.isEmpty() ? 1 : 0);
    }

    @Override
    protected int itemViewType(int position) {
        if (position == 0) {
            return T_HEAD;
        }
        return position - 1 < ids.size() ? T_ROW : T_NOTE;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return itemViewType(position) == T_ROW;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_NOTE) {
            TextView t = PcUi.label(context, LocaleController.getString(R.string.PcSecretEmpty), 14, false, Theme.key_windowBackgroundWhiteGrayText);
            t.setTypeface(PcUi.serif(false, true));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(28), dp(28), dp(28), dp(28));
            t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return t;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), dp(14));
        box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        TextView title = PcUi.label(context, "", viewType == T_HEAD ? 14 : 17, viewType == T_ROW, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(PcUi.serif(viewType == T_ROW, viewType == T_HEAD));
        title.setTag("t");
        TextView sub = PcUi.label(context, "", 13, false, Theme.key_windowBackgroundWhiteGrayText);
        sub.setTypeface(PcUi.serif(false, true));
        sub.setTag("s");
        box.addView(title, LayoutHelper.createLinear(-1, -2));
        box.addView(sub, LayoutHelper.createLinear(-1, -2, 0, 3, 0, 0));
        return box;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        TextView t = row.findViewWithTag("t");
        TextView s = row.findViewWithTag("s");
        if (viewType == T_HEAD) {
            t.setText(LocaleController.getString(R.string.PcSetSecretChatsHow));
            t.setLineSpacing(0, 1.15f);
            s.setText(hiddenUnlocked ? LocaleController.getString(R.string.PcSecretHiddenShown) : LocaleController.getString(R.string.PcSecretShowHidden));
            s.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            s.setPadding(0, dp(12), 0, 0);
            row.setOnClickListener(v -> {
                if (hiddenUnlocked) {
                    return;
                }
                if (!PcLock.isAvailable()) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcProfileLockUnavailable));
                    return;
                }
                PcLock.authenticate(0, () -> {
                    hiddenUnlocked = true;
                    reload();
                }, null);
            });
            return;
        }
        if (viewType != T_ROW) {
            return;
        }
        final long id = ids.get(position - 1);
        String name = "" + id;
        if (id > 0) {
            TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(id);
            if (u != null) {
                name = UserObject.getUserName(u);
            }
        }
        t.setText(name);
        StringBuilder sb = new StringBuilder(LocaleController.getString(PcSecret.hasKey(currentAccount, id) ? R.string.PcSecretKeyReady : R.string.PcSecretWaitKey));
        if (PcSecret.ttl(currentAccount, id) > 0) {
            sb.append("  \u00B7  \u23F3");
        }
        if (PcLock.isLocked(id)) {
            sb.append("  \u00B7  \uD83D\uDD12");
        }
        if (PcSecret.isHidden(id)) {
            sb.append("  \u00B7  ").append(LocaleController.getString(R.string.PcSecretHiddenTag));
        }
        s.setText(sb);
        row.setOnClickListener(v -> open(id));
    }

    @Override
    protected void onRowClick(View view, int position) {
        // gestito dai click delle righe
    }

    private void open(long id) {
        final Runnable go = () -> {
            Bundle args = new Bundle();
            if (id > 0) {
                args.putLong("user_id", id);
            } else {
                args.putLong("chat_id", -id);
            }
            presentFragment(new ChatActivity(args));
        };
        if (PcLock.isLocked(id) && !PcLock.recentlyUnlocked(id)) {
            PcLock.authenticate(id, go, null);
        } else {
            go.run();
        }
    }
}
