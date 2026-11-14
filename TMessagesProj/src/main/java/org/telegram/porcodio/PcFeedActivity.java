package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Feed interno (solo per chi usa l'app): post delle Bacheche degli amici e la tua, come righe di un social.
 * Con l'argomento "author" mostra solo la Bacheca di quella persona.
 */
public class PcFeedActivity extends PcListFragment implements org.telegram.ui.MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {
    private static final int T_BOARD = 0, T_POST = 1, T_NOTE = 2, T_CHIPS = 3, T_STORIES = 4, T_GRID = 5;
    private static final int REQ_PHOTO = 7101, REQ_VIDEO = 7102;
    private static boolean following() {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("pc_social", Context.MODE_PRIVATE).getBoolean("following", false);
    }

    private static void setFollowing(boolean v) {
        org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("pc_social", Context.MODE_PRIVATE).edit().putBoolean("following", v).apply();
    }

    private int section; // 0 feed, 1 storie, 2 reel, 3 esplora, 4 attività (solo nella scheda Social)

    private final long onlyAuthor;
    private final ArrayList<PcBoard.Post> posts = new ArrayList<>();
    private boolean loading = true, finding;
    private String findNote;

    public PcFeedActivity(Bundle args) {
        super(args);
        onlyAuthor = args != null ? args.getLong("author", 0) : 0;
    }

    {
        // il primo caricamento parte alla creazione del frammento
    }

    private void reload() {
        reload(false);
    }

    private String lastSig = "";

    private void reload(final boolean quiet) {
        if (!quiet) {
            loading = true;
        }
        for (long oid : PcRanking.officialIds()) {
            if (PcBoard.knownBoard(currentAccount, oid) == 0) {
                PcBoard.discover(currentAccount, oid, null);
            }
        }
        PcBoard.loadFeed(currentAccount, list -> {
            loading = false;
            StringBuilder sig = new StringBuilder();
            for (PcBoard.Post p : list) {
                sig.append(p.chatId).append(':').append(p.msgId).append(':').append(p.likes).append(':').append(p.comments).append(',');
            }
            final boolean same = quiet && sig.toString().equals(lastSig);
            lastSig = sig.toString();
            if (pull != null) {
                pull.setRefreshing(false);
            }
            if (same) {
                return;
            }
            posts.clear();
            for (PcBoard.Post p : list) {
                if ((onlyAuthor == 0 || p.authorId == onlyAuthor) && !PcBoard.isSpecial(p)) {
                    posts.add(p);
                }
            }
            if (pull != null) {
                pull.setRefreshing(false);
            }
            notifyData();
        });
        if (!quiet) {
            notifyData();
        }
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    private boolean tabMode() {
        return hasMainTabs;
    }

    @Override
    protected String screenTitle() {
        if (tabMode()) {
            return LocaleController.getString(R.string.PcTabSocial);
        }
        if (onlyAuthor != 0) {
            TLRPC.User u = getMessagesController().getUser(onlyAuthor);
            return u == null ? LocaleController.getString(R.string.PcFeedTitle) : LocaleController.formatString(R.string.PcFeedFrom, UserObject.getFirstName(u));
        }
        return LocaleController.getString(R.string.PcFeedTitle);
    }

    @Override
    protected boolean withPullToRefresh() {
        return true;
    }

    @Override
    protected void onPullRefresh() {
        reload();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return v;
    }

    private boolean showBoardCard() {
        return onlyAuthor == 0;
    }

    private static class Row {
        int type;
        PcBoard.Post post;
        ArrayList<PcBoard.Post> grid;

        Row(int type) {
            this.type = type;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    private static boolean isVideo(PcBoard.Post p) {
        if (p.text != null && p.text.toLowerCase(java.util.Locale.ROOT).contains("#reel") && p.message != null && p.message.media instanceof TLRPC.TL_messageMediaDocument) {
            return true;
        }
        return p.message != null && p.message.media instanceof TLRPC.TL_messageMediaDocument && p.message.media.document != null
            && org.telegram.messenger.MessageObject.isVideoDocument(p.message.media.document);
    }

    private static boolean isPhoto(PcBoard.Post p) {
        return p.message != null && p.message.media instanceof TLRPC.TL_messageMediaPhoto && p.message.media.photo != null;
    }

    private void rebuild() {
        rows.clear();
        if (tabMode()) {
            rows.add(new Row(T_STORIES));
            rows.add(new Row(T_CHIPS));
        }
        if (showBoardCard()) {
            rows.add(new Row(T_BOARD));
        }
        final long me = UserConfig.getInstance(currentAccount).getClientUserId();
        if (!tabMode() || section == 0) {
            for (PcBoard.Post p : PcRanking.rankFeed(currentAccount, posts, following())) {
                Row r = new Row(T_POST);
                r.post = p;
                rows.add(r);
            }
        } else if (section == 2) {
            ArrayList<PcBoard.Post> vids = new ArrayList<>();
            for (PcBoard.Post p : posts) {
                if (isVideo(p)) {
                    vids.add(p);
                }
            }
            for (PcBoard.Post p : PcRanking.rankFeed(currentAccount, vids, following())) {
                Row r = new Row(T_POST);
                r.post = p;
                rows.add(r);
            }
        } else if (section == 3) {
            ArrayList<PcBoard.Post> media = new ArrayList<>();
            for (PcBoard.Post p : posts) {
                if (isPhoto(p) || isVideo(p)) {
                    media.add(p);
                }
            }
            Row g = null;
            for (PcBoard.Post p : PcRanking.rankExplore(currentAccount, media)) {
                if (g == null || g.grid.size() == 3) {
                    g = new Row(T_GRID);
                    g.grid = new ArrayList<>();
                    rows.add(g);
                }
                g.grid.add(p);
            }
        } else if (section == 4) {
            for (PcBoard.Post p : posts) {
                if (p.authorId == me) {
                    Row r = new Row(T_POST);
                    r.post = p;
                    rows.add(r);
                }
            }
        }
        rows.add(new Row(T_NOTE));
    }

    @Override
    protected void notifyData() {
        rebuild();
        super.notifyData();
    }

    @Override
    public boolean onFragmentCreate() {
        reload();
        getNotificationCenter().addObserver(this, NotificationCenter.storiesUpdated);
        getNotificationCenter().addObserver(this, NotificationCenter.messageSendError);
        try {
            getMessagesController().getStoriesController().loadStories();
        } catch (Throwable ignore) {
        }
        PcRanking.resolveOfficialUsername(currentAccount);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesUpdated);
        getNotificationCenter().removeObserver(this, NotificationCenter.messageSendError);
        super.onFragmentDestroy();
    }

    private long watchUntil;

    private void watchSend() {
        watchUntil = System.currentTimeMillis() + 120000;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messageSendError) {
            if (System.currentTimeMillis() < watchUntil) {
                PcBoard.fail("send", "invio rifiutato o interrotto (messageSendError)");
                PcUi.toast(getParentActivity(), "Pubblicazione non riuscita: l'invio e' stato rifiutato o interrotto. Controlla la connessione e riprova.");
            }
            return;
        }
        if (id == NotificationCenter.storiesUpdated && tabMode()) {
            notifyData();
        }
    }

    @Override
    protected int itemCount() {
        if (rows.isEmpty()) {
            rebuild();
        }
        return rows.size();
    }

    @Override
    protected int itemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    protected boolean isRowEnabled(int position) {
        return false;
    }

    @Override
    protected View createRow(Context context, int viewType) {
        if (viewType == T_STORIES) {
            return new StoriesRow(context);
        }
        if (viewType == T_GRID) {
            return new GridRow(context);
        }
        if (viewType == T_CHIPS) {
            ChipsRow c = new ChipsRow(context);
            c.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return c;
        }
        if (viewType == T_BOARD) {
            return new BoardRow(context);
        }
        if (viewType == T_POST) {
            return new PostRow(context);
        }
        TextView t = PcUi.label(context, "", 14, false, Theme.key_windowBackgroundWhiteGrayText);
        t.setTypeface(PcUi.serif(false, true));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(24), dp(24), dp(tabMode() ? org.telegram.ui.DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 24 : 96));
        t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return t;
    }

    @Override
    protected void bindRow(View row, int viewType, int position) {
        Row r = rows.get(position);
        if (viewType == T_STORIES) {
            ((StoriesRow) row).bind();
        } else if (viewType == T_GRID) {
            ((GridRow) row).bind(r.grid);
        } else if (viewType == T_CHIPS) {
            ((ChipsRow) row).bind();
        } else if (viewType == T_BOARD) {
            ((BoardRow) row).bind();
        } else if (viewType == T_POST) {
            ((PostRow) row).bind(r.post);
        } else {
            TextView t = (TextView) row;
            boolean emptySection = true;
            for (Row x : rows) {
                if (x.type == T_POST || x.type == T_GRID) {
                    emptySection = false;
                    break;
                }
            }
            if (tabMode() && section == 1) {
                t.setText(LocaleController.getString(R.string.PcSocialStoriesNote));
            } else if (loading) {
                t.setText(LocaleController.getString(R.string.PcFeedLoading));
            } else if (emptySection && tabMode() && section != 0) {
                t.setText(LocaleController.getString(R.string.PcSocialSectionEmpty));
            } else if (emptySection) {
                t.setText(LocaleController.getString(R.string.PcFeedEmpty));
            } else {
                t.setText("");
            }
        }
    }

    // ---- pubblicazione: post con foto, reel (video), storia
    private String pendingCaption;

    private void onPlus() {
        if (getParentActivity() == null) {
            return;
        }
        if (!PcBoard.isEnabled(currentAccount)) {
            activate();
            return;
        }
        CharSequence[] items = {
            LocaleController.getString(R.string.PcPlusPhoto),
            LocaleController.getString(R.string.PcPlusReel),
            LocaleController.getString(R.string.PcPlusStory),
            LocaleController.getString(R.string.PcPlusText)};
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcPlusTitle));
        b.setItems(items, (d, which) -> {
            if (which == 0 || which == 1) {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                i.setType(which == 0 ? "image/*" : "video/*");
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, which == 0 ? REQ_PHOTO : REQ_VIDEO);
            } else if (which == 2) {
                try {
                    org.telegram.ui.Stories.recorder.StoryRecorder.getInstance(getParentActivity(), currentAccount).open(null);
                } catch (Throwable t) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
                }
            } else {
                write();
            }
        });
        showDialog(b.create());
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if ((requestCode != REQ_PHOTO && requestCode != REQ_VIDEO) || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final boolean video = requestCode == REQ_VIDEO;
        final android.net.Uri uri = data.getData();
        PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcPlusPreparing));
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final String path = copyToCache(uri, video);
            AndroidUtilities.runOnUIThread(() -> {
                if (path == null) {
                    PcUi.toast(getParentActivity(), "Pubblicazione non riuscita: impossibile leggere il file scelto");
                } else {
                    askCaption(path, video);
                }
            });
        });
    }

    private String copyToCache(android.net.Uri uri, boolean video) {
        try (java.io.InputStream in = org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            java.io.File f = new java.io.File(org.telegram.messenger.ApplicationLoader.applicationContext.getCacheDir(), "pc_post_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg"));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private void askCaption(final String path, final boolean video) {
        if (getParentActivity() == null) {
            return;
        }
        final EditText edit = new EditText(getParentActivity());
        edit.setHint(LocaleController.getString(R.string.PcPlusCaption));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(2);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(video ? R.string.PcPlusReel : R.string.PcPlusPhoto));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardPublish), (d, w) -> {
            org.telegram.messenger.SendMessagesHelper.SendingMediaInfo info = new org.telegram.messenger.SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.isVideo = video;
            String cap = edit.getText().toString().trim();
            if (video && !cap.toLowerCase(java.util.Locale.ROOT).contains("#reel")) {
                cap = (cap.isEmpty() ? "" : cap + " ") + "#reel"; // i reel si riconoscono dal tag
            }
            info.caption = cap;
            ArrayList<org.telegram.messenger.SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
            list.add(info);
            final long chat = PcBoard.myBoardChat(currentAccount);
            PcBoard.withBoard(currentAccount, () -> {
                org.telegram.messenger.SendMessagesHelper.prepareSendingMedia(org.telegram.messenger.AccountInstance.getInstance(currentAccount), list, -chat, null, null, null, null,
                    false, false, null, true, 0, 0, 0, false, null, org.telegram.messenger.SendMessageChatArguments.EMPTY, 0, false, 0, 0, null);
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardPublished));
                watchSend();
                // il caricamento di un video richiede tempo: ricarico piu' volte finche' il post compare
                for (int delay : new int[]{3000, 8000, 15000, 30000, 60000}) {
                    AndroidUtilities.runOnUIThread(() -> reload(true), delay);
                }
            }, ok -> PcUi.toast(getParentActivity(), "Pubblicazione non riuscita: " + PcBoard.lastError));
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    // ---- azioni Bacheca
    private void activate() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcBoardOffTitle));
        b.setMessage(LocaleController.getString(R.string.PcBoardOffText).replace("{tag}", PcBoard.MARKER));
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardOn), (d, w) -> {
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            String title = (me != null ? UserObject.getUserName(me) : "") + " - " + LocaleController.getString(R.string.PcProfileBoard);
            PcBoard.enable(this, title, ok -> {
                if (!ok) {
                    PcUi.toast(getParentActivity(), "Pubblicazione non riuscita: " + PcBoard.lastError);
                }
                notifyData();
                reload();
            });
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void deactivate() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcBoardConfirmOffTitle));
        b.setMessage(LocaleController.getString(R.string.PcBoardConfirmOffText));
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardOffBtn), (d, w) -> PcBoard.disable(this, ok -> {
            if (!ok) {
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
            }
            notifyData();
            reload();
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void write() {
        if (getParentActivity() == null) {
            return;
        }
        final EditText edit = new EditText(getParentActivity());
        edit.setHint(LocaleController.getString(R.string.PcBoardHint));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(4);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        FrameLayout holder = new FrameLayout(getParentActivity());
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PcBoardWrite));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcBoardPublish), (d, w) -> {
            String text = edit.getText().toString();
            if (!text.trim().isEmpty()) {
                PcBoard.publish(currentAccount, text, ok -> {
                    if (ok) {
                        PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardPublished));
                        watchSend();
                        for (int delay : new int[]{2000, 6000, 15000}) {
                            AndroidUtilities.runOnUIThread(() -> reload(true), delay);
                        }
                    } else {
                        PcUi.toast(getParentActivity(), "Pubblicazione non riuscita: " + PcBoard.lastError);
                    }
                });
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void openMyBoard() {
        long chat = PcBoard.myBoardChat(currentAccount);
        if (chat == 0) {
            return;
        }
        Bundle b = new Bundle();
        b.putLong("chat_id", chat);
        presentFragment(new ChatActivity(b));
    }

    private void findFriends() {
        if (finding) {
            return;
        }
        if (PcBoard.budgetLeft(currentAccount) <= 0) {
            findNote = LocaleController.getString(R.string.PcBoardNoBudget);
            notifyData();
            return;
        }
        finding = true;
        findNote = LocaleController.getString(R.string.PcBoardFinding);
        notifyData();
        PcBoard.findFriends(currentAccount, res -> {
            finding = false;
            findNote = res[0] > 0 ? LocaleController.formatString(R.string.PcBoardFound, res[0]) : LocaleController.getString(R.string.PcBoardNoFound);
            notifyData();
            if (res[0] > 0) {
                reload();
            }
        });
    }

    private void showPostMenu(final PcBoard.Post p) {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] items = {
            LocaleController.getString(R.string.PcWhyTitle),
            LocaleController.getString(R.string.PcSavePost),
            LocaleController.getString(R.string.PcSharePost),
            LocaleController.getString(R.string.PcNotInterested),
            LocaleController.getString(R.string.PcHideAuthor),
            LocaleController.getString(R.string.PcReportPost)};
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setItems(items, (d, which) -> {
            if (which == 0) {
                AlertDialog.Builder w = new AlertDialog.Builder(getParentActivity());
                w.setTitle(LocaleController.getString(R.string.PcWhyTitle));
                w.setMessage(PcRanking.whyText(currentAccount, p, tabMode() && section == 3));
                w.setPositiveButton(LocaleController.getString(R.string.OK), null);
                w.setNeutralButton(LocaleController.getString(R.string.PcInterestsManage), (dd, ww) -> presentFragment(new PcInterestsActivity()));
                showDialog(w.create());
            } else if (which == 1) {
                savePost(p);
            } else if (which == 2) {
                sharePost(p);
            } else if (which == 3) {
                PcInterests.hidePost(p);
                notifyData();
            } else if (which == 4) {
                PcInterests.hideAuthor(p.authorId);
                notifyData();
            } else {
                PcInterests.report(p);
                notifyData();
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcReportedThanks));
            }
        });
        showDialog(b.create());
    }

    /** Salvataggio: copia nei Messaggi salvati; il segnale "salvato" conta solo se il server accetta l'inoltro. */
    private void savePost(final PcBoard.Post p) {
        PcBoard.saveToSaved(currentAccount, p, ok -> {
            if (ok) {
                PcInterests.recordOnce(p, "save", PcInterests.W_SAVE);
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcSavedOk));
            } else {
                PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcSavedFail));
            }
        });
    }

    /** Condivisione: foglio di condivisione di sistema; il segnale conta solo quando l'utente sceglie davvero una destinazione. */
    private void sharePost(final PcBoard.Post p) {
        if (getParentActivity() == null) {
            return;
        }
        final android.app.Activity act = getParentActivity();
        final String action = "org.telegram.porcodio.SHARED." + p.chatId + "." + p.msgId;
        final android.content.BroadcastReceiver rcv = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                PcInterests.recordOnce(p, "share", PcInterests.W_SHARE);
                try {
                    c.getApplicationContext().unregisterReceiver(this);
                } catch (Throwable ignore) {
                }
            }
        };
        try {
            Context app = act.getApplicationContext();
            android.content.IntentFilter f = new android.content.IntentFilter(action);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(rcv, f);
            }
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            String body = (TextUtils.isEmpty(p.text) ? "" : p.text + "\n") + "https://t.me/c/" + p.chatId + "/" + p.msgId;
            send.putExtra(android.content.Intent.EXTRA_TEXT, body);
            android.content.Intent cb = new android.content.Intent(action).setPackage(app.getPackageName());
            int fl = android.app.PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(app, 0, cb, fl);
            act.startActivity(android.content.Intent.createChooser(send, LocaleController.getString(R.string.PcSharePost), pi.getIntentSender()));
        } catch (Throwable t) {
            try {
                act.getApplicationContext().unregisterReceiver(rcv);
            } catch (Throwable ignore) {
            }
        }
    }

    // commenti in attesa di verifica: appena si torna nel feed si controlla se c'e' un commento mio piu' recente del tocco
    private final ArrayList<Object[]> pendingComments = new ArrayList<>();

    private void openForComment(PcBoard.Post p) {
        pendingComments.add(new Object[]{p, (int) (System.currentTimeMillis() / 1000)});
        openPost(p);
    }

    private void openAuthorProfile(PcBoard.Post p) {
        if (p == null || p.authorId == 0) {
            return;
        }
        PcInterests.recordOnce(p, "profile", PcInterests.W_PROFILE_VISIT);
        Bundle b = new Bundle();
        b.putLong("user_id", p.authorId);
        presentFragment(new org.telegram.ui.ProfileActivity(b));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingComments.isEmpty()) {
            return;
        }
        final ArrayList<Object[]> todo = new ArrayList<>(pendingComments);
        pendingComments.clear();
        for (Object[] o : todo) {
            final PcBoard.Post p = (PcBoard.Post) o[0];
            PcBoard.hasMyCommentSince(currentAccount, p, (Integer) o[1], mine -> {
                if (mine) {
                    PcInterests.recordOnce(p, "comment", PcInterests.W_COMMENT);
                }
            });
        }
    }

    private void openPost(PcBoard.Post p) {
        PcInterests.record(p, PcInterests.W_OPEN);
        Bundle b = new Bundle();
        b.putLong("chat_id", p.chatId);
        b.putInt("message_id", p.msgId);
        presentFragment(new ChatActivity(b));
    }

    private class ChipsRow extends FrameLayout {
        ChipsRow(Context context) {
            super(context);
        }

        void bind() {
            removeAllViews();
            final Context context = getContext();
            android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(context);
            sv.setHorizontalScrollBarEnabled(false);
            LinearLayout r = new LinearLayout(context);
            TextView plus = PcUi.button(context, "+", true, v -> onPlus());
            plus.setTextSize(20);
            r.addView(plus, LayoutHelper.createLinear(44, 38, 0, 0, 10, 0));
            TextView mode = PcUi.button(context, LocaleController.getString(following() ? R.string.PcModeFollowing : R.string.PcModeForYou), false, v -> {
                setFollowing(!following());
                notifyData();
            });
            r.addView(mode, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, 0, 0, 10, 0));
            final int[] names = {R.string.PcSocialFeed, R.string.PcSocialStories, R.string.PcSocialReels, R.string.PcSocialExplore, R.string.PcSocialActivity};
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                TextView t = PcUi.button(context, LocaleController.getString(names[i]), section == i, v -> {
                    section = idx;
                    notifyData();
                });
                r.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, 0, 0, 6, 0));
            }
            sv.addView(r);
            addView(sv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 8, 12, 4));
        }
    }

    /** Riga delle storie: "La mia storia" con + e i contatti con storie (viewer nativo dell'API). */
    private class StoriesRow extends FrameLayout {
        StoriesRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        }

        private View item(Context context, String label, TLRPC.User user, boolean plus, boolean unread, View.OnClickListener click) {
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout holder = new FrameLayout(context);
            holder.setBackground(ring(unread));
            BackupImageView img = new BackupImageView(context);
            img.setRoundRadius(dp(30));
            if (plus) {
                TextView p = PcUi.label(context, "+", 30, false, Theme.key_windowBackgroundWhiteBlackText);
                p.setGravity(Gravity.CENTER);
                p.setBackground(PcUi.filled(0xFF1A1A1A));
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(0xFF1A1A1A);
                p.setBackground(g);
                holder.addView(p, LayoutHelper.createFrame(60, 60, Gravity.CENTER));
            } else {
                AvatarDrawable ad = new AvatarDrawable();
                if (user != null) {
                    ad.setInfo(currentAccount, user);
                    img.setForUserOrChat(user, ad);
                }
                holder.addView(img, LayoutHelper.createFrame(60, 60, Gravity.CENTER));
            }
            col.addView(holder, LayoutHelper.createLinear(68, 68));
            TextView t = PcUi.label(context, label, 12, false, Theme.key_windowBackgroundWhiteBlackText);
            t.setTypeface(PcUi.serif(false, false));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            col.addView(t, LayoutHelper.createLinear(72, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            col.setOnClickListener(click);
            return col;
        }

        private android.graphics.drawable.Drawable ring(boolean unread) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(0);
            g.setStroke(Math.max(2, dp(1.5f)), unread ? 0xFFF3EEE6 : 0x55F3EEE6);
            return g;
        }

        void bind() {
            removeAllViews();
            final Context context = getContext();
            android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(context);
            sv.setHorizontalScrollBarEnabled(false);
            LinearLayout r = new LinearLayout(context);
            r.addView(item(context, LocaleController.getString(R.string.PcMyStory), null, true, false, v -> {
                try {
                    org.telegram.ui.Stories.recorder.StoryRecorder.getInstance(getParentActivity(), currentAccount).open(null);
                } catch (Throwable t) {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcBoardFailed));
                }
            }), LayoutHelper.createLinear(76, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
            try {
                ArrayList<org.telegram.tgnet.tl.TL_stories.PeerStories> list = getMessagesController().getStoriesController().getDialogListStories();
                final long me = UserConfig.getInstance(currentAccount).getClientUserId();
                ArrayList<org.telegram.tgnet.tl.TL_stories.PeerStories> sorted = new ArrayList<>(list);
                java.util.Collections.sort(sorted, (a, b) -> Boolean.compare(
                    PcRanking.isOfficial(org.telegram.messenger.DialogObject.getPeerDialogId(b.peer)),
                    PcRanking.isOfficial(org.telegram.messenger.DialogObject.getPeerDialogId(a.peer))));
                for (org.telegram.tgnet.tl.TL_stories.PeerStories ps : sorted) {
                    final long did = org.telegram.messenger.DialogObject.getPeerDialogId(ps.peer);
                    if (did <= 0 || did == me) {
                        continue;
                    }
                    TLRPC.User u = getMessagesController().getUser(did);
                    if (u == null) {
                        continue;
                    }
                    boolean unread = getMessagesController().getStoriesController().hasUnreadStories(did);
                    final boolean off = PcRanking.isOfficial(did);
                    r.addView(item(context, off ? LocaleController.getString(R.string.PcOfficial) : UserObject.getFirstName(u), u, false, unread, v -> {
                        if (getParentActivity() != null) {
                            getOrCreateStoryViewer().open(getParentActivity(), did, org.telegram.ui.Stories.StoriesListPlaceProvider.of(listView));
                        }
                    }), LayoutHelper.createLinear(76, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
                }
            } catch (Throwable ignore) {
            }
            sv.addView(r);
            addView(sv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 14, 10, 8, 2));
        }
    }

    /** Tre miniature per riga (Esplora / griglia). */
    private class GridRow extends LinearLayout {
        GridRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            setPadding(dp(1), dp(1), dp(1), dp(1));
        }

        void bind(ArrayList<PcBoard.Post> items) {
            removeAllViews();
            final int size = (AndroidUtilities.displaySize.x - dp(2)) / 3 - dp(2);
            for (int i = 0; i < 3; i++) {
                BackupImageView img = new BackupImageView(getContext());
                img.setBackgroundColor(0xFF1A1A1A);
                if (i < items.size()) {
                    final PcBoard.Post p = items.get(i);
                    setMedia(img, p, 400);
                    img.setOnClickListener(v -> openPost(p));
                }
                addView(img, LayoutHelper.createLinear(0, 0));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                img.setLayoutParams(lp);
            }
        }
    }

    private static void setMedia(BackupImageView img, PcBoard.Post p, int side) {
        try {
            if (isPhoto(p)) {
                TLRPC.Photo photo = p.message.media.photo;
                TLRPC.PhotoSize sz = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, side);
                img.setImage(ImageLocation.getForPhoto(sz, photo), side + "_" + side, (android.graphics.drawable.Drawable) null, p.message);
            } else if (isVideo(p)) {
                TLRPC.Document doc = p.message.media.document;
                TLRPC.PhotoSize sz = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, side);
                if (sz != null) {
                    img.setImage(ImageLocation.getForDocument(sz, doc), side + "_" + side, (android.graphics.drawable.Drawable) null, p.message);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private class BoardRow extends FrameLayout {
        private final LinearLayout box;

        BoardRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(14), dp(14), dp(14));
            box.setBackground(PcUi.panel(true));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 8, 12, 4));
        }

        void bind() {
            final Context context = getContext();
            box.removeAllViews();
            if (!PcBoard.isEnabled(currentAccount)) {
                box.addView(PcUi.label(context, LocaleController.getString(R.string.PcBoardOffTitle), 16, true, Theme.key_windowBackgroundWhiteBlackText));
                TextView info = PcUi.label(context, LocaleController.getString(R.string.PcBoardOffText).replace("{tag}", PcBoard.MARKER), 13, false, Theme.key_windowBackgroundWhiteGrayText);
                box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 10));
                box.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardOn), true, v -> activate()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
            } else {
                LinearLayout r1 = new LinearLayout(context);
                r1.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardWrite), true, v -> write()), LayoutHelper.createLinear(0, 44, 1f, 0, 0, 4, 0));
                r1.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardOpen), false, v -> openMyBoard()), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 0, 0));
                box.addView(r1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                LinearLayout r2 = new LinearLayout(context);
                r2.addView(PcUi.button(context, LocaleController.formatString(R.string.PcBoardFind, PcBoard.budgetLeft(currentAccount)), false, v -> findFriends()), LayoutHelper.createLinear(0, 44, 2f, 0, 0, 4, 0));
                r2.addView(PcUi.button(context, LocaleController.getString(R.string.PcBoardOffBtn), false, v -> deactivate()), LayoutHelper.createLinear(0, 44, 1f, 4, 0, 0, 0));
                box.addView(r2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            }
            if (findNote != null) {
                box.addView(PcUi.label(context, findNote, 12, false, Theme.key_windowBackgroundWhiteGrayText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            }
        }
    }

    private class PostRow extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView name, date, text, stats;
        private final TextView likeBtn;
        private final BackupImageView media;
        private final TextView playBadge;
        private final TextView officialBadge;
        private long shownSince;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private PcBoard.Post post;

        PostRow(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            box.setBackground(PcUi.panel(true));
            LinearLayout head = new LinearLayout(context);
            head.setGravity(Gravity.CENTER_VERTICAL);
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(4));
            head.addView(avatar, LayoutHelper.createLinear(40, 40));
            avatar.setOnClickListener(v -> openAuthorProfile(post));
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            name = PcUi.label(context, "", 15, true, Theme.key_windowBackgroundWhiteBlackText);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setOnClickListener(v -> openAuthorProfile(post));
            date = PcUi.label(context, "", 12, false, Theme.key_windowBackgroundWhiteGrayText);
            col.addView(name);
            col.addView(date);
            head.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
            officialBadge = PcUi.badge(context, LocaleController.getString(R.string.PcOfficial).toUpperCase());
            officialBadge.setVisibility(GONE);
            head.addView(officialBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
            TextView more = PcUi.label(context, "\u22EF", 22, false, Theme.key_windowBackgroundWhiteGrayText);
            more.setPadding(dp(10), dp(2), dp(6), dp(2));
            more.setOnClickListener(v -> {
                if (post != null) showPostMenu(post);
            });
            head.addView(more, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            box.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            FrameLayout mediaBox = new FrameLayout(context);
            media = new BackupImageView(context);
            media.setBackgroundColor(0xFF1A1A1A);
            media.setOnClickListener(v -> {
                if (post != null) openPost(post);
            });
            mediaBox.addView(media, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            playBadge = PcUi.label(context, "\u25B6", 28, false, Theme.key_windowBackgroundWhiteBlackText);
            playBadge.setTextColor(0xFFF3EEE6);
            playBadge.setGravity(Gravity.CENTER);
            mediaBox.addView(playBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
            mediaBox.setTag("mediabox");
            box.addView(mediaBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 300, 0, 10, 0, 0));
            text = PcUi.label(context, "", 15, false, Theme.key_windowBackgroundWhiteBlackText);
            text.setMaxLines(12);
            text.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            stats = PcUi.label(context, "", 12, false, Theme.key_windowBackgroundWhiteGrayText);
            box.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            LinearLayout actions = new LinearLayout(context);
            likeBtn = PcUi.button(context, LocaleController.getString(R.string.PcLike), false, v -> like());
            actions.addView(likeBtn, LayoutHelper.createLinear(0, 38, 1f, 0, 0, 4, 0));
            actions.addView(PcUi.button(context, LocaleController.getString(R.string.PcComment), false, v -> {
                if (post != null) openForComment(post);
            }), LayoutHelper.createLinear(0, 38, 1f, 4, 0, 4, 0));
            actions.addView(PcUi.button(context, LocaleController.getString(R.string.PcOpenPost), false, v -> {
                if (post != null) openPost(post);
            }), LayoutHelper.createLinear(0, 38, 1f, 4, 0, 0, 0));
            box.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 4, 12, 4));
        }

        private void like() {
            if (post == null) {
                return;
            }
            final PcBoard.Post p = post;
            PcBoard.like(currentAccount, p, ok -> {
                if (ok) {
                    PcInterests.record(p, PcInterests.W_LIKE);
                    p.likes++;
                    if (post == p) {
                        bind(p);
                    }
                } else {
                    PcUi.toast(getParentActivity(), LocaleController.getString(R.string.PcLikeFailed));
                }
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            shownSince = android.os.SystemClock.elapsedRealtime();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (post != null && shownSince > 0) {
                // tempo di visione: sotto 1 secondo = scorso via (segnale negativo), sopra = interesse
                PcInterests.recordDwell(post, android.os.SystemClock.elapsedRealtime() - shownSince, isVideo(post));
            }
            shownSince = 0;
        }

        void bind(PcBoard.Post p) {
            post = p;
            officialBadge.setVisibility(PcRanking.isOfficial(p.authorId) ? VISIBLE : GONE);
            TLRPC.User u = getMessagesController().getUser(p.authorId);
            if (u != null) {
                avatarDrawable.setInfo(currentAccount, u);
                avatar.setForUserOrChat(u, avatarDrawable);
                name.setText(UserObject.getUserName(u));
            } else {
                name.setText("");
            }
            date.setText(LocaleController.formatShortDateTime(p.date));
            View mb = findViewWithTag("mediabox");
            boolean hasMedia = isPhoto(p) || isVideo(p);
            if (mb != null) {
                mb.setVisibility(hasMedia ? VISIBLE : GONE);
                if (hasMedia) {
                    setMedia(media, p, 800);
                    playBadge.setVisibility(isVideo(p) ? VISIBLE : GONE);
                }
            }
            text.setText(p.text);
            text.setVisibility(TextUtils.isEmpty(p.text) ? GONE : VISIBLE);
            stats.setText((p.likes > 0 ? "\u2764 " + p.likes + "   " : "") + (p.views > 0 ? LocaleController.formatString(R.string.PcViews, p.views) : ""));
            stats.setVisibility(p.likes > 0 || p.views > 0 ? VISIBLE : GONE);
        }
    }
}
