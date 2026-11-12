package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;
import java.util.HashSet;

/** Scheda Shop: catalogo nativo (due colonne) dal feed pubblico Shopify, paginato, con cache e pull-to-refresh. */
public class PcShopActivity extends PcListFragment implements MainTabsActivity.TabFragmentDelegate {
    private static final int T_PAIR = 0, T_FOOTER = 1;

    private final ArrayList<PcShopApi.Product> products = new ArrayList<>();
    private final HashSet<Long> seen = new HashSet<>();
    private int nextPage = 1;
    private boolean hasMore = true, loading, fromCache, failed;

    public PcShopActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        loadMore(true);
        return super.onFragmentCreate();
    }

    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcShopTitle);
    }

    @Override
    protected boolean withPullToRefresh() {
        return true;
    }

    @Override
    protected void onPullRefresh() {
        loadMore(true);
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    private void loadMore(boolean reset) {
        if (loading && !reset) {
            return;
        }
        final int page = reset ? 1 : nextPage;
        loading = true;
        failed = false;
        PcShopApi.load(page, (list, cached, more, error) -> {
            if (error != null) {
                loading = false;
                failed = true;
                if (pull != null) {
                    pull.setRefreshing(false);
                }
                notifyData();
                return;
            }
            if (page == 1) {
                products.clear();
                seen.clear();
            }
            for (PcShopApi.Product p : list) {
                if (seen.add(p.id)) {
                    products.add(p);
                }
            }
            fromCache = cached;
            if (cached) {
                // la rete risponde subito dopo: non chiudo il caricamento
                hasMore = true;
                nextPage = 2;
            } else {
                loading = false;
                hasMore = more;
                nextPage = page + 1;
                if (pull != null) {
                    pull.setRefreshing(false);
                }
            }
            notifyData();
        });
    }

    @Override
    protected int itemCount() {
        return (products.size() + 1) / 2 + 1;
    }

    @Override
    protected int itemViewType(int position) {
        return position < (products.size() + 1) / 2 ? T_PAIR : T_FOOTER;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_PAIR) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            row.addView(new Card(context), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
            row.addView(new Card(context), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
            return row;
        }
        TextView t = new TextView(context);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        t.setPadding(dp(16), dp(16), dp(16), dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 24));
        t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return t;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        if (viewType == T_PAIR) {
            LinearLayout l = (LinearLayout) row;
            for (int i = 0; i < 2; i++) {
                int idx = position * 2 + i;
                Card c = (Card) l.getChildAt(i);
                if (idx < products.size()) {
                    c.setVisibility(View.VISIBLE);
                    c.bind(products.get(idx));
                } else {
                    c.setVisibility(View.INVISIBLE);
                    c.bind(null);
                }
            }
            if (hasMore && !loading && !failed && position >= (products.size() + 1) / 2 - 3) {
                AndroidUtilities.runOnUIThread(() -> loadMore(false));
            }
        } else {
            TextView t = (TextView) row;
            if (failed) {
                t.setText(LocaleController.getString(products.isEmpty() ? R.string.PcShopError : R.string.PcShopOffline));
            } else if (loading || hasMore) {
                t.setText(LocaleController.getString(R.string.PcShopLoading));
            } else if (products.isEmpty()) {
                t.setText(LocaleController.getString(R.string.PcShopEmpty));
            } else {
                t.setText(fromCache ? LocaleController.getString(R.string.PcShopOffline) : "");
            }
        }
    }

    private class Card extends LinearLayout {
        private final BackupImageView image;
        private final TextView title, price;
        private PcShopApi.Product product;

        Card(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(1), dp(1), dp(1), dp(10));
            image = new BackupImageView(context);
            image.setRoundRadius(dp(4));
            addView(image, new LayoutParams(-1, dp(160)));
            title = new TextView(context);
            title.setTextSize(13);
            title.setTypeface(AndroidUtilities.bold());
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 8, 2, 0));
            price = new TextView(context);
            price.setTextSize(13);
            price.setSingleLine(true);
            price.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(price, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 2, 2, 0));
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            setOnClickListener(v -> {
                if (product != null) {
                    presentFragment(new PcProductActivity(product));
                }
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            ((LayoutParams) image.getLayoutParams()).height = Math.max(0, w - getPaddingLeft() - getPaddingRight());
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        void bind(PcShopApi.Product p) {
            product = p;
            if (p == null) {
                return;
            }
            title.setText(p.title);
            String pr = p.priceLabel();
            if (!p.available()) {
                pr += "  -  " + LocaleController.getString(R.string.PcShopSoldOut);
            }
            price.setText(pr);
            if (p.image() != null) {
                image.setImage(p.image(), "400_400", null);
            }
        }
    }
}
