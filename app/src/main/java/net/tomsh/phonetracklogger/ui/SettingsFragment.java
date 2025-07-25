/*
 * Copyright (c) 2018 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of μlogger-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package net.tomsh.phonetracklogger.ui;

import static net.tomsh.phonetracklogger.ui.SettingsActivity.KEY_AUTO_START;
import static net.tomsh.phonetracklogger.ui.SettingsActivity.KEY_HOST;
import static net.tomsh.phonetracklogger.ui.SettingsActivity.KEY_LIVE_SYNC;
import static net.tomsh.phonetracklogger.ui.SettingsActivity.KEY_PROVIDER;
import static net.tomsh.phonetracklogger.ui.SettingsActivity.KEY_MIN_TIME;
import static net.tomsh.phonetracklogger.ui.SettingsActivity.KEY_MIN_ACCURACY;
import static net.tomsh.phonetracklogger.ui.SettingsActivity.KEY_MIN_DISTANCE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import android.content.Intent;
import net.tomsh.phonetracklogger.services.LoggerService;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.TwoStatePreference;

import net.tomsh.phonetracklogger.Logger;
import net.tomsh.phonetracklogger.R;
import net.tomsh.phonetracklogger.utils.PermissionHelper;
import net.tomsh.phonetracklogger.utils.WebHelper;

public class SettingsFragment extends PreferenceFragmentCompat implements PermissionHelper.PermissionRequester {

    private static final String TAG = SettingsFragment.class.getSimpleName();

    final PermissionHelper permissionHelper;

    public SettingsFragment() {
        permissionHelper = new PermissionHelper(this, this);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        setListeners();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if (preference instanceof EditTextPreference && KEY_HOST.equals(preference.getKey())) {
            final UrlPreferenceDialogFragment fragment = UrlPreferenceDialogFragment.newInstance(preference.getKey());
            fragment.setTargetFragment(this, 0);
            fragment.show(getParentFragmentManager(), "UrlPreferenceDialogFragment");
        } else if (preference instanceof ListPreference && KEY_PROVIDER.equals(preference.getKey())) {
            final ProviderPreferenceDialogFragment fragment = ProviderPreferenceDialogFragment.newInstance(preference.getKey());
            fragment.setTargetFragment(this, 0);
            fragment.show(getParentFragmentManager(), "ProviderPreferenceDialogFragment");
        } else if (preference instanceof ListPreference) {
            final ListPreferenceDialogWithMessageFragment fragment = ListPreferenceDialogWithMessageFragment.newInstance(preference.getKey());
            fragment.setTargetFragment(this, 0);
            fragment.show(getParentFragmentManager(), "ListPreferenceDialogWithMessageFragment");
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }
    private void restartLoggerIfRunning(@NonNull Context context) {
            if (LoggerService.isRunning()) {
                context.stopService(new Intent(context, LoggerService.class));
                context.startService(new Intent(context, LoggerService.class));
                if (Logger.DEBUG) { Log.d(TAG, "[LoggerService restarted due to settings change]"); }
            }
        }

    /**
     * Set various listeners
     */
    private void setListeners() {
        final Preference prefLiveSync = findPreference(KEY_LIVE_SYNC);
        final Preference prefHost = findPreference(KEY_HOST);
        final Preference prefMinTime = findPreference(SettingsActivity.KEY_MIN_TIME);
        final Preference prefMinDistance = findPreference(SettingsActivity.KEY_MIN_DISTANCE);
        final Preference prefMinAccuracy = findPreference(SettingsActivity.KEY_MIN_ACCURACY);

        // On change listener to destroy session cookies if server setup has changed
        Preference.OnPreferenceChangeListener serverSetupChanged = (preference, newValue) -> {
            // update web helper settings, remove session cookies
            WebHelper.updatePreferences(preference.getContext());
            // disable live synchronization if any server preference is removed
            if (newValue.toString().trim().isEmpty()) {
                disableLiveSync(preference.getContext());
            }
            return true;
        };

        Preference.OnPreferenceChangeListener locationSettingsChanged = (preference, newValue) -> {
            restartLoggerIfRunning(preference.getContext());
            return true;
        };

        // On change listener to validate whether live synchronization is allowed
        Preference.OnPreferenceChangeListener liveSyncChanged = (preference, newValue) -> {
            final Context context = preference.getContext();
            if (Boolean.parseBoolean(newValue.toString())) {
                if (!isValidServerSetup(context)) {
                    Toast.makeText(context, R.string.provide_user_pass_url, Toast.LENGTH_LONG).show();
                    return false;
                }
            }
            restartLoggerIfRunning(context);
            return true;
        };

        // on change listeners
        if (prefLiveSync != null) {
            prefLiveSync.setOnPreferenceChangeListener(liveSyncChanged);
        }
        if (prefHost != null) {
            prefHost.setOnPreferenceChangeListener(serverSetupChanged);
        }
        if (prefMinTime != null) {
        prefMinTime.setOnPreferenceChangeListener(locationSettingsChanged);
        }
        if (prefMinDistance != null) {
            prefMinDistance.setOnPreferenceChangeListener(locationSettingsChanged);
        }
        if (prefMinAccuracy != null) {
            prefMinAccuracy.setOnPreferenceChangeListener(locationSettingsChanged);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On change listener to check permission for background location
            Preference.OnPreferenceChangeListener permissionLevelChanged = (preference, newValue) -> {
//                final Context context = preference.getContext();
                if (Boolean.parseBoolean(newValue.toString())) {
                    if (!permissionHelper.hasBackgroundLocationPermission()) {
                        permissionHelper.requestBackgroundLocationPermission(preference.getKey());
//                        requestBackgroundLocationPermission(context, preference.getKey());
                        return false;
                    }
                }
                return true;
            };
            final Preference prefAutoStart = findPreference(KEY_AUTO_START);
            if (prefAutoStart != null) {
                prefAutoStart.setOnPreferenceChangeListener(permissionLevelChanged);
            }
        }
    }

    /**
     * Disable live sync preference, reset checkbox
     * @param context Context
     */
    private void disableLiveSync(@NonNull Context context) {
        setBooleanPreference(context, KEY_LIVE_SYNC, false);
    }

    /**
     * Enable preference, set checkbox
     * @param context Context
     */
    private void setBooleanPreference(@NonNull Context context, @NonNull String key, boolean isSet) {
        if (Logger.DEBUG) { Log.d(TAG, "[enabling " + key + "]"); }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(key, isSet);
        editor.apply();

        final Preference preference = findPreference(key);
        if (preference instanceof TwoStatePreference) {
            ((TwoStatePreference) preference).setChecked(isSet);
        }
    }

    /**
     * Check whether server setup parameters are set
     * @param context Context
     * @return boolean True if set
     */
    public static boolean isValidServerSetup(@NonNull Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String host = prefs.getString(KEY_HOST, null);
        return (host != null && !host.isEmpty());
    }

    @Override
    public void onPermissionGranted(@Nullable String requestCode) {
        if (Logger.DEBUG) { Log.d(TAG, "[onPermissionGranted: " + requestCode + "]"); }

        Context context = getContext();
        if (context != null && requestCode != null) {
            setBooleanPreference(context, requestCode, true);
        }
    }

    @Override
    public void onPermissionDenied(@Nullable String requestCode) {
        if (Logger.DEBUG) { Log.d(TAG, "[onPermissionGranted: " + requestCode + "]"); }
    }
}
