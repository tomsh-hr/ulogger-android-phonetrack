/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of μlogger-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package net.tomsh.phonetracklogger.services;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.app.AlarmManager;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.tomsh.phonetracklogger.Logger;
import net.tomsh.phonetracklogger.R;
import net.tomsh.phonetracklogger.db.DbAccess;
import net.tomsh.phonetracklogger.db.DbContract;
import net.tomsh.phonetracklogger.utils.BroadcastHelper;
import net.tomsh.phonetracklogger.utils.NotificationHelper;
import net.tomsh.phonetracklogger.utils.WebHelper;

import org.json.JSONException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/**
 * Service synchronizing local database positions with remote server
 */
public class WebSyncService extends Service {

    private static final String TAG = WebSyncService.class.getSimpleName();
    public static final String BROADCAST_SYNC_FAILED = "net.tomsh.phonetracklogger.broadcast.sync_failed";
    public static final String BROADCAST_SYNC_DONE = "net.tomsh.phonetracklogger.broadcast.sync_done";

    private HandlerThread thread;
    private ServiceHandler serviceHandler;
    private DbAccess db;
    private WebHelper web;
    private static PendingIntent pi = null;

    final private static int FIVE_MINUTES = 1000 * 60 * 5;

    private NotificationHelper notificationHelper;

    /**
     * Basic initializations
     * Start looper to process uploads
     */
    @Override
    public void onCreate() {
        super.onCreate();
        if (Logger.DEBUG) { Log.d(TAG, "[onCreate]"); }

        web = new WebHelper(this);
        notificationHelper = new NotificationHelper(this, true);

        thread = new HandlerThread("WebSyncThread", THREAD_PRIORITY_BACKGROUND);
        thread.start();
        Looper looper = thread.getLooper();
        if (looper != null) {
            serviceHandler = new ServiceHandler(looper);
        }
        // keep database open during whole service runtime
        db = DbAccess.getInstance();
        db.open(this);
    }

    /**
     * Handler to do synchronization on background thread
     */
    private final class ServiceHandler extends Handler {
        public ServiceHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            cancelPending();

            doSync();

            stopSelf(msg.arg1);
        }
    }

    /**
     * Start foreground service
     *
     * @param intent Intent
     * @param flags Flags
     * @param startId Unique id
     * @return START_STICKY on success
     */
    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        if (Logger.DEBUG) { Log.d(TAG, "[onStartCommand]"); }

        if (serviceHandler == null) {
            if (Logger.DEBUG) { Log.d(TAG, "[Give up on serviceHandler not initialized]"); }
            stopSelf();
            return START_NOT_STICKY;
        }
        final Notification notification = notificationHelper.showNotification();
        try {
            startForeground(notificationHelper.getId(), notification);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e instanceof ForegroundServiceStartNotAllowedException) {
                // In an unlikely case the app reaches data-sync time limit, continue without declaring foreground service.
                // There is still 5 second time period for non-foreground operation, which should be enough for posting data.
                // More: https://developer.android.com/about/versions/15/behavior-changes-15#datasync-timeout
                if (Logger.DEBUG) { Log.d(TAG, "[Foreground start not allowed: " + e.getMessage() + "]"); }
            } else {
                throw e;
            }
        }

        Message msg = serviceHandler.obtainMessage();
        msg.arg1 = startId;
        serviceHandler.sendMessage(msg);

        return START_STICKY;
    }

    /**
     * Synchronize all positions in database.
     * Skips already synchronized, uploads new ones
     * @param trackId Current track id
     */
    private void doSync() {
        db.resetError();
        // iterate over positions in db
        try (Cursor cursor = db.getUnsynced()) {
            while (cursor.moveToNext()) {
                int rowId = cursor.getInt(cursor.getColumnIndexOrThrow(DbContract.Positions._ID));
                Map<String, String> params = cursorToMap(cursor);
                web.postPosition(params);
                db.setSynced(getApplicationContext(), rowId);
                BroadcastHelper.sendBroadcast(this, BROADCAST_SYNC_DONE);
            }
        } catch (IOException e) {
            // handle web errors
            if (Logger.DEBUG) {
                Log.d(TAG, "[doSync: io exception: " + e + "]");
            }
            // schedule retry
            handleError(e);
        } 
    }

    /**
     * Actions performed in case of synchronization error.
     * Send broadcast to main activity, schedule retry if tracking is on.
     *
     * @param e Exception
     */
    private void handleError(@NonNull Exception e) {
        String message;
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (e instanceof UnknownHostException) {
            message = getString(R.string.e_unknown_host, reason);
        } else if (e instanceof MalformedURLException || e instanceof URISyntaxException) {
            message = getString(R.string.e_bad_url, reason);
        } else if (e instanceof ConnectException || e instanceof NoRouteToHostException || e instanceof SocketTimeoutException) {
            message = getString(R.string.e_connect, reason);
        } else if (e instanceof IllegalStateException) {
            message = getString(R.string.e_illegal_state, reason);
        } else {
            message = reason;
        }
        if (Logger.DEBUG) { Log.d(TAG, "[handleError: retry: " + message + "]"); }

        db.setError(message);

        Bundle extras = new Bundle();
        extras.putString("message", message);
        BroadcastHelper.sendBroadcast(this, BROADCAST_SYNC_FAILED, extras);

        // retry only if tracking is on
        if (LoggerService.isRunning()) {
            setPending();
        }
    }

    /**
     * Set pending alarm
     */
    private void setPending() {
        if (Logger.DEBUG) { Log.d(TAG, "[setPending alarm]"); }
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent syncIntent = new Intent(getApplicationContext(), WebSyncService.class);
        int flags = FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= FLAG_IMMUTABLE;
        }
        pi = PendingIntent.getService(this, 0, syncIntent, flags);
        if (am != null) {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + FIVE_MINUTES, pi);
        }
    }

    /**
     * Cancel pending alarm
     */
    private void cancelPending() {
        if (hasPending()) {
            if (Logger.DEBUG) { Log.d(TAG, "[cancelPending alarm]"); }
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pi);
            }
            pi = null;
        }
    }

    /**
     * Is pending alarm set
     * @return True if has pending alarm set
     */
    private boolean hasPending() {
        return pi != null;
    }

    /**
     * Convert cursor to map of request parameters
     *
     * @param cursor Cursor
     * @return Map of parameters
     */
    @NonNull
    private Map<String, String> cursorToMap(@NonNull Cursor cursor) {
        Map<String, String> params = new HashMap<>();
        params.put(WebHelper.PARAM_TIME, DbAccess.getTime(cursor));
        params.put(WebHelper.PARAM_LAT, DbAccess.getLatitude(cursor));
        params.put(WebHelper.PARAM_LON, DbAccess.getLongitude(cursor));
        if (DbAccess.hasAltitude(cursor)) {
            params.put(WebHelper.PARAM_ALT, DbAccess.getAltitude(cursor));
        }
        if (DbAccess.hasSpeed(cursor)) {
            params.put(WebHelper.PARAM_SPEED, DbAccess.getSpeed(cursor));
        }
        if (DbAccess.hasBearing(cursor)) {
            params.put(WebHelper.PARAM_BEARING, DbAccess.getBearing(cursor));
        }
        if (DbAccess.hasAccuracy(cursor)) {
            params.put(WebHelper.PARAM_ACCURACY, DbAccess.getAccuracy(cursor));
        }
        if (DbAccess.hasProvider(cursor)) {
            params.put(WebHelper.PARAM_PROVIDER, DbAccess.getProvider(cursor));
        }
        return params;
    }

    /**
     * Cleanup
     */
    @Override
    public void onDestroy() {
        if (Logger.DEBUG) { Log.d(TAG, "[onDestroy]"); }
        if (db != null) {
            db.close();
        }
        notificationHelper.cancelNotification();

        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }


    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        if (Logger.DEBUG) { Log.d(TAG, "[Give up on system timeout]"); }
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not implemented");
    }

}
