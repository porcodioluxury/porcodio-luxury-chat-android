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
import org.telegram.messenger.AndroidUtilities;
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

        if (!featuresOnly) {
            section(R.string.PcSetSecurity);
            row(R.string.PcSetPrivacy, R.string.PcSetPrivacyInfo, R.drawable.ic_pc_shield, () -> presentFragment(new PrivacySettingsActivity()));
            row(R.string.PcSetPrivateTools, R.string.PcSetPrivateToolsInfo, R.drawable.ic_pc_lock, this::explainPrivateTools);
            row(R.string.PcSetSecretChats, R.string.PcSetSecretChatsInfo, R.drawable.ic_pc_lock, () -> presentFragment(new PcSecretActivity()));
            row(R.string.PcSecTitle, R.string.PcSecRowInfo, R.drawable.ic_pc_shield, () -> presentFragment(new PcSecurityActivity()));
            row(R.string.PcSetDevices, R.string.PcSetDevicesInfo, R.drawable.ic_pc_device, () -> presentFragment(new SessionsActivity(0)));

            section(R.string.PcSetLook);
            row(R.string.PcSetTheme, R.string.PcSetThemeInfo, R.drawable.ic_pc_palette, () -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
            row(R.string.PcSetFolders, R.string.PcSetFoldersInfo, R.drawable.ic_pc_folder, () -> presentFragment(new FiltersSetupActivity()));
            row(R.string.PcSetLanguage, 0, R.drawable.ic_pc_globe, () -> presentFragment(new LanguageSelectActivity()));

            section(R.string.PcSetNotifications);
            row(R.string.PcSetNotificationsRow, R.string.PcSetNotificationsInfo, R.drawable.ic_pc_bell, () -> presentFragment(new NotificationsSettingsActivity()));

            section(R.string.PcSetSpace);
            row(R.string.PcSetData, R.string.PcSetDataInfo, R.drawable.ic_pc_data, () -> presentFragment(new DataSettingsActivity()));
            row(R.string.PcQuickCleanup, R.string.PcQuickCleanupInfo, R.drawable.ic_pc_folder, () -> presentFragment(new PcCleanupActivity()));
            row(R.string.PcBulkSettingsRow, R.string.PcBulkSettingsSub, R.drawable.ic_pc_folder, () -> presentFragment(new PcBulkActivity()));
            row(R.string.PcSetPower, R.string.PcSetPowerInfo, R.drawable.ic_pc_spark, () -> presentFragment(new LiteModeSettingsActivity()));

            section(R.string.PcSetSocial);
        }
        if (featuresOnly) {
            section(R.string.PcSetFeatures);
        }
        row(R.string.PcFeedInfo, R.string.PcFeedInfoSub, R.drawable.ic_pc_grid, () -> presentFragment(new PcFeedActivity(new Bundle())));
        row(R.string.PcInterestsTitle, R.string.PcInterestsRowInfo, R.drawable.ic_pc_spark, () -> presentFragment(new PcInterestsActivity()));
        if (!featuresOnly) {
            section(R.string.PcSetShopOrders);
        }
        row(R.string.PcTabShop, R.string.PcSetShopInfo, R.drawable.ic_pc_bag, () -> presentFragment(new PcShopActivity(new Bundle())));
        if (!featuresOnly) {
            section(R.string.PcSetFeatures);
        }
        row(R.string.PcQuickReplies, R.string.PcQuickRepliesInfo, R.drawable.ic_pc_chat, () -> presentFragment(new PcQuickActivity()));
        row(R.string.PcAntispamMenu, R.string.PcSetAntispamInfo, R.drawable.ic_pc_shield, () -> PcAntispam.showMenu(this, currentAccount, null));
        row(R.string.PcMuteAllGroups, R.string.PcMuteAllGroupsInfo, R.drawable.ic_pc_bell, () -> PcAntispam.muteAllGroups(this, currentAccount, null));
        if (featuresOnly) {
            row(R.string.PcSecTitle, R.string.PcSecRowInfo, R.drawable.ic_pc_shield, () -> presentFragment(new PcSecurityActivity()));
            row(R.string.PcBulkSettingsRow, R.string.PcBulkSettingsSub, R.drawable.ic_pc_folder, () -> presentFragment(new PcBulkActivity()));
            row(R.string.PcSetPrivateTools, R.string.PcSetPrivateToolsInfo, R.drawable.ic_pc_lock, this::explainPrivateTools);
            row(R.string.PcUpdSettings, R.string.PcUpdSettingsInfo, R.drawable.ic_pc_refresh, () -> presentFragment(new PcUpdateActivity()));
            items.add(new Item(T_FOOT, null, null, 0, null));
            return;
        }

        section(R.string.PcSetSupport);
        row(R.string.PcUpdSettings, R.string.PcUpdSettingsInfo, R.drawable.ic_pc_refresh, () -> presentFragment(new PcUpdateActivity()));
        items.add(new Item(T_ROW, s(R.string.PcSetAbout), versionLine(), R.drawable.ic_pc_info, null));

        section(R.string.PcSetAdvanced);
        row(R.string.PcSetOriginal, R.string.PcSetOriginalInfo, R.drawable.ic_pc_edit, () -> presentFragment(new SettingsActivity(new Bundle())));
        items.add(new Item(T_FOOT, null, null, 0, null));
    }

    private void explainSecretChats() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s(R.string.PcSetSecretChats));
        b.setMessage(s(R.string.PcSetSecretChatsHow));
        b.setPositiveButton(s(R.string.OK), null);
        showDialog(b.create());
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
                t.setLetterSpacing(0.12f);
                t.setTypeface(android.graphics.Typeface.DEFAULT);
                t.setPadding(dp(20), dp(22), dp(16), dp(8));
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
            name = PcUi.label(context, "", 22, true, Theme.key_windowBackgroundWhiteBlackText);
            name.setTypeface(AndroidUtilities.bold());
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            line = PcUi.label(context, "", 13, false, Theme.key_windowBackgroundWhiteGrayText);
            line.setMaxLines(2);
            col.addView(name);
            col.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            top.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
            box.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            LinearLayout buttons = new LinearLayout(context);
            buttons.addView(PcUi.button(context, s(R.string.PcSetEditProfile), true, v -> presentFragment(new PcProfileEditActivity())), LayoutHelper.createLinear(0, 42, 1f, 0, 0, 4, 0));
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
            box.setPadding(dp(12), dp(14), dp(14), dp(14));
            box.setBackground(PcUi.panel(true));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            box.addView(icon, LayoutHelper.createLinear(28, 28));
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            title = PcUi.label(context, "", 16, true, Theme.key_windowBackgroundWhiteBlackText);
            title.setTypeface(AndroidUtilities.bold());
            sub = PcUi.label(context, "", 12, false, Theme.key_windowBackgroundWhiteGrayText);
            sub.setTypeface(android.graphics.Typeface.DEFAULT);
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
