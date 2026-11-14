package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Gestione delle risposte rapide: aggiungi in alto, tocca una risposta per eliminarla. */
public class PcQuickActivity extends PcListFragment {
    private static final int T_ADD = 0, T_ITEM = 1, T_NOTE = 2;
    private ArrayList<String> items = PcQuick.list();

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcQuickReplies);
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return v;
    }

    private void refresh() {
        items = PcQuick.list();
        notifyData();
    }

    @Override
    protected int itemCount() {
        return 1 + items.size() + (items.isEmpty() ? 1 : 0);
    }

    @Override
    protected int itemViewType(int position) {
        if (position == 0) {
            return T_ADD;
        }
        return position - 1 < items.size() ? T_ITEM : T_NOTE;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_NOTE) {
            TextView t = PcUi.label(context, LocaleController.getString(R.string.PcQuickEmpty), 14, false, Theme.key_windowBackgroundWhiteGrayText);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(24), dp(24), dp(24));
            t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return t;
        }
        FrameLayout wrap = new FrameLayout(context);
        wrap.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        if (viewType == T_ADD) {
            TextView b = PcUi.button(context, LocaleController.getString(R.string.PcQuickAdd), true, v -> PcQuick.askNew(this, this::refresh));
            wrap.addView(b, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 12, 8, 12, 8));
        } else {
            LinearLayout box = new LinearLayout(context);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            box.setBackground(PcUi.panel(true));
            TextView text = PcUi.label(context, "", 15, false, Theme.key_windowBackgroundWhiteBlackText);
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setTag("t");
            box.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            TextView del = PcUi.label(context, LocaleController.getString(R.string.PcQuickDelete).toUpperCase(), 11, true, Theme.key_windowBackgroundWhiteGrayText);
            del.setPadding(dp(10), dp(6), dp(2), dp(6));
            del.setTag("d");
            box.addView(del, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            wrap.addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 4, 12, 4));
        }
        return wrap;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        if (viewType != T_ITEM) {
            return;
        }
        final int index = position - 1;
        ((TextView) row.findViewWithTag("t")).setText(items.get(index));
        row.findViewWithTag("d").setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(LocaleController.getString(R.string.PcQuickDelete));
            b.setMessage(items.get(index));
            b.setPositiveButton(LocaleController.getString(R.string.PcQuickDelete), (d, w) -> {
                PcQuick.remove(index);
                refresh();
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b.create());
        });
    }
}
