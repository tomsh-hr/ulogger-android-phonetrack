/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of μlogger-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package net.tomsh.phonetracklogger.ui;

import static androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import static net.tomsh.phonetracklogger.tasks.GpxExportTask.GPX_EXTENSION;
import static java.util.concurrent.Executors.newCachedThreadPool;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.text.HtmlCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import net.tomsh.phonetracklogger.BuildConfig;
import net.tomsh.phonetracklogger.CreateGpxDocument;
import net.tomsh.phonetracklogger.Logger;
import net.tomsh.phonetracklogger.R;
import net.tomsh.phonetracklogger.db.DbAccess;
import net.tomsh.phonetracklogger.services.LoggerService;
import net.tomsh.phonetracklogger.tasks.GpxExportTask;
import net.tomsh.phonetracklogger.ui.AutoNamePreference;

import java.util.concurrent.ExecutorService;

/**
 * Main activity of ulogger
 *
 */

public class MainActivity extends AppCompatActivity
        implements FragmentManager.OnBackStackChangedListener, MainFragment.OnFragmentInteractionListener,
        GpxExportTask.GpxExportTaskCallback {

    private final String TAG = MainActivity.class.getSimpleName();

    public final static String UPDATED_PREFS = "extra_updated_prefs";

    public String preferenceHost;
    public String preferenceUnits;
    public long preferenceMinTimeMillis;
    public boolean preferenceLiveSync;
    private GpxExportTask gpxExportTask;
    private final ExecutorService executor = newCachedThreadPool();

    private DbAccess db;

    /**
     * Initialization
     * @param savedInstanceState Saved state
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int theme = prefs.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(theme);
        updatePreferences();
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        final int toolbarHeight = toolbar.getLayoutParams().height;
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView().getRootView(), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            toolbar.setPadding(0, systemBars.top, 0, 0);
            toolbar.getLayoutParams().height = toolbarHeight + systemBars.top;
            return WindowInsetsCompat.CONSUMED;
        });
        if (savedInstanceState == null) {
            MainFragment fragment = MainFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_placeholder, fragment).commit();
        }
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        //Handle when activity is recreated like on orientation Change
        setHomeUpButton();
    }

    /**
     * On resume
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (Logger.DEBUG) { Log.d(TAG, "[onResume]"); }
        db = DbAccess.getOpenInstance(this);
    }

    /**
     * On pause
     */
    @Override
    protected void onPause() {
        if (Logger.DEBUG) { Log.d(TAG, "[onPause]"); }
        if (db != null) {
            db.close();
        }
        super.onPause();
    }

    /**
     * On destroy
     */
    @Override
    protected void onDestroy() {
        if (Logger.DEBUG) { Log.d(TAG, "[onDestroy]"); }
        super.onDestroy();
    }


    /**
     * Create main menu
     * @param menu Menu
     * @return Always true
     */
    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    /**
     * Main menu options
     * @param item Selected option
     * @return True if handled
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
            return true;
        } else if (id == R.id.menu_self_check) {
            loadSelfCheckFragment();
            return true;
        } else if (id == R.id.menu_about) {
            showAbout();
            return true;
        } else if (id == R.id.menu_export) {
            startExport();
            return true;
        } else if (id == R.id.menu_theme) {
            int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

            if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                prefs.edit().putInt("theme", AppCompatDelegate.MODE_NIGHT_NO).apply();
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                prefs.edit().putInt("theme", AppCompatDelegate.MODE_NIGHT_YES).apply();
            }

            // Handle fragment back stack after theme change
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_placeholder);
            if (currentFragment instanceof MainFragment) {
                recreate();  // Only recreate if we're on MainFragment
            } else {
                getSupportFragmentManager().popBackStack();  // Go back to MainFragment
            }

            return true;
        } else if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Load SelfCheckFragment or run it's self check action if already loaded
     */
    private void loadSelfCheckFragment() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment = fragmentManager.findFragmentById(R.id.fragment_placeholder);
        if (currentFragment instanceof SelfCheckFragment) {
            if (Logger.DEBUG) { Log.d(TAG, "[SelfCheckFragment already loaded]"); }
            ((SelfCheckFragment) currentFragment).setRefreshing(true);
            ((SelfCheckFragment) currentFragment).selfCheck();
            return;
        }
        SelfCheckFragment fragment = new SelfCheckFragment();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_placeholder, fragment);
        transaction.addToBackStack(null);
        transaction.commit();
    }

    /**
     * Reread user preferences
     */
    private void updatePreferences() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        preferenceUnits = prefs.getString(SettingsActivity.KEY_UNITS, getString(R.string.pref_units_default));
        preferenceMinTimeMillis = Long.parseLong(prefs.getString(SettingsActivity.KEY_MIN_TIME, getString(R.string.pref_mintime_default))) * 1000;
        preferenceLiveSync = prefs.getBoolean(SettingsActivity.KEY_LIVE_SYNC, false);
        preferenceHost = prefs.getString(SettingsActivity.KEY_HOST, "").replaceAll("/+$", "");
    }

    /**
     * Display warning if track name is not set
     */
    public void showNoTrackWarning() {
        showToast(getString(R.string.no_track_warning));
    }

    /**
     * Start export service
     */
    private void startExport() {
        if (db.countPositions() > 0) {
            String serverUrl = AutoNamePreference.getHost(this);
            String trackName = getPhoneTrackSegment(serverUrl);
            if (trackName == null || trackName.trim().isEmpty()) {
                trackName = "session" + GPX_EXTENSION;
            }
            try {
                getExportUri.launch(trackName);
            } catch (ActivityNotFoundException e) {
                showToast(getString(R.string.cannot_open_picker), Toast.LENGTH_LONG);
            }
        } else {
            showToast(getString(R.string.nothing_to_export));
        }
    }

    public static String getPhoneTrackSegment(@NonNull String url) {
        if (url.isEmpty()) {
            return "export" + GpxExportTask.GPX_EXTENSION;
        }
        String segment = "export";
        String[] parts = url.split("/log/");
        if (parts.length > 1) {
            String[] afterLog = parts[1].split("/");
            if (afterLog.length > 0 && !afterLog[0].isEmpty()) {
                segment = afterLog[2];
            }
        }
        return segment + GpxExportTask.GPX_EXTENSION;
    }

    /**
     * Display toast message
     * @param text Message
     */
    private void showToast(@NonNull CharSequence text) {
        showToast(text, Toast.LENGTH_SHORT);
    }

    /**
     * Display toast message
     * @param text Message
     * @param duration Duration
     */
    private void showToast(@NonNull CharSequence text, int duration) {
        Context context = getApplicationContext();
        Toast toast = Toast.makeText(context, text, duration);
        toast.show();
    }

    /**
     * Display About dialog
     */
    private void showAbout() {
        final AlertDialog dialog = Alert.showAlert(MainActivity.this,
                getString(R.string.app_name),
                R.layout.about,
                R.drawable.ic_ulogger_logo_24dp);
        final TextView versionLabel = dialog.findViewById(R.id.about_version);
        if (versionLabel != null) {
            versionLabel.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));
        }
        final TextView descriptionLabel = dialog.findViewById(R.id.about_description);
        final TextView description2Label = dialog.findViewById(R.id.about_description2);
        if (descriptionLabel != null && description2Label != null) {
            descriptionLabel.setText(HtmlCompat.fromHtml(getString(R.string.about_description), HtmlCompat.FROM_HTML_MODE_LEGACY));
            description2Label.setText(HtmlCompat.fromHtml(getString(R.string.about_description2), HtmlCompat.FROM_HTML_MODE_LEGACY));
            descriptionLabel.setMovementMethod(LinkMovementMethod.getInstance());
            description2Label.setMovementMethod(LinkMovementMethod.getInstance());
        }
        final Button okButton = dialog.findViewById(R.id.about_button_ok);
        if (okButton != null) {
            okButton.setOnClickListener(v -> dialog.dismiss());
        }
    }

    private void setHomeUpButton() {
        boolean enabled = getSupportFragmentManager().getBackStackEntryCount() > 0;
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(enabled);
        }
    }

    /**
     * Open file picker to get exported file URI, then run export task
     */
    final ActivityResultLauncher<String> getExportUri = registerForActivityResult(new CreateGpxDocument(), uri -> {
        if (uri != null) {
            runGpxExportTask(uri);
        }
    });

    /**
     * Open settings activity, update preferences on return
     */
    final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(new StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK) {
            // Preferences updated
            updatePreferences();
            if (LoggerService.isRunning()) {
                // restart logging
                Intent intent = new Intent(MainActivity.this, LoggerService.class);
                intent.putExtra(UPDATED_PREFS, true);
                startService(intent);
            }
        }
    });

    /**
     * Called whenever the contents of the back stack change.
     */
    @Override
    public void onBackStackChanged() {
        setHomeUpButton();
    }

    /**
     * Start GPX export task
     */
    private void runGpxExportTask(@NonNull Uri uri) {
        if (gpxExportTask == null || !gpxExportTask.isRunning()) {
            gpxExportTask = new GpxExportTask(uri, this);
            executor.execute(gpxExportTask);
            showToast(getString(R.string.export_started));
        }
    }

    @Override
    public void onGpxExportTaskCompleted() {
        showToast(getString(R.string.export_done));
    }

    @Override
    public void onGpxExportTaskFailure(@NonNull String error) {
        String message = getString(R.string.export_failed);
        if (!error.isEmpty()) {
            message += "\n" + error;
        }
        showToast(message);
    }

    @NonNull
    @Override
    public Activity getActivity() {
        return this;
    }

}
