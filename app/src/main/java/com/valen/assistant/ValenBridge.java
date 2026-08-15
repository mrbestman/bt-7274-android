package com.valen.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;

public class ValenBridge {

    private static final String TAG = "ValenBridge";
    private final Activity activity;
    private final WebView webView;
    private String cachedServerUrl = "";
    private AudioRecord audioRecord = null;
    private volatile boolean isRecording = false;
    private Thread recordThread = null;
    private ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private final Object micLock = new Object();

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public ValenBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibrate failed: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public String getServerUrl() { return cachedServerUrl; }

    @JavascriptInterface
    public void setServerUrl(String url) {
        cachedServerUrl = url;
        SharedPreferences prefs = activity.getSharedPreferences("valen", Context.MODE_PRIVATE);
        prefs.edit().putString("server_url", url).apply();
    }

    @JavascriptInterface
    public boolean isBackgroundServiceRunning() { return ValenBackgroundService.isRunning; }

    @JavascriptInterface
    public int getAndroidVersion() { return Build.VERSION.SDK_INT; }

    @JavascriptInterface
    public int getMinSdkVersion() { return 29; }

    @JavascriptInterface
    public String getDeviceName() { return Build.MANUFACTURER + " " + Build.MODEL; }

    @JavascriptInterface
    public void loadServerUrl(String url) {
        activity.runOnUiThread(() -> {
            if (webView != null) webView.loadUrl(url);
        });
    }

    @JavascriptInterface
    public boolean isNativeMicAvailable() { return true; }

    @JavascriptInterface
    public void startNativeRecording() {
        synchronized (micLock) {
            if (isRecording) return;
            try {
                int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    bufferSize = SAMPLE_RATE * 2;
                }
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                audioBuffer.reset();
                isRecording = true;
                audioRecord.startRecording();
                Log.d(TAG, "Native recording started");

                recordThread = new Thread(() -> {
                    short[] buffer = new short[1024];
                    while (isRecording) {
                        try {
                            int read = audioRecord.read(buffer, 0, buffer.length);
                            if (read > 0) {
                                byte[] byteBuffer = new byte[read * 2];
                                for (int i = 0; i < read; i++) {
                                    byteBuffer[i * 2] = (byte) (buffer[i] & 0xFF);
                                    byteBuffer[i * 2 + 1] = (byte) ((buffer[i] >> 8) & 0xFF);
                                }
                                synchronized (micLock) {
                                    audioBuffer.write(byteBuffer);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Record thread error: " + e.getMessage());
                            break;
                        }
                    }
                }, "ValenAudioRecord");
                recordThread.start();
            } catch (Exception e) {
                isRecording = false;
                Log.e(TAG, "startNativeRecording failed: " + e.getMessage());
                notifyJsError("Mic init failed: " + e.getMessage());
            }
        }
    }

    @JavascriptInterface
    public void stopNativeRecording() {
        synchronized (micLock) {
            isRecording = false;
        }
        if (recordThread != null) {
            try { recordThread.join(3000); } catch (Exception e) { Log.e(TAG, "Thread join error"); }
            recordThread = null;
        }
        synchronized (micLock) {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                    audioRecord.release();
                } catch (Exception e) { Log.e(TAG, "AudioRecord stop error: " + e.getMessage()); }
                audioRecord = null;
            }
            byte[] pcmData = audioBuffer.toByteArray();
            if (pcmData.length > 100) {
                String b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP);
                Log.d(TAG, "Recording stopped, sending " + pcmData.length + " bytes to JS");
                final String js = "if(typeof onNativeRecordingReady==='function') onNativeRecordingReady('" + b64 + "')";
                activity.runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(js, null);
                });
            } else {
                Log.d(TAG, "Recording too short, ignoring");
                notifyJsError("Recording too short");
            }
        }
    }

    @JavascriptInterface
    public boolean isRecording() { return isRecording; }

    @JavascriptInterface
    public float getMicLevel() {
        synchronized (micLock) {
            if (audioRecord != null && isRecording) {
                try {
                    short[] buf = new short[256];
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0) {
                        long sum = 0;
                        for (int i = 0; i < read; i++) sum += Math.abs(buf[i]);
                        return (float) sum / read / 32768.0f;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "getMicLevel error: " + e.getMessage());
                }
            }
        }
        return 0;
    }

    private void notifyJsError(String msg) {
        final String safe = msg.replace("'", "\\'");
        activity.runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(typeof onNativeMicError==='function') onNativeMicError('" + safe + "')", null);
            }
        });
    }
}
