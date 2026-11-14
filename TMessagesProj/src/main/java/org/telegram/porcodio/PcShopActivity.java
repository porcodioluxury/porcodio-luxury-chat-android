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
    private static final int T_PAIR = 0, T_FOOTER = 1, T_FILTER = 2;

    private final ArrayList<PcShopApi.Product> products = new ArrayList<>();
    private final HashSet<Long> seen = new HashSet<>();
    private final ArrayList<PcShopApi.Product> shown = new ArrayList<>();
    private String query = "", category = null;
    private int sort = 0; // 0 predefinito, 1 prezzo crescente, 2 prezzo decrescente
    private boolean onlyAvailable;
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

    private void applyFilter() {
        shown.clear();
        final String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        for (PcShopApi.Product p : products) {
            if (category != null && !category.equals(p.type)) {
                continue;
            }
            if (onlyAvailable && !p.available()) {
                continue;
            }
            if (!q.isEmpty() && !((p.title != null ? p.title : "") + " " + (p.type != null ? p.type : "") + " " + (p.vendor != null ? p.vendor : "")).toLowerCase(java.util.Locale.ROOT).contains(q)) {
                continue;
            }
            shown.add(p);
        }
        if (sort == 1) {
            java.util.Collections.sort(shown, (a, b) -> Double.compare(a.minPrice(), b.minPrice()));
        } else if (sort == 2) {
            java.util.Collections.sort(shown, (a, b) -> Double.compare(b.minPrice(), a.minPrice()));
        }
    }

    @Override
    protected void notifyData() {
        applyFilter();
        super.notifyData();
    }

    private ArrayList<String> categories() {
        ArrayList<String> res = new ArrayList<>();
        for (PcShopApi.Product p : products) {
            if (p.type != null && !p.type.trim().isEmpty() && !res.contains(p.type)) {
                res.add(p.type);
            }
        }
        java.util.Collections.sort(res);
        return res;
    }

    private void askQuery() {
        if (getParentActivity() == null) {
            return;
        }
        final android.widget.EditText edit = new android.widget.EditText(getParentActivity());
        edit.setText(query);
        edit.setSingleLine(true);
        edit.setHint(LocaleController.getString(R.string.PcShopSearchHint));
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        android.widget.FrameLayout holder = new android.widget.FrameLayout(getParentActivity());
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcShopSearch));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcShopSearch), (d, w) -> {
            query = edit.getText().toString();
            notifyData();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    @Override
    protected int itemCount() {
        return 1 + (shown.size() + 1) / 2 + 1;
    }

    @Override
    protected int itemViewType(int position) {
        if (position == 0) {
            return T_FILTER;
        }
        return position - 1 < (shown.size() + 1) / 2 ? T_PAIR : T_FOOTER;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_FILTER) {
            FilterRow f = new FilterRow(context);
            f.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return f;
        }
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
        if (viewType == T_FILTER) {
            ((FilterRow) row).bind();
        } else if (viewType == T_PAIR) {
            LinearLayout l = (LinearLayout) row;
            final int pairIndex = position - 1;
            for (int i = 0; i < 2; i++) {
                int idx = pairIndex * 2 + i;
                Card c = (Card) l.getChildAt(i);
                if (idx < shown.size()) {
                    c.setVisibility(View.VISIBLE);
                    c.bind(shown.get(idx));
                } else {
                    c.setVisibility(View.INVISIBLE);
                    c.bind(null);
                }
            }
        } else {
            TextView t = (TextView) row;
            if (failed) {
                t.setText(LocaleController.getString(products.isEmpty() ? R.string.PcShopError : R.string.PcShopOffline));
            } else if (loading || hasMore) {
                t.setText(LocaleController.getString(R.string.PcShopLoading));
            } else if (shown.isEmpty()) {
                t.setText(LocaleController.getString(products.isEmpty() ? R.string.PcShopEmpty : R.string.PcShopNoResults));
            } else {
                t.setText(fromCache ? LocaleController.getString(R.string.PcShopOffline) : "");
            }
            // il catalogo è piccolo: si carica tutto, così categorie e filtri sono completi
            if (hasMore && !loading && !failed) {
                AndroidUtilities.runOnUIThread(() -> loadMore(false));
            }
        }
    }

    private TextView chip(Context context, String text, boolean selected, View.OnClickListener l) {
        TextView t = new TextView(context);
        t.setText(text.toUpperCase());
        t.setTextSize(12);
        t.setTypeface(AndroidUtilities.bold());
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        if (selected) {
            t.setBackground(PcUi.filled(Theme.getColor(Theme.key_chats_actionBackground)));
            t.setTextColor(Theme.getColor(Theme.key_chats_actionIcon));
        } else {
            t.setBackground(PcUi.panel(true));
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        t.setOnClickListener(l);
        return t;
    }

    private class FilterRow extends LinearLayout {
        FilterRow(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(8), dp(8), dp(8), dp(4));
        }

        void bind() {
            final Context context = getContext();
            removeAllViews();
            // riga 1: ricerca + categorie
            android.widget.HorizontalScrollView sv1 = new android.widget.HorizontalScrollView(context);
            sv1.setHorizontalScrollBarEnabled(false);
            LinearLayout r1 = new LinearLayout(context);
            String searchLabel = query.trim().isEmpty() ? LocaleController.getString(R.string.PcShopSearch) : "\u201C" + query.trim() + "\u201D \u2715";
            r1.addView(chip(context, searchLabel, !query.trim().isEmpty(), v -> {
                if (query.trim().isEmpty()) {
                    askQuery();
                } else {
                    query = "";
                    notifyData();
                }
            }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            r1.addView(chip(context, LocaleController.getString(R.string.PcShopAll), category == null, v -> {
                category = null;
                notifyData();
            }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            for (String c : categories()) {
                r1.addView(chip(context, c, c.equals(category), v -> {
                    category = c.equals(category) ? null : c;
                    notifyData();
                }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            }
            sv1.addView(r1);
            addView(sv1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            // riga 2: ordinamento + disponibili
            android.widget.HorizontalScrollView sv2 = new android.widget.HorizontalScrollView(context);
            sv2.setHorizontalScrollBarEnabled(false);
            LinearLayout r2 = new LinearLayout(context);
            r2.addView(chip(context, LocaleController.getString(R.string.PcShopSortLow), sort == 1, v -> {
                sort = sort == 1 ? 0 : 1;
                notifyData();
            }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            r2.addView(chip(context, LocaleController.getString(R.string.PcShopSortHigh), sort == 2, v -> {
                sort = sort == 2 ? 0 : 2;
                notifyData();
            }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            r2.addView(chip(context, LocaleController.getString(R.string.PcShopOnlyAvailable), onlyAvailable, v -> {
                onlyAvailable = !onlyAvailable;
                notifyData();
            }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            sv2.addView(r2);
            addView(sv2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            TextView count = new TextView(context);
            count.setTextSize(12);
            count.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            count.setText(LocaleController.formatString(R.string.PcShopCount, shown.size()));
            addView(count, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 8, 4, 2));
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
