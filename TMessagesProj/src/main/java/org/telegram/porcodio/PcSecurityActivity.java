package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Sicurezza dei messaggi: blocca gruppi sconosciuti, richieste di contatto, chi può scrivermi (vedi PcPolicy). */
public class PcSecurityActivity extends PcListFragment {
    private static final int T_SECTION = 0, T_ROW = 1, T_NOTE = 2;

    private static class Item {
        int type;
        String title, value;
        Runnable click;
    }

    private final ArrayList<Item> items = new ArrayList<>();

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcSecTitle);
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
        return position >= 0 && position < items.size() && items.get(position).type == T_ROW;
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        build();
        return v;
    }

    private static String s(int res) {
        return LocaleController.getString(res);
    }

    private void section(int res) {
        Item i = new Item();
        i.type = T_SECTION;
        i.title = s(res);
        items.add(i);
    }

    private void note(int res) {
        Item i = new Item();
        i.type = T_NOTE;
        i.title = s(res);
        items.add(i);
    }

    private void row(int titleRes, String value, Runnable click) {
        Item i = new Item();
        i.type = T_ROW;
        i.title = s(titleRes);
        i.value = value;
        i.click = click;
        items.add(i);
    }

    private void build() {
        items.clear();
        section(R.string.PcSecGroups);
        row(R.string.PcSecBlockGroups, s(PcPolicy.blockGroups() ? R.string.PcSecOn : R.string.PcSecOff), () -> {
            PcPolicy.setBlockGroups(currentAccount, !PcPolicy.blockGroups());
            build();
        });
        row(R.string.PcSecInvite, s(PcPolicy.groupInvite() == PcPolicy.INVITE_NOBODY ? R.string.PcSecInviteNobody : R.string.PcSecInviteContacts), () ->
            choose(R.string.PcSecInvite, new int[]{R.string.PcSecInviteContacts, R.string.PcSecInviteNobody}, w -> PcPolicy.setGroupInvite(currentAccount, w)));
        row(R.string.PcSecAutoLeave, s(PcPolicy.autoLeave() ? R.string.PcSecOn : R.string.PcSecOff), () -> {
            if (PcPolicy.autoLeave()) {
                PcPolicy.setAutoLeave(false);
                build();
                return;
            }
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(s(R.string.PcSecAutoLeave));
            b.setMessage(s(R.string.PcSecAutoLeaveWarn));
            b.setPositiveButton(s(R.string.PcSecTurnOn), (d, w) -> {
                PcPolicy.setAutoLeave(true);
                build();
            });
            b.setNegativeButton(s(R.string.Cancel), null);
            showDialog(b.create());
        });
        note(R.string.PcSecGroupsHelp);

        section(R.string.PcSecRequests);
        row(R.string.PcSecReqWho, s(PcPolicy.reqMode() == PcPolicy.REQ_ALL ? R.string.PcSecReqAll : PcPolicy.reqMode() == PcPolicy.REQ_CONTACTS_OF_CONTACTS ? R.string.PcSecReqCoc : R.string.PcSecReqNobody), () ->
            choose(R.string.PcSecReqWho, new int[]{R.string.PcSecReqAll, R.string.PcSecReqCoc, R.string.PcSecReqNobody}, w -> PcPolicy.setReqMode(currentAccount, w)));
        row(R.string.PcSecOpenRequests, "", () -> presentFragment(new PcRequestsActivity()));
        note(R.string.PcSecReqHelp);

        section(R.string.PcSecWrite);
        int wm = PcPolicy.writeMode();
        row(R.string.PcSecWriteWho, s(wm == PcPolicy.WRITE_ALL ? R.string.PcSecWriteAll : wm == PcPolicy.WRITE_CONTACTS ? R.string.PcSecWriteContacts : wm == PcPolicy.WRITE_VERIFIED ? R.string.PcSecWriteVerified : R.string.PcSecWriteNobody), () ->
            choose(R.string.PcSecWriteWho, new int[]{R.string.PcSecWriteAll, R.string.PcSecWriteContacts, R.string.PcSecWriteVerified, R.string.PcSecWriteNobody}, w -> PcPolicy.setWriteMode(currentAccount, w)));
        note(R.string.PcSecWriteHelp);
        notifyData();
    }

    private interface Pick {
        void on(int which);
    }

    private void choose(int titleRes, int[] options, Pick pick) {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] labels = new CharSequence[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = s(options[i]);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s(titleRes));
        b.setItems(labels, (d, which) -> {
            pick.on(which);
            build();
        });
        b.setNegativeButton(s(R.string.Cancel), null);
        showDialog(b.create());
    }

    @Override
    protected void onRowClick(View view, int position) {
        if (position >= 0 && position < items.size() && items.get(position).click != null) {
            items.get(position).click.run();
        }
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_ROW) {
            FrameLayout f = new FrameLayout(context);
            f.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, dp(56)));
            f.setBackground(Theme.getSelectorDrawable(false));
            TextView t = new TextView(context);
            t.setTag("t");
            t.setTextSize(16);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            f.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 150, 0));
            TextView v = new TextView(context);
            v.setTag("v");
            v.setTextSize(14);
            v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            v.setGravity(Gravity.RIGHT);
            f.addView(v, LayoutHelper.createFrame(140, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            return f;
        }
        TextView t = new TextView(context);
        t.setPadding(dp(16), dp(viewType == T_SECTION ? 16 : 6), dp(16), dp(8));
        t.setTextSize(viewType == T_SECTION ? 13 : 13);
        if (viewType == T_SECTION) {
            t.setTypeface(AndroidUtilities.bold());
            t.setAllCaps(true);
        }
        t.setTextColor(Theme.getColor(viewType == T_SECTION ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        t.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
        return t;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Item it = items.get(position);
        if (viewType == T_ROW) {
            ((TextView) row.findViewWithTag("t")).setText(it.title);
            ((TextView) row.findViewWithTag("v")).setText(it.value);
        } else {
            ((TextView) row).setText(it.title);
        }
    }
}
