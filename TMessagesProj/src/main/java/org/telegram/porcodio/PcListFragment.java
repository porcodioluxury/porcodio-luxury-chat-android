package org.telegram.porcodio;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** Base minimale per le schermate Porcodio (Pulizia rapida, Shop): barra semplice + lista. */
public abstract class PcListFragment extends BaseFragment {
    protected RecyclerListView listView;
    protected FrameLayout root;
    protected PcPullLayout pull;
    protected RecyclerListView.SelectionAdapter adapter;

    protected boolean hasMainTabs;

    public PcListFragment() {
        super();
    }

    public PcListFragment(android.os.Bundle args) {
        super(args);
        hasMainTabs = args != null && args.getBoolean("hasMainTabs", false);
    }

    protected abstract String screenTitle();

    protected abstract int itemCount();

    protected abstract int itemViewType(int position);

    protected abstract View createRow(Context context, int viewType);

    protected abstract void bindRow(View row, int viewType, int position);

    protected void onRowClick(View view, int position) {
    }

    protected boolean isRowEnabled(int position) {
        return true;
    }

    protected boolean withPullToRefresh() {
        return false;
    }

    /** true = l'intestazione sta sopra al contenuto (es. Reel a schermo intero): nessun margine superiore. */
    protected boolean overlayHeader() {
        return false;
    }

    @Override
    protected void onViewCreated(View view) {
        super.onViewCreated(view);
        // Porcodio v10: il contenuto parte sempre sotto l'intestazione (niente titolo sovrapposto ai contenuti)
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (listView == null) {
                return;
            }
            int want = 0;
            if (!overlayHeader() && simpleActionBar != null && simpleActionBar.getVisibility() != View.GONE) {
                want = simpleActionBar.getMeasuredHeight();
                if (want <= 0) {
                    return;
                }
            }
            if (listView.getPaddingTop() != want) {
                listView.setPadding(listView.getPaddingLeft(), want, listView.getPaddingRight(), listView.getPaddingBottom());
                listView.setClipToPadding(false);
                if (listView.getLayoutManager() instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
                }
            }
        });
    }

    protected void onPullRefresh() {
    }

    protected int bottomReserve() {
        return 0;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    protected void notifyData() {
        if (adapter != null) {
            // Porcodio: mantiene la posizione di scorrimento quando la lista si ricarica (niente salti in cima)
            int pos = -1, off = 0;
            try {
                if (listView != null && listView.getLayoutManager() instanceof LinearLayoutManager) {
                    LinearLayoutManager lm = (LinearLayoutManager) listView.getLayoutManager();
                    pos = lm.findFirstVisibleItemPosition();
                    View first = pos >= 0 ? lm.findViewByPosition(pos) : null;
                    off = first != null ? first.getTop() : 0;
                }
            } catch (Throwable ignore) {
                pos = -1;
            }
            adapter.notifyDataSetChanged();
            if (pos > 0 && listView != null && pos < adapter.getItemCount()) {
                ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(pos, off);
            }
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        if (!hasMainTabs) {
            simpleActionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        }
        simpleActionBar.setTitle(screenTitle());
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        root = new FrameLayout(context);
        fragmentView = root;
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return isRowEnabled(holder.getAdapterPosition());
            }

            @Override
            public int getItemCount() {
                return itemCount();
            }

            @Override
            public int getItemViewType(int position) {
                return itemViewType(position);
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(createRow(parent.getContext(), viewType));
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                bindRow(holder.itemView, holder.getItemViewType(), position);
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(view, position));
        final int reserve = bottomReserve();
        if (withPullToRefresh()) {
            pull = new PcPullLayout(context, listView);
            pull.setListener(this::onPullRefresh);
            root.addView(pull, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.TOP, 0, 0, 0, reserve));
        } else {
            root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.TOP, 0, 0, 0, reserve));
        }
        return fragmentView;
    }
}
