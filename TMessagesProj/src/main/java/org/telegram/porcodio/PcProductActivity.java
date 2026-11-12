package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.messenger.browser.Browser;

import java.util.ArrayList;

/** Dettaglio prodotto: galleria, prezzo, varianti, descrizione; Acquista (apre il sito) e Condividi in chat. */
public class PcProductActivity extends PcListFragment {
    private static final int T_GALLERY = 0, T_TITLE = 1, T_TEXT = 2, T_HEADER = 3, T_VARIANT = 4;

    private final PcShopApi.Product product;
    private int selectedImage;
    private BackupImageView mainImage;

    public PcProductActivity(PcShopApi.Product product) {
        super();
        this.product = product;
    }

    @Override
    protected String screenTitle() {
        return product.title;
    }

    @Override
    protected int bottomReserve() {
        return 64;
    }

    private final ArrayList<Object[]> rows = new ArrayList<>(); // {type, string, variant?}

    private void rebuild() {
        rows.clear();
        rows.add(new Object[]{T_GALLERY, null, null});
        rows.add(new Object[]{T_TITLE, product.title, null});
        if (!product.description.isEmpty()) {
            rows.add(new Object[]{T_HEADER, LocaleController.getString(R.string.PcShopAbout), null});
            rows.add(new Object[]{T_TEXT, product.description, null});
        }
        if (product.variants.size() > 1 || (product.variants.size() == 1 && !"Default Title".equals(product.variants.get(0).title))) {
            rows.add(new Object[]{T_HEADER, LocaleController.getString(R.string.PcShopVariants), null});
            for (PcShopApi.Variant v : product.variants) {
                rows.add(new Object[]{T_VARIANT, v.title, v});
            }
        }
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        rebuild();
        notifyData();
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView share = button(context, LocaleController.getString(R.string.PcShareInChat), false);
        TextView buy = button(context, LocaleController.getString(R.string.PcBuy), true);
        share.setOnClickListener(x -> shareInChat());
        buy.setOnClickListener(x -> Browser.openUrl(getParentActivity(), product.url()));
        bar.addView(share, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 6, 0));
        bar.addView(buy, LayoutHelper.createLinear(0, 48, 1f, 6, 0, 0, 0));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM));
        return v;
    }

    private TextView button(Context context, String text, boolean primary) {
        TextView t = new TextView(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                float sw = Math.max(1f, AndroidUtilities.density);
                RectF r = AndroidUtilities.rectTmp;
                r.set(sw / 2, sw / 2, getWidth() - sw / 2, getHeight() - sw / 2);
                if (primary) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Theme.getColor(Theme.key_chats_actionBackground));
                } else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    p.setColor(Theme.getColor(Theme.key_chats_actionBackground));
                }
                canvas.drawRoundRect(r, dp(4), dp(4), p);
                super.onDraw(canvas);
            }
        };
        t.setWillNotDraw(false);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextSize(14);
        t.setText(text);
        t.setTextColor(Theme.getColor(primary ? Theme.key_chats_actionIcon : Theme.key_windowBackgroundWhiteBlackText));
        return t;
    }

    private void shareInChat() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        DialogsActivity picker = new DialogsActivity(args);
        final String text = product.title + "\n" + product.priceLabel() + "\n" + product.url();
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            AccountInstance acc = AccountInstance.getInstance(currentAccount);
            for (MessagesStorage.TopicKey key : dids) {
                SendMessagesHelper.prepareSendingText(acc, text, key.dialogId, key.topicId, true, 0, 0, 0);
                if (message != null && message.length() > 0) {
                    SendMessagesHelper.prepareSendingText(acc, message, key.dialogId, key.topicId, true, 0, 0, 0);
                }
            }
            fragment.finishFragment();
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.PcShopShared), Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        presentFragment(picker);
    }

    @Override
    protected int itemCount() {
        return rows.size();
    }

    @Override
    protected int itemViewType(int position) {
        return (Integer) rows.get(position)[0];
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_GALLERY) {
            LinearLayout col = new LinearLayout(context) {
                @Override
                protected void onMeasure(int w, int h) {
                    int width = MeasureSpec.getSize(w);
                    mainImage.getLayoutParams().height = width;
                    super.onMeasure(w, h);
                }
            };
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            mainImage = new BackupImageView(context);
            col.addView(mainImage, new LinearLayout.LayoutParams(-1, dp(300)));
            if (product.images.size() > 1) {
                HorizontalScrollView hs = new HorizontalScrollView(context);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout strip = new LinearLayout(context);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                strip.setPadding(dp(12), dp(8), dp(12), dp(4));
                for (int i = 0; i < product.images.size(); i++) {
                    final int index = i;
                    BackupImageView th = new BackupImageView(context);
                    th.setRoundRadius(dp(4));
                    th.setImage(product.images.get(i), "120_120", null);
                    th.setOnClickListener(x -> {
                        selectedImage = index;
                        showMain();
                    });
                    strip.addView(th, LayoutHelper.createLinear(56, 56, 0, 0, 6, 0));
                }
                hs.addView(strip);
                col.addView(hs, new LinearLayout.LayoutParams(-1, -2));
            }
            return col;
        }
        TextView t = new TextView(context);
        t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        if (viewType == T_TITLE) {
            t.setPadding(dp(16), dp(14), dp(16), dp(2));
            t.setTextSize(20);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        } else if (viewType == T_HEADER) {
            t.setPadding(dp(16), dp(18), dp(16), dp(6));
            t.setTextSize(13);
            t.setAllCaps(true);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        } else if (viewType == T_VARIANT) {
            t.setPadding(dp(16), dp(8), dp(16), dp(8));
            t.setTextSize(15);
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        } else {
            t.setPadding(dp(16), dp(2), dp(16), dp(8));
            t.setTextSize(14);
            t.setLineSpacing(dp(2), 1f);
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        return t;
    }

    private void showMain() {
        if (mainImage != null && !product.images.isEmpty()) {
            mainImage.setImage(product.images.get(Math.min(selectedImage, product.images.size() - 1)), "800_800", null);
        }
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Object[] r = rows.get(position);
        if (viewType == T_GALLERY) {
            showMain();
            return;
        }
        TextView t = (TextView) row;
        if (viewType == T_TITLE) {
            t.setText(product.title + "\n" + product.priceLabel() + (product.available() ? "" : "  -  " + LocaleController.getString(R.string.PcShopSoldOut)));
        } else if (viewType == T_VARIANT) {
            PcShopApi.Variant v = (PcShopApi.Variant) r[2];
            String s = v.title + "   " + PcShopApi.money(v.price);
            if (!v.available) {
                s += "   -   " + LocaleController.getString(R.string.PcShopSoldOut);
            }
            t.setText(s);
            t.setAlpha(v.available ? 1f : 0.5f);
        } else {
            t.setText((String) r[1]);
        }
    }
}
