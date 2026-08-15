package com.valen.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public class ValenBridge {

    private final Activity activity;
    private String cachedServerUrl = "";

    public ValenBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void showToast(String message) {
        activity.runOnUiThread(() ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        );
    }

    @JavascriptInterface
    public void startBackgroundService() {
        Intent intent = new Intent(activity, ValenBackgroundService.class);
        intent.putExtra("server_url", cachedServerUrl);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }
    }

    @JavascriptInterface
    public void stopBackgroundService() {
        Intent intent = new Intent(activity, ValenBackgroundService.class);
        activity.stopService(intent);
    }

    @JavascriptInterface
    public void vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @JavascriptInterface
    public String getServerUrl() {
        return cachedServerUrl;
    }

    @JavascriptInterface
    public void setServerUrl(String url) {
        cachedServerUrl = url;
    }

    @JavascriptInterface
    public boolean isBackgroundServiceRunning() {
        return ValenBackgroundService.isRunning;
    }

    @JavascriptInterface
    public int getAndroidVersion() {
        return Build.VERSION.SDK_INT;
    }

    @JavascriptInterface
    public int getMinSdkVersion() {
        return 29;
    }

    @JavascriptInterface
    public String getDeviceName() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }
}
