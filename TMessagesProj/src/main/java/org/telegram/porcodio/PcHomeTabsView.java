package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Porcodio Luxury Chat: barra delle schede della home. Private / Gruppi / Canali con contatore dei non letti,
 * più Richieste e Spam (antispam) e un pulsante "…" con le azioni (blocca e segnala, silenzia gruppi, pulizia).
 */
public class PcHomeTabsView extends FrameLayout {
    private static final int[] TITLES = {R.string.PcTabPrivate, R.string.PcTabGroups, R.string.PcTabChannels, R.string.PcTabRequests, R.string.PcTabSpam};

    private final int currentAccount;
    private final BaseFragment fragment;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout row;
    private final TabButton[] buttons = new TabButton[PcHome.TABS];
    private final MoreButton more;
    private Runnable onUpdate;

    public void setOnUpdate(Runnable r) {
        onUpdate = r;
    }

    public PcHomeTabsView(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider, BaseFragment fragment) {
        super(context);
        this.currentAccount = currentAccount;
        this.fragment = fragment;
        this.resourcesProvider = resourcesProvider;

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 0, 0, 40, 0));

        for (int i = 0; i < PcHome.TABS; i++) {
            final int index = i;
            buttons[i] = new TabButton(context, index);
            buttons[i].setOnClickListener(v -> {
                PcHome.setTab(index, currentAccount);
                update();
            });
            row.addView(buttons[i], LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        }
        // scorciatoia al Feed interno (non cambia scheda della lista chat)
        android.widget.TextView feed = new android.widget.TextView(context);
        feed.setText(LocaleController.getString(R.string.PcChipFeed));
        feed.setTextSize(13);
        feed.setTypeface(AndroidUtilities.bold());
        feed.setGravity(Gravity.CENTER);
        feed.setPadding(dp(12), 0, dp(12), 0);
        feed.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        feed.setBackground(PcUi.panel(false));
        feed.setOnClickListener(v -> fragment.presentFragment(new PcFeedActivity(new android.os.Bundle())));
        row.addView(feed, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        android.widget.TextView book = new android.widget.TextView(context);
        book.setText(LocaleController.getString(R.string.PcChipContacts));
        book.setTextSize(13);
        book.setTypeface(AndroidUtilities.bold());
        book.setGravity(Gravity.CENTER);
        book.setPadding(dp(12), 0, dp(12), 0);
        book.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        book.setBackground(PcUi.panel(false));
        book.setOnClickListener(v -> {
            android.os.Bundle ca = new android.os.Bundle();
            ca.putBoolean("needFinishFragment", false);
            ca.putBoolean("needPhonebook", true);
            fragment.presentFragment(new org.telegram.ui.ContactsActivity(ca));
        });
        row.addView(book, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        android.widget.TextView feats = new android.widget.TextView(context);
        feats.setText(LocaleController.getString(R.string.PcFeaturesChip));
        feats.setTextSize(13);
        feats.setTypeface(AndroidUtilities.bold());
        feats.setGravity(Gravity.CENTER);
        feats.setPadding(dp(12), 0, dp(12), 0);
        feats.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        feats.setBackground(PcUi.panel(false));
        feats.setOnClickListener(v -> {
            android.os.Bundle fa = new android.os.Bundle();
            fa.putBoolean("features_only", true);
            fragment.presentFragment(new PcSettingsActivity(fa));
        });
        row.addView(feats, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        more = new MoreButton(context);
        more.setOnClickListener(v -> PcAntispam.showMenu(fragment, currentAccount, this::update));
        addView(more, LayoutHelper.createFrame(36, 32, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 0, 0));
        setWillNotDraw(false);
        update();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY));
    }

    public void update() {
        for (int i = 0; i < PcHome.TABS; i++) {
            buttons[i].refresh();
        }
        more.invalidate();
        if (onUpdate != null) {
            onUpdate.run();
        }
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private class TabButton extends View {
        private final int index;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint countText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint countFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String title = "";
        private String count = "";
        private float titleW, countW;

        TabButton(Context context, int index) {
            super(context);
            this.index = index;
            stroke.setStyle(Paint.Style.STROKE);
            text.setTextSize(dp(13));
            text.setTypeface(AndroidUtilities.bold());
            countText.setTextSize(dp(11));
            countText.setTypeface(AndroidUtilities.bold());
            refresh();
        }

        void refresh() {
            title = LocaleController.getString(TITLES[index]).toUpperCase();
            int n = PcHome.unread[index];
            count = n > 0 ? (n > 99 ? "99+" : String.valueOf(n)) : "";
            titleW = text.measureText(title);
            countW = count.isEmpty() ? 0 : Math.max(dp(16), countText.measureText(count) + dp(8));
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = (int) (dp(12) + titleW + (countW > 0 ? dp(6) + countW : 0) + dp(12));
            setMeasuredDimension(w, dp(32));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final boolean selected = PcHome.tab == index;
            final float sw = Math.max(1f, AndroidUtilities.density);
            stroke.setStrokeWidth(sw);
            RectF r = AndroidUtilities.rectTmp;
            r.set(sw / 2f, sw / 2f, getWidth() - sw / 2f, getHeight() - sw / 2f);
            if (selected) {
                fill.setColor(color(Theme.key_chats_actionBackground));
                canvas.drawRoundRect(r, dp(4), dp(4), fill);
                text.setColor(color(Theme.key_chats_actionIcon));
            } else {
                stroke.setColor(color(Theme.key_divider));
                canvas.drawRoundRect(r, dp(4), dp(4), stroke);
                text.setColor(color(Theme.key_windowBackgroundWhiteGrayText));
            }
            float cy = getHeight() / 2f - (text.ascent() + text.descent()) / 2f;
            canvas.drawText(title, dp(12), cy, text);
            if (countW > 0) {
                float cx = dp(12) + titleW + dp(6);
                r.set(cx, getHeight() / 2f - dp(8), cx + countW, getHeight() / 2f + dp(8));
                countFill.setColor(selected ? color(Theme.key_chats_actionIcon) : color(Theme.key_chats_unreadCounter));
                canvas.drawRoundRect(r, dp(4), dp(4), countFill);
                countText.setColor(selected ? color(Theme.key_chats_actionBackground) : color(Theme.key_chats_unreadCounterText));
                float ty = getHeight() / 2f - (countText.ascent() + countText.descent()) / 2f;
                canvas.drawText(count, cx + (countW - countText.measureText(count)) / 2f, ty, countText);
            }
        }
    }

    private class MoreButton extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        MoreButton(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float sw = Math.max(1f, AndroidUtilities.density);
            stroke.setStrokeWidth(sw);
            stroke.setColor(color(Theme.key_divider));
            AndroidUtilities.rectTmp.set(sw / 2f, sw / 2f, getWidth() - sw / 2f, getHeight() - sw / 2f);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(4), dp(4), stroke);
            dot.setColor(color(Theme.key_windowBackgroundWhiteGrayText));
            float cy = getHeight() / 2f, cx = getWidth() / 2f;
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(cx + i * dp(6), cy, dp(1.6f), dot);
            }
        }
    }
}
