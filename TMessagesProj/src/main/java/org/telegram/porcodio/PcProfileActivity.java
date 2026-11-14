package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActivity;
import org.telegram.ui.Components.voip.VoIPHelper;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;

/**
 * Scheda profilo Porcodio (ridisegnata): intestazione con avatar e targhetta, azioni rapide, pannelli con le informazioni,
 * Bacheca (social interno), media condivisi, blocco chat con impronta, nota privata. Il profilo completo resta a un tocco.
 */
public class PcProfileActivity extends PcListFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int T_HEADER = 0, T_ACTIONS = 1, T_SECTION = 2, T_CARD = 3, T_SWITCH = 4, T_SPACE = 5;

    private static class Item {
        int type;
        String title, value;
        Runnable click;
        boolean checked;

        Item(int type, String title, String value, Runnable click) {
            this.type = type;
            this.title = title;
            this.value = value;
            this.click = click;
        }
    }

    private final long userId;
    private TLRPC.User user;
    private TLRPC.UserFull full;
    private final ArrayList<Item> items = new ArrayList<>();
    private long boardChat;
    private boolean boardLooking;
    private final Bundle originalArgs;

    public PcProfileActivity(Bundle args) {
        super(args);
        originalArgs = args;
        userId = args.getLong("user_id", 0);
    }

    @Override
    public boolean onFragmentCreate() {
        user = getMessagesController().getUser(userId);
        full = getMessagesController().getUserFull(userId);
        getNotificationCenter().addObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        if (user != null) {
            getMessagesController().loadFullUser(user, classGuid, true);
        }
        boardChat = PcBoard.knownBoard(currentAccount, userId);
        rebuild();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.userInfoDidLoad) {
            if ((Long) args[0] == userId) {
                full = (TLRPC.UserFull) args[1];
                afterInfo();
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            user = getMessagesController().getUser(userId);
            rebuild();
            notifyData();
        }
    }

    private void afterInfo() {
        if (full != null && PcBoard.aboutHasMarker(full.about) && boardChat == 0 && !boardLooking) {
            boardLooking = true;
            PcBoard.discover(currentAccount, userId, chat -> {
                boardLooking = false;
                boardChat = chat == null ? 0 : chat;
                rebuild();
                notifyData();
            });
        }
        rebuild();
        notifyData();
    }

    private boolean isAppUser() {
        return PcBoard.isAppUser(currentAccount, userId, full != null ? full.about : null);
    }

    private String cleanAbout() {
        if (full == null || full.about == null) {
            return "";
        }
        return full.about.replace(PcBoard.MARKER, "").trim();
    }

    private void rebuild() {
        items.clear();
        items.add(new Item(T_HEADER, null, null, null));
        items.add(new Item(T_ACTIONS, null, null, null));
        items.add(new Item(T_SPACE, null, null, null));

        final String about = cleanAbout();
        final String username = user != null ? UserObject.getPublicUsername(user) : null;
        final String phone = user != null && !TextUtils.isEmpty(user.phone) ? PhoneFormat.getInstance().format("+" + user.phone) : null;
        if (!about.isEmpty() || !TextUtils.isEmpty(username) || phone != null) {
            items.add(new Item(T_SECTION, LocaleController.getString(R.string.PcProfileAbout), null, null));
            if (!about.isEmpty()) {
                items.add(new Item(T_CARD, LocaleController.getString(R.string.PcProfileBio), about, () -> AndroidUtilities.addToClipboard(about)));
            }
            if (!TextUtils.isEmpty(username)) {
                items.add(new Item(T_CARD, LocaleController.getString(R.string.PcProfileUsername), "@" + username, () -> AndroidUtilities.addToClipboard("@" + username)));
            }
            if (phone != null) {
                items.add(new Item(T_CARD, LocaleController.getString(R.string.PcProfilePhone), phone, () -> AndroidUtilities.addToClipboard(phone)));
            }
        }

        items.add(new Item(T_SECTION, LocaleController.getString(R.string.PcProfileBoard), null, null));
        if (boardChat != 0) {
            items.add(new Item(T_CARD, LocaleController.getString(R.string.PcProfileSeeBoard), LocaleController.getString(R.string.PcProfileAppUser), this::openBoard));
        } else {
            items.add(new Item(T_CARD, LocaleController.getString(R.string.PcProfileBoard),
                LocaleController.getString(boardLooking ? R.string.PcProfileBoardLooking : R.string.PcProfileBoardNone), null));
        }

        items.add(new Item(T_SECTION, LocaleController.getString(R.string.PcProfileMedia), null, null));
        items.add(new Item(T_CARD, LocaleController.getString(R.string.PcProfileMedia), LocaleController.getString(R.string.PcProfileLinks), this::openMedia));

        items.add(new Item(T_SECTION, LocaleController.getString(R.string.PcProfileMore), null, null));
        final String note = PcNotes.get(userId);
        items.add(new Item(T_CARD, LocaleController.getString(R.string.PcNoteTitle), note.isEmpty() ? LocaleController.getString(R.string.PcNoteEmpty) : note, this::editNote));
        Item lock = new Item(T_SWITCH, LocaleController.getString(R.string.PcProfileLock), LocaleController.getString(R.string.PcProfileLockInfo), this::toggleLock);
        lock.checked = PcLock.isLocked(userId);
        items.add(lock);
        items.add(new Item(T_CARD, LocaleController.getString(R.string.PcProfileFull), null, this::openStock));
        items.add(new Item(T_SPACE, null, null, null));
    }

    // ---- azioni
    private void openChat() {
        if (originalArgs.getBoolean("from_chat", false)) {
            finishFragment(); // il profilo è stato aperto dalla chat: torno lì invece di duplicarla
            return;
        }
        Bundle b = new Bundle();
        b.putLong("user_id", userId);
        presentFragment(new ChatActivity(b), true);
    }

    private void call(boolean video) {
        if (user == null || getParentActivity() == null) {
            return;
        }
        VoIPHelper.startCall(user, video, full != null && full.video_calls_available, getParentActivity(), full, getAccountInstance());
    }

    private void share() {
        final String username = user != null ? UserObject.getPublicUsername(user) : null;
        if (TextUtils.isEmpty(username) || getParentActivity() == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, UserObject.getUserName(user) + " https://t.me/" + username);
        getParentActivity().startActivity(Intent.createChooser(i, LocaleController.getString(R.string.PcProfileShare)));
    }

    private void openBoard() {
        if (boardChat == 0) {
            return;
        }
        Bundle b = new Bundle();
        b.putLong("author", userId);
        presentFragment(new PcFeedActivity(b));
    }

    private void openMedia() {
        Bundle b = new Bundle();
        b.putLong("dialog_id", userId);
        presentFragment(new MediaActivity(b, null));
    }

    private void openStock() {
        Bundle b = new Bundle(originalArgs);
        b.putBoolean(PcHook.STOCK, true);
        presentFragment(new ProfileActivity(b));
    }

    private void toggleLock() {
        final boolean now = PcLock.isLocked(userId);
        if (!PcLock.isAvailable()) {
            PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcProfileLockUnavailable));
            return;
        }
        // attivare o togliere il blocco richiede sempre l'autenticazione
        PcLock.authenticate(userId, () -> {
            PcLock.setLocked(userId, !now);
            rebuild();
            notifyData();
        }, null);
    }

    private void editNote() {
        if (getParentActivity() == null) {
            return;
        }
        final EditText edit = new EditText(getParentActivity());
        edit.setText(PcNotes.get(userId));
        edit.setHint(LocaleController.getString(R.string.PcNoteHint));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(3);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcNoteTitle));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcNoteSave), (d, w) -> {
            PcNotes.set(userId, edit.getText().toString());
            rebuild();
            notifyData();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    // ---- lista
    @Override
    protected String screenTitle() {
        return LocaleController.getString(R.string.PcProfileAbout);
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
        if (position < 0 || position >= items.size()) {
            return false;
        }
        Item it = items.get(position);
        return it.type != T_HEADER && it.type != T_ACTIONS && it.type != T_SECTION && it.type != T_SPACE && it.click != null;
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
            case T_HEADER:
                return new HeaderRow(context);
            case T_ACTIONS:
                return new ActionsRow(context);
            case T_SECTION: {
                TextView t = PcUi.label(context, "", 11, true, Theme.key_windowBackgroundWhiteGrayText);
                t.setAllCaps(true);
                t.setLetterSpacing(0.08f);
                t.setPadding(dp(18), dp(14), dp(16), dp(6));
                t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return t;
            }
            case T_SPACE: {
                View s = new View(context);
                s.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(12)));
                return s;
            }
            default:
                return new CardRow(context, viewType == T_SWITCH);
        }
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Item it = items.get(position);
        switch (viewType) {
            case T_HEADER:
                ((HeaderRow) row).bind();
                break;
            case T_SECTION:
                ((TextView) row).setText(it.title);
                break;
            case T_CARD:
            case T_SWITCH:
                ((CardRow) row).bind(it);
                break;
            default:
                break;
        }
    }

    private class HeaderRow extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView name, status, userTag;
        private final LinearLayout badges;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();

        HeaderRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(dp(14), dp(14), dp(14), dp(14));
            box.setBackground(PcUi.panel(true));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(40));
            box.addView(avatar, LayoutHelper.createLinear(96, 96));
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            name = PcUi.label(context, "", 21, true, Theme.key_windowBackgroundWhiteBlackText);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            status = PcUi.label(context, "", 14, false, Theme.key_windowBackgroundWhiteGrayText);
            status.setSingleLine(true);
            userTag = PcUi.label(context, "", 14, false, Theme.key_windowBackgroundWhiteGrayText);
            userTag.setSingleLine(true);
            badges = new LinearLayout(context);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            col.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            col.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            col.addView(userTag, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            col.addView(badges, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            box.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 8, 12, 4));
        }

        void bind() {
            if (user == null) {
                return;
            }
            avatarDrawable.setInfo(currentAccount, user);
            avatar.setForUserOrChat(user, avatarDrawable);
            name.setText(UserObject.getUserName(user));
            status.setText(LocaleController.formatUserStatus(currentAccount, user));
            String un = UserObject.getPublicUsername(user);
            userTag.setText(TextUtils.isEmpty(un) ? "" : "@" + un);
            userTag.setVisibility(TextUtils.isEmpty(un) ? GONE : VISIBLE);
            badges.removeAllViews();
            if (isAppUser()) {
                badges.addView(PcUi.badge(getContext(), LocaleController.getString(R.string.PcBadge)));
            }
            badges.setVisibility(badges.getChildCount() == 0 ? GONE : VISIBLE);
        }
    }

    private class ActionsRow extends LinearLayout {
        ActionsRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(8), dp(4), dp(8), dp(4));
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            addView(PcUi.button(context, LocaleController.getString(R.string.PcProfileMessage), true, v -> openChat()), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 4, 0));
            addView(PcUi.button(context, LocaleController.getString(R.string.PcProfileCall), false, v -> call(false)), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 4, 0));
            addView(PcUi.button(context, LocaleController.getString(R.string.PcProfileVideo), false, v -> call(true)), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 4, 0));
            addView(PcUi.button(context, LocaleController.getString(R.string.PcProfileShare), false, v -> share()), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 4, 0));
        }
    }

    private class CardRow extends FrameLayout {
        private final TextView title, value, chip;
        private final boolean isSwitch;

        CardRow(Context context, boolean isSwitch) {
            super(context);
            this.isSwitch = isSwitch;
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            box.setBackground(PcUi.panel(true));
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            title = PcUi.label(context, "", 12, true, Theme.key_windowBackgroundWhiteGrayText);
            title.setAllCaps(true);
            title.setLetterSpacing(0.06f);
            value = PcUi.label(context, "", 15, false, Theme.key_windowBackgroundWhiteBlackText);
            value.setMaxLines(6);
            value.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            col.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            box.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            chip = PcUi.label(context, "", 11, true, Theme.key_windowBackgroundWhiteBlackText);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            box.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            chip.setVisibility(isSwitch ? VISIBLE : GONE);
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 4, 12, 4));
        }

        void bind(Item it) {
            title.setText(it.title);
            value.setText(it.value == null ? "" : it.value);
            value.setVisibility(it.value == null ? GONE : VISIBLE);
            if (isSwitch) {
                chip.setText(LocaleController.getString(it.checked ? R.string.PcOn : R.string.PcOff).toUpperCase());
                if (it.checked) {
                    chip.setBackground(PcUi.filled(Theme.getColor(Theme.key_chats_actionBackground)));
                    chip.setTextColor(Theme.getColor(Theme.key_chats_actionIcon));
                } else {
                    chip.setBackground(PcUi.panel(false));
                    chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                }
            }
        }
    }
}
