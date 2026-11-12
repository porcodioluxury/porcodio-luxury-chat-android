package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;

/** Pull-to-refresh minimale (nessuna dipendenza esterna): trascina la lista verso il basso quando è in cima. */
public class PcPullLayout extends FrameLayout {
    public interface Listener {
        void onRefresh();
    }

    private final int slop;
    private final View content;
    private Listener listener;
    private float downY;
    private boolean pulling, atTop, refreshing;
    private float pull;

    public PcPullLayout(Context context, View content) {
        super(context);
        this.content = content;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        addView(content, new LayoutParams(-1, -1));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setRefreshing(boolean value) {
        if (refreshing == value) {
            return;
        }
        refreshing = value;
        if (!value) {
            animateTo(0);
        } else {
            animateTo(dp(48));
        }
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    private void animateTo(float target) {
        ValueAnimator a = ValueAnimator.ofFloat(pull, target);
        a.setDuration(220);
        a.addUpdateListener(v -> {
            pull = (float) v.getAnimatedValue();
            content.setTranslationY(pull);
        });
        a.start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                atTop = !content.canScrollVertically(-1);
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!refreshing && atTop && ev.getY() - downY > slop && !content.canScrollVertically(-1)) {
                    pulling = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (pulling) {
                    pull = Math.max(0, Math.min(dp(110), (ev.getY() - downY - slop) * 0.5f));
                    content.setTranslationY(pull);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling) {
                    pulling = false;
                    if (pull >= dp(64) && listener != null && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        refreshing = true;
                        animateTo(dp(48));
                        listener.onRefresh();
                    } else {
                        animateTo(0);
                    }
                }
                return true;
        }
        return pulling;
    }
}
