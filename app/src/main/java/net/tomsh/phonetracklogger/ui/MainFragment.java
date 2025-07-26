/*
 * Copyright (c) 2019 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of μlogger-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package net.tomsh.phonetracklogger.ui;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static net.tomsh.phonetracklogger.ui.SettingsFragment.isValidServerSetup;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import net.tomsh.phonetracklogger.Logger;
import net.tomsh.phonetracklogger.R;
import net.tomsh.phonetracklogger.TrackSummary;
import net.tomsh.phonetracklogger.db.DbAccess;
import net.tomsh.phonetracklogger.services.LoggerService;
import net.tomsh.phonetracklogger.services.WebSyncService;
import net.tomsh.phonetracklogger.utils.PermissionHelper;
import net.tomsh.phonetracklogger.utils.WebHelper;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.io.IOException;

public class MainFragment extends Fragment implements PermissionHelper.PermissionRequester {

    private final String TAG = MainFragment.class.getSimpleName();

    private final static int LED_GREEN = 1;
    private final static int LED_RED = 2;
    private final static int LED_YELLOW = 3;

    private final static double KM_MILE = 0.621371;
    private final static double KM_NMILE = 0.5399568;

    private static boolean syncError = false;
    private boolean isUploading = false;

    private TextView syncErrorLabel;
    private TextView syncLabel;
    private TextView syncLed;
    private TextView locLabel;
    private TextView locLed;
    private SwipeSwitch switchLogger;

    private TextView serverLabel;
    private Button buttonSetupServer;
    private Button buttonSync;

    private PorterDuffColorFilter redFilter;
    private PorterDuffColorFilter greenFilter;
    private PorterDuffColorFilter yellowFilter;

    private OnFragmentInteractionListener mListener;

    final PermissionHelper permissionHelper;


    public MainFragment() {
        permissionHelper = new PermissionHelper(this, this);
    }

    static MainFragment newInstance() {
        return new MainFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ScrollView layout = (ScrollView) inflater.inflate(R.layout.fragment_main, container, false);

        switchLogger = layout.findViewById(R.id.switchLogger);
        syncErrorLabel = layout.findViewById(R.id.sync_error);
        syncLabel = layout.findViewById(R.id.sync_status);
        syncLed = layout.findViewById(R.id.sync_led);
        locLabel = layout.findViewById(R.id.location_status);
        locLed = layout.findViewById(R.id.loc_led);

        serverLabel = layout.findViewById(R.id.server_label);
        buttonSetupServer = layout.findViewById(R.id.buttonSetupServer);
        buttonSetupServer.setOnClickListener(v -> showServerDialog());

        buttonSync = layout.findViewById(R.id.buttonSync);
        buttonSync.setOnClickListener(this::uploadData);

        switchLogger.setOnCheckedChangeListener(this::toggleLogging);
        return layout;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        greenFilter = new PorterDuffColorFilter(ContextCompat.getColor(context, R.color.colorGreen), PorterDuff.Mode.SRC_ATOP);
        redFilter = new PorterDuffColorFilter(ContextCompat.getColor(context, R.color.colorRed), PorterDuff.Mode.SRC_ATOP);
        yellowFilter = new PorterDuffColorFilter(ContextCompat.getColor(context, R.color.colorYellow), PorterDuff.Mode.SRC_ATOP);

        if (context instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context + " must implement OnFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    /**
     * On resume
     */
    @Override
    public void onResume() {
        super.onResume();
        if (Logger.DEBUG) { Log.d(TAG, "[onResume]"); }

        Context context = getContext();
        if (context != null) {

            if (LoggerService.isRunning()) {
                switchLogger.setChecked(true);
                setLocLed(LED_GREEN);
            } else {
                switchLogger.setChecked(false);
                setLocLed(LED_RED);
            }
            registerBroadcastReceiver();
            updateStatus();
        }
    }

    /**
     * On pause
     */
    @Override
    public void onPause() {
        if (Logger.DEBUG) { Log.d(TAG, "[onPause]"); }
        Context context = getContext();
        if (context != null) {
            context.unregisterReceiver(broadcastReceiver);
        }
        super.onPause();
    }

    /**
     * Called when the user swipes tracking switch
     * @param view View
     */
    private void toggleLogging(@NonNull View view, boolean isChecked) {
        if (isChecked && !LoggerService.isRunning()) {
            startLogger(view.getContext());
        } else if (!isChecked && LoggerService.isRunning()) {
            stopLogger(view.getContext());
        }
    }

    /**
     * Start logger service
     */
    private void startLogger(@NonNull Context context) {
        // start tracking
        boolean isValidServerSetup = isValidServerSetup(context);
        if (isValidServerSetup) {
            Intent intent = new Intent(context, LoggerService.class);
            context.startService(intent);
        } else {
            if (mListener != null) {
                mListener.showNoTrackWarning();
            }
            showServerDialog();
            switchLogger.setChecked(false);
        }
    }

    /**
     * Stop logger service
     */
    private void stopLogger(@NonNull Context context) {
        // stop tracking
        Intent intent = new Intent(context, LoggerService.class);
        context.stopService(intent);
    }

    /**
     * Called when the user clicks the Setup server button
     * @param view View
     */

    private void newServer(@SuppressWarnings("UnusedParameters") @NonNull View view) {
        if (LoggerService.isRunning()) {
            showToast(getString(R.string.logger_running_warning));
        } else {
            showServerDialog();
        }
    }

    private void showServerDialog() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog dialog = Alert.showAlert(activity,
                getString(R.string.pref_server_url),
                R.layout.newserver_dialog);
        final EditText editText = dialog.findViewById(R.id.newserver_edittext);

        if (editText == null) {
            showToast(getString(R.string.provide_valid_url));
            return;
        }
        editText.setText(AutoNamePreference.getHost(activity));
        editText.setOnClickListener(view -> editText.selectAll());

        final Button submit = dialog.findViewById(R.id.newserver_button_submit);
        if (submit != null) {
            submit.setOnClickListener(v -> {
                String serverUrl = editText.getText().toString().trim();
                if (serverUrl.isEmpty()) {
                    showToast(getString(R.string.provide_valid_url));
                    return;
                }
                if (!WebHelper.isValidURL(serverUrl)) {
                    showToast(getString(R.string.e_bad_url, serverUrl));
                    return;
                }

                // Test connection in background
                new Thread(() -> {
                    boolean reachable = false;
                    try {
                        WebHelper.updatePreferences(activity);
                        PreferenceManager.getDefaultSharedPreferences(activity)
                            .edit()
                            .putBoolean(SettingsActivity.KEY_LIVE_SYNC, true)
                            .apply();

                        WebHelper helper = new WebHelper(activity);
                        reachable = helper.isReachable();
                    } catch (IOException e) {
                        if (Logger.DEBUG) Log.d(TAG, "[Server check failed: " + e + "]");
                    }

                    boolean finalReachable = reachable;
                    activity.runOnUiThread(() -> {
                        if (finalReachable) {
                            AutoNamePreference.setHost(activity, serverUrl);
                            updateStatus();
                            dialog.dismiss();
                            startLogger(requireContext());
                            switchLogger.setChecked(true);
                            // showToast(getString(R.string.server_ok));
                        } else {
                            showToast(getString(R.string.e_connect, serverUrl));
                        }
                    });
                }).start();
            });

        }

        final Button cancel = dialog.findViewById(R.id.newserver_button_cancel);
        if (cancel != null) {
            cancel.setOnClickListener(v -> dialog.dismiss());
        }
    }


    /**
     * Show toast
     * @param text Text
     */
    private void showToast(@NonNull String text) {
        Context context = getContext();
        if (context != null) {
            Toast toast = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG);
            toast.show();
        }
    }

    /**
     * Called when the user clicks the Upload button
     * @param view View
     */
    private void uploadData(@NonNull View view) {
        Context context = view.getContext();
        if (!SettingsFragment.isValidServerSetup(context)) {
            showToast(getString(R.string.provide_user_pass_url));
        } else if (DbAccess.needsSync(context)) {
            buttonSync.setEnabled(false);
            buttonSync.setAlpha(0.5f);  // Optional: make it look disabled
            buttonSync.setText(R.string.syncing);  // Change text to "Syncing"
            startButtonBlinking(buttonSync);

            Intent syncIntent = new Intent(context, WebSyncService.class);
            context.startService(syncIntent);
            showToast(getString(R.string.uploading_started));
            isUploading = true;
        } else {
            showToast(getString(R.string.nothing_to_synchronize));
        }
    }

    private void startButtonBlinking(@NonNull Button button) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(button, "alpha", 1f, 0.3f);
        animator.setDuration(500);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.start();

        button.setTag(animator);  // Save animator to stop it later
    }

    private void stopButtonBlinking(@NonNull Button button) {
        Object tag = button.getTag();
        if (tag instanceof ObjectAnimator) {
            ((ObjectAnimator) tag).cancel();
        }
        button.setAlpha(1f);
        button.setEnabled(true);
        button.setText(R.string.button_sync);  // Back to normal label
    }

    /**
     * Update location tracking status label
     * @param lastUpdateRealtime Real time of last location update
     */
    private void updateLocationLabel(long lastUpdateRealtime) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        // get last location update time
        String timeString;
        long timestamp = 0;
        long elapsed = 0;
        long dbTimestamp;
        if (lastUpdateRealtime > 0) {
            elapsed = (SystemClock.elapsedRealtime() - lastUpdateRealtime);
            timestamp = System.currentTimeMillis() - elapsed;
        } else if ((dbTimestamp = DbAccess.getLastTimestamp(context)) > 0) {
            timestamp = dbTimestamp * 1000;
            elapsed = System.currentTimeMillis() - timestamp;
        }

        if (timestamp > 0) {
            final Date updateDate = new Date(timestamp);
            final Calendar calendar = Calendar.getInstance();
            calendar.setTime(updateDate);
            final Calendar today = Calendar.getInstance();
            SimpleDateFormat sdf;

            if (calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                    && calendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            } else {
                sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            }
            sdf.setTimeZone(TimeZone.getDefault());
            timeString = String.format(getString(R.string.label_last_update), sdf.format(updateDate));
        } else {
            timeString = "-";
        }
        locLabel.setText(timeString);
        // Change led if more than 2 update periods elapsed since last location update
        MainActivity activity = (MainActivity) requireActivity();
        if (LoggerService.isRunning() && (timestamp == 0 || elapsed > activity.preferenceMinTimeMillis * 2)) {
            setLocLed(LED_YELLOW);
        }
    }

    /**
     * Update synchronization status label and led
     * @param unsynced Count of not synchronized positions
     */
    private void updateSyncStatus(int unsynced) {
        String text;
        Context context = getContext();
        boolean validServer = context != null && SettingsFragment.isValidServerSetup(context);
        
        if (!validServer) {
            text = getString(R.string.label_no_server_configured);
            setSyncLed(LED_RED);
            buttonSync.setVisibility(View.GONE);
        } else if (unsynced > 0) {
            text = getResources().getQuantityString(R.plurals.label_positions_behind, unsynced, unsynced);
            if (syncError) {
                setSyncLed(LED_RED);
            } else {
                setSyncLed(LED_YELLOW);
            }
            buttonSync.setVisibility(View.VISIBLE);
        } else {
            text = getString(R.string.label_synchronized);
            setSyncLed(LED_GREEN);
            buttonSync.setVisibility(View.GONE);
        }

        syncLabel.setText(text);
    }

    /**
     * Update location tracking and synchronization status
     */
    private void updateStatus() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        updateLocationLabel(LoggerService.lastUpdateRealtime());
        // get sync status
        int count = DbAccess.countUnsynced(context);
        String error = DbAccess.getError(context);
        if (error != null) {
            if (Logger.DEBUG) { Log.d(TAG, "[sync error: " + error + "]"); }
            setSyncError(error);
        } else {
            resetSyncError();
        }
        updateSyncStatus(count);
        updateSummaryBox();
        updateServerBox();
    }

    private void updateServerBox() {
        Context context = getContext();
        if (context == null) return;

        String host = AutoNamePreference.getHost(context);
        if (host == null || host.isEmpty()) {
            serverLabel.setText(getString(R.string.dash));
            buttonSetupServer.setVisibility(View.VISIBLE);
        } else {
            serverLabel.setText(replacePhonetrackPath(host));
            buttonSetupServer.setVisibility(View.GONE);
        }
    }

    private String replacePhonetrackPath(@NonNull String url) {
        return url.replace("index.php/apps/phonetrack/log/ulogger", "…");
    }

    private void updateSummaryBox() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        final TrackSummary summary = DbAccess.getSessionSummary(context);
        View view = getView();
        if (summary == null || view == null) {
            return;
        }

        TextView distanceView = view.findViewById(R.id.summary_distance);
        TextView durationView = view.findViewById(R.id.summary_duration);
        TextView positionsView = view.findViewById(R.id.summary_positions);

        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        double distance = summary.getDistance() / 1000.0;
        String unit = getString(R.string.unit_kilometer);
        if (activity.preferenceUnits.equals(getString(R.string.pref_units_imperial))) {
            distance *= 0.621371;
            unit = getString(R.string.unit_mile);
        } else if (activity.preferenceUnits.equals(getString(R.string.pref_units_nautical))) {
            distance *= 0.5399568;
            unit = getString(R.string.unit_nmile);
        }

        NumberFormat nf = NumberFormat.getInstance();
        nf.setMaximumFractionDigits(2);

        if (distanceView != null) {
            distanceView.setText(getString(R.string.summary_distance, nf.format(distance), unit));
        }

        if (durationView != null) {
            long h = summary.getDuration() / 3600;
            long m = (summary.getDuration() % 3600) / 60;
            durationView.setText(getString(R.string.summary_duration, h, m));
        }

        if (positionsView != null) {
            int positions = (int) summary.getPositionsCount();
            positionsView.setText(getResources().getQuantityString(R.plurals.summary_positions, positions, positions));
        }
    }

    /**
     * Set status led color
     * @param led Led text view
     * @param color Color (red, yellow or green)
     */
    private void setLedColor(@NonNull TextView led, int color) {
        Drawable l = TextViewCompat.getCompoundDrawablesRelative(led)[0];
        switch (color) {
            case LED_RED -> l.setColorFilter(redFilter);
            case LED_GREEN -> l.setColorFilter(greenFilter);
            case LED_YELLOW -> l.setColorFilter(yellowFilter);
        }
        l.invalidateSelf();
    }

    /**
     * Set synchronization status led color
     * Red - synchronization error
     * Yellow - synchronization delay
     * Green - synchronized
     * @param color Color
     */
    private void setSyncLed(int color) {
        if (Logger.DEBUG) { Log.d(TAG, "[setSyncLed " + color + "]"); }
        setLedColor(syncLed, color);
    }

    /**
     * Set location tracking status led color
     * Red - tracking off
     * Yellow - tracking on, long time since last update
     * Green - tracking on, recently updated
     * @param color Color
     */
    private void setLocLed(int color) {
        if (Logger.DEBUG) { Log.d(TAG, "[setLocLed " + color + "]"); }
        setLedColor(locLed, color);
    }


    /**
     * Set sync error flag and label
     * @param message Error message
     */
    private void setSyncError(@Nullable String message) {
        syncError = true;
        syncErrorLabel.setText(message);
        syncErrorLabel.setVisibility(VISIBLE);
    }

    /**
     * Reset sync error flag and label
     */
    private void resetSyncError() {
        if (syncError) {
            syncErrorLabel.setText(null);
            syncError = false;
            syncErrorLabel.setVisibility(GONE);
        }
    }

    @Override
    public void onPermissionGranted(@Nullable String requestCode) {
        if (Logger.DEBUG) { Log.d(TAG, "[LocationPermission: granted]"); }
        Context context = getContext();
        if (context != null) {
            startLogger(context);
        }
    }

    @Override
    public void onPermissionDenied(@Nullable String requestCode) {
        if (Logger.DEBUG) { Log.d(TAG, "[LocationPermission: denied]"); }
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     */
    public interface OnFragmentInteractionListener {
        void showNoTrackWarning();
    }


    /**
     * Register broadcast receiver for synchronization
     * and tracking status updates
     */
    private void registerBroadcastReceiver() {
        Context context = getContext();
        if (context != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(LoggerService.BROADCAST_LOCATION_STARTED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_STOPPED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_UPDATED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_DISABLED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_GPS_DISABLED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_NETWORK_DISABLED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_GPS_ENABLED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_NETWORK_ENABLED);
            filter.addAction(LoggerService.BROADCAST_LOCATION_PERMISSION_DENIED);
            filter.addAction(WebSyncService.BROADCAST_SYNC_DONE);
            filter.addAction(WebSyncService.BROADCAST_SYNC_FAILED);
            ContextCompat.registerReceiver(context, broadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    /**
     * Broadcast receiver
     */
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull Context context, @Nullable Intent intent) {
            if (Logger.DEBUG) { Log.d(TAG, "[broadcast received " + intent + "]"); }
            if (intent == null || intent.getAction() == null) {
                return;
            }
            MainActivity activity = (MainActivity) getActivity();
            switch (intent.getAction()) {
                case LoggerService.BROADCAST_LOCATION_UPDATED -> {
                    Log.d(TAG, "onReceive LOCATION_UPDATED");
                    updateLocationLabel(LoggerService.lastUpdateRealtime());
                    setLocLed(LED_GREEN);
                    if (activity != null && !activity.preferenceLiveSync) {
                        updateSyncStatus(DbAccess.countUnsynced(context));
                    }
                }
                case WebSyncService.BROADCAST_SYNC_DONE -> {
                    final int unsyncedCount = DbAccess.countUnsynced(context);
                    updateSyncStatus(unsyncedCount);
                    setSyncLed(LED_GREEN);
                    // reset error flag and label
                    resetSyncError();
                    // showConfirm message if manual uploading
                    if (isUploading && unsyncedCount == 0) {
                        showToast(getString(R.string.uploading_done));
                        stopButtonBlinking(buttonSync);
                        isUploading = false;
                    }
                }
                case (WebSyncService.BROADCAST_SYNC_FAILED) -> {
                    updateSyncStatus(DbAccess.countUnsynced(context));
                    setSyncLed(LED_RED);
                    // set error flag and label
                    String message = intent.getStringExtra("message");
                    setSyncError(message);
                    // showConfirm message if manual uploading
                    if (isUploading) {
                        showToast(getString(R.string.uploading_failed) + "\n" + message);
                        isUploading = false;
                    }
                }
                case LoggerService.BROADCAST_LOCATION_STARTED -> {
                    switchLogger.setChecked(true);
                    showToast(getString(R.string.tracking_started));
                    setLocLed(LED_YELLOW);
                }
                case LoggerService.BROADCAST_LOCATION_STOPPED -> {
                    switchLogger.setChecked(false);
                    showToast(getString(R.string.tracking_stopped));
                    setLocLed(LED_RED);
                }
                case LoggerService.BROADCAST_LOCATION_GPS_DISABLED ->
                        showToast(getString(R.string.gps_disabled_warning));
                case LoggerService.BROADCAST_LOCATION_NETWORK_DISABLED ->
                        showToast(getString(R.string.net_disabled_warning));
                case LoggerService.BROADCAST_LOCATION_DISABLED -> {
                    showToast(getString(R.string.location_disabled));
                    setLocLed(LED_RED);
                }
                case LoggerService.BROADCAST_LOCATION_NETWORK_ENABLED ->
                        showToast(getString(R.string.using_network));
                case LoggerService.BROADCAST_LOCATION_GPS_ENABLED ->
                        showToast(getString(R.string.using_gps));
                case LoggerService.BROADCAST_LOCATION_PERMISSION_DENIED -> {
                    showToast(getString(R.string.location_permission_denied));
                    setLocLed(LED_RED);
                    permissionHelper.requestFineLocationPermission();
                }
            }
        }
    };

}
