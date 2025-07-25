/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of μlogger-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package net.tomsh.phonetracklogger.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Adds preferences from xml resource
 *
 */

public class SettingsActivity extends AppCompatActivity {

    public static final String KEY_AUTO_START = "prefAutoStart";
    public static final String KEY_HOST = "prefHost";
    public static final String KEY_LIVE_SYNC = "prefLiveSync";
    public static final String KEY_MIN_ACCURACY = "prefMinAccuracy";
    public static final String KEY_MIN_DISTANCE = "prefMinDistance";
    public static final String KEY_MIN_TIME = "prefMinTime";
    public static final String KEY_PROVIDER = "prefProvider";
    public static final String KEY_UNITS = "prefUnits";
    public static final String KEY_USE_GPS = "prefUseGps";
    public static final String KEY_USE_NET = "prefUseNet";
    public static final String KEY_LOGGER_RUNNING = "prefLoggerRunning";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView().getRootView(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }


}
