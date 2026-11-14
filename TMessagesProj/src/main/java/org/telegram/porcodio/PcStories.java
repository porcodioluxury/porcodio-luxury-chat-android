package org.telegram.porcodio;

import android.app.Activity;

import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.recorder.StoryPrivacyBottomSheet;
import org.telegram.ui.Stories.recorder.StoryPrivacySelector;
import org.telegram.ui.Stories.recorder.StoryRecorder;

/**
 * Storie vere (stories.sendStory) dal "+" della Home: apre il registratore dell'app (fotocamera o galleria, foto o video,
 * modifica, scelta di chi vede). Prima controlla che le storie siano abilitate sull'account e i limiti, e imposta come
 * predefinito "Contatti" (invece di "Tutti") finche' l'utente non sceglie altro.
 */
public final class PcStories {
    private PcStories() {
    }

    public static void openRecorder(BaseFragment f) {
        final Activity a = f == null ? null : f.getParentActivity();
        if (a == null) {
            return;
        }
        final int account = f.getCurrentAccount();
        try {
            final MessagesController mc = MessagesController.getInstance(account);
            if (!mc.storiesEnabled()) {
                PcUi.toast(a, "Le storie non sono abilitate per questo account dal server (" + mc.storiesPosting + ").");
                return;
            }
            final StoriesController sc = mc.getStoriesController();
            final StoriesController.StoryLimit limit = sc.checkStoryLimit();
            if (limit != null && limit.active(account, 1)) {
                f.showDialog(new LimitReachedBottomSheet(f, a, limit.getLimitReachedType(), account, null));
                return;
            }
            if (!mc.getMainSettings().contains("story_privacy2")) {
                StoryPrivacySelector.save(account, new StoryPrivacyBottomSheet.StoryPrivacy(StoryPrivacyBottomSheet.TYPE_CONTACTS, account, null));
            }
            StoryRecorder.getInstance(a, account).open(null);
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
            PcUi.toast(a, "Impossibile aprire le storie: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " - " + t.getMessage()));
        }
    }
}
