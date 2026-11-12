/*
 * Porcodio Luxury Chat - schermata di intro.
 * Derivata da Telegram per Android (GNU GPL v. 2 or later, Copyright Nikolai Kudashov, 2013-2018).
 * Sostituite le animazioni OpenGL/rlottie con il logo statico Porcodio Luxury (bianco su nero #121212).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BottomPagesView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

public class IntroActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int PC_BG = 0xFF121212, PC_TEXT = 0xFFF3F3F3, PC_TEXT2 = 0xFFBFBFBF;

    private final int currentAccount = UserConfig.selectedAccount;

    private ViewPager viewPager;
    private BottomPagesView bottomPages;
    private TextView switchLanguageTextView;
    private TextView startMessagingButton;
    private FrameLayout frameContainerView;

    private boolean startPressed = false;
    private boolean destroyed;
    private boolean isOnLogout;
    private CharSequence[] titles;
    private String[] messages;
    private LocaleController.LocaleInfo localeInfo;

    @Override
    public boolean onFragmentCreate() {
        titles = new CharSequence[]{
                LocaleController.getString(R.string.PcIntro1Title),
                LocaleController.getString(R.string.PcIntro2Title),
                LocaleController.getString(R.string.PcIntro3Title),
                LocaleController.getString(R.string.PcIntro4Title),
                LocaleController.getString(R.string.PcIntro5Title),
                LocaleController.getString(R.string.PcIntro6Title)
        };
        messages = new String[]{
                LocaleController.getString(R.string.PcIntro1Message),
                LocaleController.getString(R.string.PcIntro2Message),
                LocaleController.getString(R.string.PcIntro3Message),
                LocaleController.getString(R.string.PcIntro4Message),
                LocaleController.getString(R.string.PcIntro5Message),
                LocaleController.getString(R.string.PcIntro6Message)
        };
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        frameContainerView = new FrameLayout(context);
        frameContainerView.setBackgroundColor(PC_BG);

        ImageView logoView = new ImageView(context);
        logoView.setImageResource(R.drawable.porcodio_logo_white);
        logoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logoView.setContentDescription(LocaleController.getString(R.string.AppName));
        frameContainerView.addView(logoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 190, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 48, 72, 48, 0));

        viewPager = new ViewPager(context);
        viewPager.setAdapter(new IntroAdapter());
        viewPager.setPageMargin(0);
        viewPager.setOffscreenPageLimit(1);
        frameContainerView.addView(viewPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 290, 0, 170));

        bottomPages = new BottomPagesView(context, viewPager, 6);
        frameContainerView.addView(bottomPages, LayoutHelper.createFrame(66, 5, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 150));
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                bottomPages.setPageOffset(position, positionOffset);
            }

            @Override
            public void onPageSelected(int position) {
                bottomPages.setCurrentPage(position);
            }
        });

        // bottone bianco #F3F3F3 con testo scuro, angoli 4dp (niente azzurro / gradiente)
        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setColor(PC_TEXT);
        buttonBg.setCornerRadius(dp(4));
        startMessagingButton = new TextView(context);
        startMessagingButton.setBackground(buttonBg);
        startMessagingButton.setTextColor(PC_BG);
        ScaleStateListAnimator.apply(startMessagingButton, .02f, 1.2f);
        startMessagingButton.setText(LocaleController.getString(R.string.StartMessaging));
        startMessagingButton.setGravity(Gravity.CENTER);
        startMessagingButton.setTypeface(AndroidUtilities.bold());
        startMessagingButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        startMessagingButton.setPadding(dp(34), 0, dp(34), 0);
        frameContainerView.addView(startMessagingButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 24, 0, 24, 76));
        startMessagingButton.setOnClickListener(view -> {
            if (startPressed) {
                return;
            }
            startPressed = true;
            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
            destroyed = true;
        });

        switchLanguageTextView = new TextView(context);
        switchLanguageTextView.setGravity(Gravity.CENTER);
        switchLanguageTextView.setTextColor(PC_TEXT2);
        switchLanguageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        frameContainerView.addView(switchLanguageTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));
        switchLanguageTextView.setOnClickListener(v -> {
            if (startPressed || localeInfo == null) {
                return;
            }
            startPressed = true;

            AlertDialog loaderDialog = new AlertDialog(v.getContext(), AlertDialog.ALERT_TYPE_SPINNER);
            loaderDialog.setCanCancel(false);
            loaderDialog.showDelayed(1000);

            NotificationCenter.getGlobalInstance().addObserver(new NotificationCenter.NotificationCenterDelegate() {
                @Override
                public void didReceivedNotification(int id, int account, Object... args) {
                    if (id == NotificationCenter.reloadInterface) {
                        loaderDialog.dismiss();

                        NotificationCenter.getGlobalInstance().removeObserver(this, id);
                        AndroidUtilities.runOnUIThread(() -> {
                            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
                            destroyed = true;
                        }, 100);
                    }
                }
            }, NotificationCenter.reloadInterface);
            LocaleController.getInstance().applyLanguage(localeInfo, true, false, currentAccount);
        });

        fragmentView = frameContainerView;

        NotificationCenter.getGlobalInstance().addWeakObserver(this, NotificationCenter.suggestedLangpack);
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.configLoaded);
        ConnectionsManager.getInstance(currentAccount).updateDcSettings();
        LocaleController.getInstance().loadRemoteLanguages(currentAccount);
        checkContinueText();

        return fragmentView;
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.lockOrientation(getParentActivity(), ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.unlockOrientation(getParentActivity());
    }

    @Override
    public boolean hasForceLightStatusBar() {
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        destroyed = true;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.suggestedLangpack);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.configLoaded);
        MessagesController.getGlobalMainSettings().edit().putLong("intro_crashed_time", 0).apply();
    }

    private void checkContinueText() {
        LocaleController.LocaleInfo englishInfo = null;
        LocaleController.LocaleInfo systemInfo = null;
        LocaleController.LocaleInfo currentLocaleInfo = LocaleController.getInstance().getCurrentLocaleInfo();
        String systemLang = MessagesController.getInstance(currentAccount).suggestedLangCode;
        if (systemLang == null || systemLang.equals("en") && LocaleController.getInstance().getSystemDefaultLocale().getLanguage() != null && !LocaleController.getInstance().getSystemDefaultLocale().getLanguage().equals("en")) {
            systemLang = LocaleController.getInstance().getSystemDefaultLocale().getLanguage();
            if (systemLang == null) {
                systemLang = "en";
            }
        }

        String arg = systemLang.contains("-") ? systemLang.split("-")[0] : systemLang;
        String alias = LocaleController.getLocaleAlias(arg);
        for (int a = 0; a < LocaleController.getInstance().languages.size(); a++) {
            LocaleController.LocaleInfo info = LocaleController.getInstance().languages.get(a);
            if (info.shortName.equals("en")) {
                englishInfo = info;
            }
            if (info.shortName.replace("_", "-").equals(systemLang) || info.shortName.equals(arg) || info.shortName.equals(alias)) {
                systemInfo = info;
            }
            if (englishInfo != null && systemInfo != null) {
                break;
            }
        }
        if (englishInfo == null || systemInfo == null || englishInfo == systemInfo) {
            return;
        }
        TLRPC.TL_langpack_getStrings req = new TLRPC.TL_langpack_getStrings();
        if (systemInfo != currentLocaleInfo) {
            req.lang_code = systemInfo.getLangCode();
            localeInfo = systemInfo;
        } else {
            req.lang_code = englishInfo.getLangCode();
            localeInfo = englishInfo;
        }
        req.keys.add("ContinueOnThisLanguage");
        String finalSystemLang = systemLang;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (response instanceof Vector) {
                Vector vector = (Vector) response;
                if (vector.objects.isEmpty()) {
                    return;
                }
                final TLRPC.LangPackString string = (TLRPC.LangPackString) vector.objects.get(0);
                if (string instanceof TLRPC.TL_langPackString) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!destroyed) {
                            switchLanguageTextView.setText(string.value);
                            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                            preferences.edit().putString("language_showed2", finalSystemLang.toLowerCase()).apply();
                        }
                    });
                }
            }
        }, ConnectionsManager.RequestFlagWithoutLogin);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.suggestedLangpack || id == NotificationCenter.configLoaded) {
            checkContinueText();
        }
    }

    public IntroActivity setOnLogout() {
        isOnLogout = true;
        return this;
    }

    @Override
    public AnimatorSet onCustomTransitionAnimation(boolean isOpen, Runnable callback) {
        if (isOnLogout) {
            AnimatorSet set = new AnimatorSet().setDuration(50);
            set.playTogether(ValueAnimator.ofFloat());
            return set;
        }
        return null;
    }

    private class IntroAdapter extends PagerAdapter {
        @Override
        public int getCount() {
            return titles.length;
        }

        @NonNull
        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            Context ctx = container.getContext();
            FrameLayout frameLayout = new FrameLayout(ctx);

            TextView headerTextView = new TextView(ctx);
            headerTextView.setTextColor(PC_TEXT);
            headerTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            headerTextView.setTypeface(AndroidUtilities.bold());
            headerTextView.setGravity(Gravity.CENTER);
            headerTextView.setText(titles[position]);
            frameLayout.addView(headerTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 24, 0, 24, 0));

            TextView messageTextView = new TextView(ctx);
            messageTextView.setTextColor(PC_TEXT2);
            messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            messageTextView.setLineSpacing(dpf2(2.33f), 1f);
            messageTextView.setGravity(Gravity.CENTER);
            messageTextView.setText(AndroidUtilities.replaceTags(messages[position]));
            frameLayout.addView(messageTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 24, 48, 24, 0));

            container.addView(frameLayout, 0);
            return frameLayout;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        @Override
        public boolean isViewFromObject(View view, @NonNull Object object) {
            return view.equals(object);
        }

        @Override
        public void restoreState(Parcelable arg0, ClassLoader arg1) {
        }

        @Override
        public Parcelable saveState() {
            return null;
        }
    }
}
