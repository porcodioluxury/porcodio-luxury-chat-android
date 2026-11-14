package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Controlli dell'utente sull'algoritmo: feed Per te / Seguiti, argomenti (più / meno), azzeramento. Tutto sul dispositivo. */
public class PcInterestsActivity extends PcListFragment {
    private static final int T_HEAD = 0, T_TOPIC = 1, T_NOTE = 2;
    private ArrayList<Object[]> top = new ArrayList<>();

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcInterestsTitle);
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        reload();
        return v;
    }

    private void reload() {
        top = PcInterests.topTopics(40);
        notifyData();
    }

    @Override
    protected int itemCount() {
        return 1 + top.size() + (top.isEmpty() ? 1 : 0);
    }

    @Override
    protected int itemViewType(int position) {
        if (position == 0) return T_HEAD;
        return position - 1 < top.size() ? T_TOPIC : T_NOTE;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        return box;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        final Context context = row.getContext();
        LinearLayout box = (LinearLayout) row;
        box.removeAllViews();
        if (viewType == T_HEAD) {
            TextView t = PcUi.label(context, LocaleController.getString(R.string.PcInterestsHow), 14, false, Theme.key_windowBackgroundWhiteBlackText);
            t.setTypeface(PcUi.serif(false, true));
            t.setLineSpacing(0, 1.15f);
            box.addView(t);
            box.addView(PcUi.button(context, LocaleController.getString(R.string.PcInterestsReset), false, v -> {
                if (getParentActivity() == null) return;
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(LocaleController.getString(R.string.PcInterestsReset));
                b.setMessage(LocaleController.getString(R.string.PcInterestsResetAsk));
                b.setPositiveButton(LocaleController.getString(R.string.PcInterestsReset), (d, w) -> {
                    PcInterests.reset();
                    reload();
                });
                b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                showDialog(b.create());
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 14, 0, 0));
        } else if (viewType == T_NOTE) {
            TextView t = PcUi.label(context, LocaleController.getString(R.string.PcInterestsEmpty), 13, false, Theme.key_windowBackgroundWhiteGrayText);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(PcUi.serif(false, true));
            box.addView(t);
        } else {
            final Object[] e = top.get(position - 1);
            final String topic = (String) e[0];
            final double w = (Double) e[1];
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = PcUi.label(context, "#" + topic, 16, true, Theme.key_windowBackgroundWhiteBlackText);
            name.setTypeface(PcUi.serif(true, false));
            box.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            TextView val = PcUi.label(context, String.format("%+.1f", w), 13, false, Theme.key_windowBackgroundWhiteGrayText);
            box.addView(val, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
            box.addView(PcUi.button(context, "\u2212", false, v -> {
                PcInterests.adjustTopic(topic, -4);
                reload();
            }), LayoutHelper.createLinear(44, 36, 0, 0, 6, 0));
            box.addView(PcUi.button(context, "+", false, v -> {
                PcInterests.adjustTopic(topic, 4);
                reload();
            }), LayoutHelper.createLinear(44, 36));
        }
    }
}
