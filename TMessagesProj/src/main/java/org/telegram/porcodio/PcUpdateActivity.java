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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Locale;

/** Impostazioni > Aggiornamenti: versione installata, esito, "Cerca aggiornamenti", "Aggiorna ora" e aggiornamento automatico. */
public class PcUpdateActivity extends PcListFragment implements PcUpdater.Listener {
    private static final int T_ROW = 0, T_NOTE = 1;

    private static class Item {
        int type;
        String title, value;
        Runnable click;
    }

    private final ArrayList<Item> items = new ArrayList<>();

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcUpdScreenTitle);
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
        return position >= 0 && position < items.size() && items.get(position).click != null;
    }

    @Override
    public boolean onFragmentCreate() {
        PcUpdater.addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PcUpdater.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        build();
        return v;
    }

    @Override
    public void onUpdaterChanged() {
        build();
    }

    private static String s(int r) {
        return LocaleController.getString(r);
    }

    private void row(String title, String value, Runnable click) {
        Item i = new Item();
        i.type = T_ROW;
        i.title = title;
        i.value = value;
        i.click = click;
        items.add(i);
    }

    private void note(String t) {
        Item i = new Item();
        i.type = T_NOTE;
        i.title = t;
        items.add(i);
    }

    private void build() {
        items.clear();
        row(LocaleController.formatString(R.string.PcUpdCurrent, PcUpdater.currentName()), "", null);
        String status;
        switch (PcUpdater.state) {
            case CHECKING: status = s(R.string.PcUpdChecking); break;
            case AVAILABLE: status = LocaleController.formatString(R.string.PcUpdStatusAvailable, PcUpdater.available != null ? PcUpdater.available.versionName : ""); break;
            case DOWNLOADING: status = LocaleController.formatString(R.string.PcUpdDownloading, PcUpdater.progress); break;
            case READY: status = LocaleController.formatString(R.string.PcUpdReadyText, PcUpdater.available != null ? PcUpdater.available.versionName : ""); break;
            case ERROR: status = PcUpdater.error == null ? s(R.string.PcUpdFailed) : PcUpdater.error; break;
            default: status = LocaleController.formatString(R.string.PcUpdUpToDate, PcUpdater.currentName());
        }
        note(status);
        if (PcUpdater.state == PcUpdater.State.AVAILABLE || PcUpdater.state == PcUpdater.State.READY || PcUpdater.state == PcUpdater.State.DOWNLOADING) {
            row(s(R.string.PcUpdNowBtn), "", () -> PcUpdater.present(getParentActivity()));
        }
        if (PcUpdater.state == PcUpdater.State.ERROR) {
            row(s(R.string.PcUpdRetry), "", () -> PcUpdater.present(getParentActivity()));
        }
        row(s(R.string.PcUpdSearch), "", () -> PcUpdater.checkNow(getParentActivity()));
        row(s(R.string.PcUpdAutoToggle), s(PcUpdater.autoUpdate() ? R.string.PcSecOn : R.string.PcSecOff), () -> {
            PcUpdater.setAutoUpdate(!PcUpdater.autoUpdate());
            build();
        });
        note(s(R.string.PcUpdAutoInfo));
        notifyData();
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
            f.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 90, 0));
            TextView v = new TextView(context);
            v.setTag("v");
            v.setTextSize(14);
            v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            v.setGravity(Gravity.RIGHT);
            f.addView(v, LayoutHelper.createFrame(80, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            return f;
        }
        TextView t = new TextView(context);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setTextSize(13);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
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
