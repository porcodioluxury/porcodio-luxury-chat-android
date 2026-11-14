package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DataSettingsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.FiltersSetupActivity;
import org.telegram.ui.LanguageSelectActivity;
import org.telegram.ui.LiteModeSettingsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.NotificationsSettingsActivity;
import org.telegram.ui.PrivacySettingsActivity;
import org.telegram.ui.SessionsActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.UserInfoActivity;

import java.util.ArrayList;

/**
 * Impostazioni di Porcodio Luxury Chat: struttura propria a sezioni (Funzioni Porcodio, Account e privacy, Aspetto e chat,
 * Informazioni, Avanzate). Le schermate originali restano raggiungibili da qui, in fondo ("Tutte le impostazioni originali").
 */
public class PcSettingsActivity extends PcListFragment implements MainTabsActivity.TabFragmentDelegate {
    private static final int T_PROFILE = 0, T_SECTION = 1, T_ROW = 2, T_FOOT = 3;

    private static class Item {
        int type;
        String title, sub;
        int icon;
        Runnable click;

        Item(int type, String title, String sub, int icon, Runnable click) {
            this.type = type;
            this.title = title;
            this.sub = sub;
            this.icon = icon;
            this.click = click;
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();

    public PcSettingsActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        build();
        return super.onFragmentCreate();
    }

    private static String s(int res) {
        return LocaleController.getString(res);
    }

    private void section(int titleRes) {
        items.add(new Item(T_SECTION, s(titleRes), null, 0, null));
    }

    private void row(int titleRes, int subRes, int icon, Runnable click) {
        items.add(new Item(T_ROW, s(titleRes), subRes == 0 ? null : s(subRes), icon, click));
    }

    private void build() {
        items.clear();
        final boolean featuresOnly = getArguments() != null && getArguments().getBoolean("features_only", false);
        if (!featuresOnly) {
            items.add(new Item(T_PROFILE, null, null, 0, null));
        }

        section(R.string.PcSetFeatures);
        row(R.string.PcFeedInfo, R.string.PcFeedInfoSub, R.drawable.settings_channel, () -> presentFragment(new PcFeedActivity(new Bundle())));
        row(R.string.PcQuickReplies, R.string.PcQuickRepliesInfo, R.drawable.settings_chat, () -> presentFragment(new PcQuickActivity()));
        row(R.string.PcTabShop, R.string.PcSetShopInfo, R.drawable.settings_gift, () -> presentFragment(new PcShopActivity(new Bundle())));
        row(R.string.PcSetPrivateTools, R.string.PcSetPrivateToolsInfo, R.drawable.settings_privacy, this::explainPrivateTools);
        row(R.string.PcQuickCleanup, R.string.PcQuickCleanupInfo, R.drawable.settings_folders, () -> presentFragment(new PcCleanupActivity()));
        row(R.string.PcAntispamMenu, R.string.PcSetAntispamInfo, R.drawable.settings_policy, () -> PcAntispam.showMenu(this, currentAccount, null));
        row(R.string.PcMuteAllGroups, R.string.PcMuteAllGroupsInfo, R.drawable.settings_sounds, () -> PcAntispam.muteAllGroups(this, currentAccount, null));
        row(R.string.PcUpdSettings, R.string.PcUpdSettingsInfo, R.drawable.settings_data, () -> PcUpdater.checkNow(getParentActivity()));

        if (featuresOnly) {
            items.add(new Item(T_FOOT, null, null, 0, null));
            return;
        }

        section(R.string.PcSetAccount);
        row(R.string.PcSetPrivacy, 0, R.drawable.settings_privacy, () -> presentFragment(new PrivacySettingsActivity()));
        row(R.string.PcSetNotifications, 0, R.drawable.settings_sounds, () -> presentFragment(new NotificationsSettingsActivity()));
        row(R.string.PcSetData, 0, R.drawable.settings_data, () -> presentFragment(new DataSettingsActivity()));
        row(R.string.PcSetDevices, 0, R.drawable.settings_devices, () -> presentFragment(new SessionsActivity(0)));

        section(R.string.PcSetLook);
        row(R.string.PcSetTheme, 0, R.drawable.settings_chat, () -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        row(R.string.PcSetFolders, 0, R.drawable.settings_folders, () -> presentFragment(new FiltersSetupActivity()));
        row(R.string.PcSetLanguage, 0, R.drawable.settings_language, () -> presentFragment(new LanguageSelectActivity()));
        row(R.string.PcSetPower, 0, R.drawable.settings_power, () -> presentFragment(new LiteModeSettingsActivity()));

        section(R.string.PcSetInfo);
        items.add(new Item(T_ROW, s(R.string.PcSetAbout), versionLine(), R.drawable.settings_faq, null));

        section(R.string.PcSetAdvanced);
        row(R.string.PcSetOriginal, R.string.PcSetOriginalInfo, R.drawable.settings_features, () -> presentFragment(new SettingsActivity(new Bundle())));
        items.add(new Item(T_FOOT, null, null, 0, null));
    }

    private String versionLine() {
        String v = "";
        try {
            PackageInfo p = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            v = "v" + p.versionName + "\n";
        } catch (Throwable ignore) {
        }
        return v + s(R.string.PcApiDisclosure);
    }

    private void explainPrivateTools() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s(R.string.PcSetPrivateTools));
        b.setMessage(s(R.string.PcSetPrivateToolsHow));
        b.setPositiveButton(s(R.string.OK), null);
        showDialog(b.create());
    }

    @Override
    protected String screenTitle() {
        return s(getArguments() != null && getArguments().getBoolean("features_only", false) ? R.string.PcSetFeatures : R.string.Settings);
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return v;
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
    protected void onRowClick(View view, int position) {
        if (position >= 0 && position < items.size() && items.get(position).click != null) {
            items.get(position).click.run();
        }
    }

    @Override
    protected View createRow(Context context, int viewType) {
        switch (viewType) {
            case T_PROFILE:
                return new ProfileRow(context);
            case T_SECTION: {
                TextView t = PcUi.label(context, "", 11, true, Theme.key_windowBackgroundWhiteGrayText);
                t.setAllCaps(true);
                t.setLetterSpacing(0.08f);
                t.setPadding(dp(18), dp(16), dp(16), dp(6));
                t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return t;
            }
            case T_FOOT: {
                View f = new View(context);
                f.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 24)));
                return f;
            }
            default:
                return new SettingRow(context);
        }
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Item it = items.get(position);
        if (viewType == T_PROFILE) {
            ((ProfileRow) row).bind();
        } else if (viewType == T_SECTION) {
            ((TextView) row).setText(it.title);
        } else if (viewType == T_ROW) {
            ((SettingRow) row).bind(it);
        }
    }

    private class ProfileRow extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView name, line;
        private final AvatarDrawable drawable = new AvatarDrawable();

        ProfileRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(14), dp(14), dp(14));
            box.setBackground(PcUi.panel(true));
            LinearLayout top = new LinearLayout(context);
            top.setGravity(Gravity.CENTER_VERTICAL);
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(4));
            top.addView(avatar, LayoutHelper.createLinear(72, 72));
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            name = PcUi.label(context, "", 20, true, Theme.key_windowBackgroundWhiteBlackText);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            line = PcUi.label(context, "", 13, false, Theme.key_windowBackgroundWhiteGrayText);
            line.setMaxLines(2);
            col.addView(name);
            col.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            top.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
            box.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            LinearLayout buttons = new LinearLayout(context);
            buttons.addView(PcUi.button(context, s(R.string.PcSetEditProfile), true, v -> presentFragment(new UserInfoActivity())), LayoutHelper.createLinear(0, 42, 1f, 0, 0, 4, 0));
            buttons.addView(PcUi.button(context, s(R.string.PcProfileBoard), false, v -> presentFragment(new PcFeedActivity(new Bundle()))), LayoutHelper.createLinear(0, 42, 1f, 4, 0, 0, 0));
            box.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 8, 12, 4));
        }

        void bind() {
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (me == null) {
                return;
            }
            drawable.setInfo(currentAccount, me);
            avatar.setForUserOrChat(me, drawable);
            name.setText(UserObject.getUserName(me));
            String un = UserObject.getPublicUsername(me);
            String phone = TextUtils.isEmpty(me.phone) ? "" : PhoneFormat.getInstance().format("+" + me.phone);
            line.setText((TextUtils.isEmpty(un) ? "" : "@" + un + "\n") + phone);
        }
    }

    private class SettingRow extends FrameLayout {
        private final ImageView icon;
        private final TextView title, sub;

        SettingRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            LinearLayout box = new LinearLayout(context);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(dp(12), dp(10), dp(14), dp(10));
            box.setBackground(PcUi.panel(true));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setBackground(PcUi.panel(false));
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            box.addView(icon, LayoutHelper.createLinear(40, 40));
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            title = PcUi.label(context, "", 15, true, Theme.key_windowBackgroundWhiteBlackText);
            sub = PcUi.label(context, "", 12, false, Theme.key_windowBackgroundWhiteGrayText);
            col.addView(title);
            col.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            box.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 3, 12, 3));
        }

        void bind(Item it) {
            title.setText(it.title);
            sub.setText(it.sub == null ? "" : it.sub);
            sub.setVisibility(it.sub == null ? GONE : VISIBLE);
            icon.setImageResource(it.icon);
        }
    }
}
