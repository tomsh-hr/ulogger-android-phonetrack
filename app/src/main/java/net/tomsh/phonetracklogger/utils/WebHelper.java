/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of μlogger-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package net.tomsh.phonetracklogger.utils;

import static android.util.Base64.NO_PADDING;
import static android.util.Base64.NO_WRAP;
import static android.util.Base64.URL_SAFE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import net.tomsh.phonetracklogger.BuildConfig;
import net.tomsh.phonetracklogger.Logger;
import net.tomsh.phonetracklogger.R;
import net.tomsh.phonetracklogger.TlsSocketFactory;
import net.tomsh.phonetracklogger.services.WebSyncService;
import net.tomsh.phonetracklogger.ui.SettingsActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.HttpsURLConnection;

/**
 * Web server communication
 *
 */

public class WebHelper {
    private static final String TAG = WebSyncService.class.getSimpleName();
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final String CRLF = "\r\n";
    private static CookieManager cookieManager = null;

    private static String host;

    private static final String CLIENT_SCRIPT = "client/index.php";
    private static final String PARAM_ACTION = "action";

    // addpos
    private static final String ACTION_ADDPOS = "addpos";
    public static final String PARAM_TIME = "time";
    public static final String PARAM_LAT = "lat";
    public static final String PARAM_LON = "lon";
    public static final String PARAM_ALT = "altitude";
    public static final String PARAM_SPEED = "speed";
    public static final String PARAM_BEARING = "bearing";
    public static final String PARAM_ACCURACY = "accuracy";
    public static final String PARAM_PROVIDER = "provider";
    public static final String PARAM_COMMENT = "comment";
    public static final String PARAM_BAT = "bat";

    private final String userAgent;
    private final Context context;

    private static boolean tlsSocketInitialized = false;
    // Socket timeout in milliseconds
    static final int SOCKET_TIMEOUT = 30 * 1000;
    private static final Random random = new Random();

    /**
     * Constructor
     * @param context Context
     */
    public WebHelper(@NonNull Context context) {
        this.context = context;
        loadPreferences(context);
        userAgent = this.context.getString(R.string.app_name_ascii) + "/" + BuildConfig.VERSION_NAME + "; " + System.getProperty("http.agent");

        if (cookieManager == null) {
            cookieManager = new CookieManager();
            CookieHandler.setDefault(cookieManager);
        }

        // On APIs < 20 enable TLSv1.1 and TLSv1.2 protocols, on APIs <= 22 disable SSLv3
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1 && !tlsSocketInitialized) {
            try {
                if (Logger.DEBUG) { Log.d(TAG, "[init TLS socket factory]"); }
                HttpsURLConnection.setDefaultSSLSocketFactory(new TlsSocketFactory());
                tlsSocketInitialized = true;
            } catch (Exception e) {
                if (Logger.DEBUG) { Log.d(TAG, "[TLS socket setup error (ignored): " + e.getMessage() + "]"); }
            }
        }
    }

    private byte[] getUrlencodedData(@NonNull Map<String, String> params) throws UnsupportedEncodingException {
        StringBuilder dataString = new StringBuilder();
        for (Map.Entry<String, String> p : params.entrySet()) {
            String key = p.getKey();
            String value = p.getValue();
            //noinspection SizeReplaceableByIsEmpty
            if (dataString.length() > 0) {
                dataString.append("&");
            }
            //noinspection CharsetObjectCanBeUsed
            dataString.append(URLEncoder.encode(key, "UTF-8"))
                      .append("=")
                      .append(URLEncoder.encode(value, "UTF-8"));
        }
        return dataString.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Send post request
     * When uri is supplied it posts multipart/form-data
     * else application/x-www-form-urlencoded type
     * @param params Request parameters
     * @return Server response
     * @throws IOException Connection error
     */
    @NonNull
    private String postForm(@NonNull Map<String, String> params) throws IOException {
        URL url = new URL(host + "/" + CLIENT_SCRIPT);
        if (Logger.DEBUG) { Log.d(TAG, "[postForm: " + url + " : " + params + "]"); }
        String response;
        byte[] data;
        final long contentLength;
        final String contentType;
        data = getUrlencodedData(params);
        contentLength = data.length;
        contentType = "application/x-www-form-urlencoded";
        HttpURLConnection connection = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            boolean retry;
            int tries = 5;
            do {
                retry = false;
                connection = (HttpURLConnection) url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(SOCKET_TIMEOUT);
                connection.setReadTimeout(SOCKET_TIMEOUT);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(contentLength);

                out = new BufferedOutputStream(connection.getOutputStream());
                byte[] bytes = data;
                out.write(bytes);
                out.flush();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                        || responseCode == 307) {
                    URL base = connection.getURL();
                    String location = connection.getHeaderField("Location");
                    if (Logger.DEBUG) { Log.d(TAG, "[postForm redirect: " + location + "]"); }
                    if (location == null || tries == 0) {
                        throw new IOException(context.getString(R.string.e_illegal_redirect, responseCode));
                    }
                    retry = true;
                    tries--;
                    url = new URL(base, location);
                    String h1 = base.getHost();
                    String h2 = url.getHost();
                    if (h1 != null && !h1.equalsIgnoreCase(h2)) {
                        throw new IOException(context.getString(R.string.e_illegal_redirect, responseCode));
                    }
                    try {
                        out.close();
                        connection.getInputStream().close();
                        connection.disconnect();
                    } catch (final IOException ignored) { }
                }
                else if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException(context.getString(R.string.e_http_code, responseCode));
                }
            } while (retry);

            in = new BufferedInputStream(connection.getInputStream());

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String inputLine;
            while ((inputLine = br.readLine()) != null) {
                sb.append(inputLine);
            }
            response = sb.toString();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
                if (connection != null) {
                    connection.disconnect();
                }
            } catch (final IOException ignored) { }
        }
        if (Logger.DEBUG) { Log.d(TAG, "[postForm response: " + response + "]"); }
        return response;
    }

    /**
     * Upload position to server
     * @param params Map of parameters (position properties)
     * @throws IOException Connection error
     */
    public void postPosition(@NonNull Map<String, String> params) throws IOException {
        if (Logger.DEBUG) { Log.d(TAG, "[postPosition]"); }
        params.put(PARAM_ACTION, ACTION_ADDPOS);
        params.put(PARAM_BAT, getBatteryLevel(context));
        String response;
        response = postForm(params);
        boolean error = true;
        try {
            JSONObject json = new JSONObject(response);
            error = json.getBoolean("error");
        } catch (JSONException e) {
            if (Logger.DEBUG) { Log.d(TAG, "[postPosition json failed: " + e + "]"); }
        }
        if (error) {
            throw new IOException(context.getString(R.string.e_server_response));
        }
    }

    private String getBatteryLevel(Context context) {
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                return String.valueOf((level * 100) / scale);
            }
        }
        return "";
    }

    /**
     * Ping server without authorization
     * @throws IOException Exception on timeout or server internal error
     */
    public boolean isReachable() throws IOException {
        if (!isNetworkAvailable()) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(host + "/" + CLIENT_SCRIPT);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setConnectTimeout(SOCKET_TIMEOUT);
            connection.setReadTimeout(SOCKET_TIMEOUT);
            int responseCode = connection.getResponseCode();
            if (Logger.DEBUG) { Log.d(TAG, "[isReachable " + host + ": " + responseCode + "]"); }
            if (responseCode / 100 == 5) {
                throw new IOException(context.getString(R.string.e_http_code, responseCode));
            }
            return true;
        } catch (IOException e) {
            if (Logger.DEBUG) { Log.d(TAG, "[isReachable exception: " + e + "]"); }
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isNetworkAvailable() {
        boolean isAvailable = false;

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
                if (capabilities != null) {
                    isAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
            } else {
                isAvailable = isNetworkAvailableApi21(connectivityManager);
            }

        }
        if (Logger.DEBUG) { Log.d(TAG, "[isNetworkAvailable " + isAvailable + "]"); }
        return isAvailable;
    }

    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    private boolean isNetworkAvailableApi21(@NonNull ConnectivityManager connectivityManager) {
        NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        return info != null && info.isAvailable() && info.isConnected();
    }

    /**
     * Get settings from shared preferences
     * @param context Context
     */
    private static void loadPreferences(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        host = prefs.getString(SettingsActivity.KEY_HOST, "NULL").replaceAll("/+$", "");
    }

    /**
     * Reload settings from shared preferences.
     * @param context Context
     */
     public static void updatePreferences(@NonNull Context context) {
        loadPreferences(context);
    }

    /**
     * Check whether given url is valid.
     * Uses relaxed pattern (@see WebPatterns#WEB_URL_RELAXED)
     * @param url URL
     * @return True if valid, false otherwise
     */
    public static boolean isValidURL(@NonNull String url) {
        return WebPatterns.WEB_URL_RELAXED.matcher(url).matches();
    }

}
