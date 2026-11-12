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
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
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
