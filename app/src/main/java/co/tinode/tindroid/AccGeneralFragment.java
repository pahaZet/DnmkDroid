package co.tinode.tindroid;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import co.tinode.tindroid.account.Utils;

public class AccGeneralFragment extends Fragment {
    private SharedPreferences preferences;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final AppCompatActivity activity = (AppCompatActivity) requireActivity();

        View view = inflater.inflate(R.layout.fragment_acc_general, container, false);
        final ActionBar bar = activity.getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
        }

        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.general);
        toolbar.setNavigationOnClickListener(v -> activity.getSupportFragmentManager().popBackStack());

        // Load saved preference
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        String savedTheme = preferences.getString(Utils.PREFS_UI_MODE, Utils.PREFS_KEY_AUTO_THEME);
        RadioGroup radioGroup = view.findViewById(R.id.radio_group_theme);
        setRadioButtonFromTheme(radioGroup, savedTheme);

        // Set listeners
        radioGroup.setOnCheckedChangeListener(this::onCheckedChanged);

        view.findViewById(R.id.buttonWallpapers)
                .setOnClickListener(v ->
                        ((ChatsActivity) requireActivity())
                                .showFragment(ChatsActivity.FRAGMENT_WALLPAPERS, null));

        SwitchCompat sendOnEnter = view.findViewById(R.id.send_on_enter);
        boolean sendOnEnterEnabled = preferences.getBoolean(Utils.PREFS_SEND_ON_ENTER, false);
        sendOnEnter.setChecked(sendOnEnterEnabled);
        sendOnEnter.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(Utils.PREFS_SEND_ON_ENTER, isChecked).apply());

        EditText ufadeInput = view.findViewById(R.id.ufade_input);
        ufadeInput.setText(String.valueOf(readUfadePreference()));
        ufadeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                persistUfadeValue((EditText) v);
            }
        });
        ufadeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                persistUfadeValue((EditText) v);
            }
            return false;
        });
        return view;
    }

    private int readUfadePreference() {
        try {
            return Math.max(0, preferences.getInt(Const.PREF_MESSAGE_UFADE, Const.DEFAULT_MESSAGE_UFADE));
        } catch (ClassCastException ignored) {
            String value = preferences.getString(Const.PREF_MESSAGE_UFADE, null);
            if (!TextUtils.isEmpty(value)) {
                try {
                    return Math.max(0, Integer.parseInt(value));
                } catch (NumberFormatException ignored2) {
                    // Ignore malformed value and use default.
                }
            }
        }
        return Const.DEFAULT_MESSAGE_UFADE;
    }

    private void persistUfadeValue(@NonNull EditText input) {
        String raw = input.getText() != null ? input.getText().toString().trim() : "";
        int value = Const.DEFAULT_MESSAGE_UFADE;
        if (!TextUtils.isEmpty(raw)) {
            try {
                value = Math.max(0, Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // Revert invalid content to the last valid/default value below.
            }
        }
        preferences.edit().putInt(Const.PREF_MESSAGE_UFADE, value).apply();
        String normalized = Integer.toString(value);
        if (!normalized.equals(raw)) {
            input.setText(normalized);
            input.setSelection(normalized.length());
        }
    }

    private void handleThemeSelection(String selectedKey) {
        preferences.edit().putString(Utils.PREFS_UI_MODE, selectedKey).apply();
        TindroidApp.onThemeChanged(selectedKey);
    }

    private void setRadioButtonFromTheme(RadioGroup radioGroup, String theme) {
        switch (theme) {
            case Utils.PREFS_KEY_LIGHT_THEME:
                radioGroup.check(R.id.radio_light);
                break;
            case Utils.PREFS_KEY_DARK_THEME:
                radioGroup.check(R.id.radio_dark);
                break;
            default:
                radioGroup.check(R.id.radio_default);
                break;
        }
    }

    private void onCheckedChanged(RadioGroup group, int checkedId) {
        String selectedTheme;
        if (checkedId == R.id.radio_light) {
            selectedTheme = Utils.PREFS_KEY_LIGHT_THEME;
        } else if (checkedId == R.id.radio_dark) {
            selectedTheme = Utils.PREFS_KEY_DARK_THEME;
        } else {
            selectedTheme = Utils.PREFS_KEY_AUTO_THEME;
        }
        handleThemeSelection(selectedTheme);
    }
}
