package com.example.webviewwrapper;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Lets the user change the Web App URL at runtime, without rebuilding the APK.
 * Saving here only writes SharedPreferences; MainActivity is responsible for
 * detecting the change (on resume) and clearing the WebView cache before
 * loading the new URL.
 */
public class SettingsActivity extends Activity {

    private EditText editUrl;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);

        editUrl = findViewById(R.id.edit_url);
        Button saveButton = findViewById(R.id.btn_save);
        Button cancelButton = findViewById(R.id.btn_cancel);
        Button resetButton = findViewById(R.id.btn_reset);

        editUrl.setText(prefs.getString(MainActivity.KEY_ACTIVE_URL, MainActivity.DEFAULT_URL));

        saveButton.setOnClickListener(v -> onSave());
        cancelButton.setOnClickListener(v -> finish());
        resetButton.setOnClickListener(v -> onReset());
    }

    private void onSave() {
        String candidate = editUrl.getText().toString().trim();

        if (!isValidHttpsUrl(candidate)) {
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_LONG).show();
            return;
        }

        String current = prefs.getString(MainActivity.KEY_ACTIVE_URL, MainActivity.DEFAULT_URL);
        if (candidate.equals(current)) {
            Toast.makeText(this, R.string.toast_no_change, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        prefs.edit().putString(MainActivity.KEY_ACTIVE_URL, candidate).apply();
        Toast.makeText(this, R.string.toast_url_saved, Toast.LENGTH_SHORT).show();
        finish(); // MainActivity.onResume() will detect the change and reload
    }

    private void onReset() {
        prefs.edit().putString(MainActivity.KEY_ACTIVE_URL, MainActivity.DEFAULT_URL).apply();
        Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show();
        finish(); // MainActivity.onResume() will detect the change and reload
    }

    /**
     * Only HTTPS is allowed. There is no compelling reason for this app to
     * accept cleartext HTTP, so it is rejected outright.
     */
    private boolean isValidHttpsUrl(String candidate) {
        if (candidate.isEmpty()) return false;
        if (!candidate.startsWith("https://")) return false;
        return Patterns.WEB_URL.matcher(candidate).matches();
    }
}
