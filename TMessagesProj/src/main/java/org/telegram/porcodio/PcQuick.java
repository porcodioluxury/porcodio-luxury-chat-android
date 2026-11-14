package org.telegram.porcodio;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Risposte rapide: testi salvati solo sul dispositivo, da inserire in qualsiasi chat dal menu (⋮) della conversazione. */
public class PcQuick {
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("pc_quick", Context.MODE_PRIVATE);
    }

    public static ArrayList<String> list() {
        ArrayList<String> res = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                res.add(a.getString(i));
            }
        } catch (Exception ignore) {
        }
        return res;
    }

    private static void save(ArrayList<String> items) {
        JSONArray a = new JSONArray();
        for (String s : items) {
            a.put(s);
        }
        prefs().edit().putString("items", a.toString()).apply();
    }

    public static void add(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        ArrayList<String> l = list();
        l.add(text.trim());
        save(l);
    }

    public static void remove(int index) {
        ArrayList<String> l = list();
        if (index >= 0 && index < l.size()) {
            l.remove(index);
            save(l);
        }
    }

    public static void askNew(BaseFragment fragment, Runnable after) {
        final Context ctx = fragment.getParentActivity();
        if (ctx == null) {
            return;
        }
        final EditText edit = new EditText(ctx);
        edit.setHint(LocaleController.getString(R.string.PcQuickHint));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(3);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        FrameLayout holder = new FrameLayout(ctx);
        holder.setPadding(dp(20), dp(4), dp(20), 0);
        holder.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(LocaleController.getString(R.string.PcQuickAdd));
        b.setView(holder);
        b.setPositiveButton(LocaleController.getString(R.string.PcNoteSave), (d, w) -> {
            add(edit.getText().toString());
            if (after != null) after.run();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
    }

    /** Elenco da cui scegliere (dal menu della chat). onPick riceve il testo da inserire nel campo messaggio. */
    public static void showPicker(BaseFragment fragment, Utilities.Callback<String> onPick) {
        final Context ctx = fragment.getParentActivity();
        if (ctx == null) {
            return;
        }
        final ArrayList<String> l = list();
        if (l.isEmpty()) {
            askNew(fragment, () -> showPicker(fragment, onPick));
            return;
        }
        final CharSequence[] items = new CharSequence[l.size() + 1];
        for (int i = 0; i < l.size(); i++) {
            String s = l.get(i).replace('\n', ' ');
            items[i] = s.length() > 70 ? s.substring(0, 70) + "..." : s;
        }
        items[l.size()] = LocaleController.getString(R.string.PcQuickManage);
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(LocaleController.getString(R.string.PcQuickInsert));
        b.setItems(items, (d, which) -> {
            if (which < l.size()) {
                onPick.run(l.get(which));
            } else {
                fragment.presentFragment(new PcQuickActivity());
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
    }
}
