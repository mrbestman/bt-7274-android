package com.valen.assistant;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.PowerManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ValenMain";
    private WebView webView;
    private PermissionManager permissionManager;
    private PowerManager.WakeLock wakeLock;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            SslHelper.trustSelfSignedCerts();
        } catch (Exception e) {
            Log.e(TAG, "SSL init failed (non-fatal): " + e.getMessage());
        }

        try {
            permissionManager = new PermissionManager(this);
            permissionManager.requestAllPermissions();
        } catch (Exception e) {
            Log.e(TAG, "Permission init failed: " + e.getMessage());
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Valen::BackgroundLock");
            wakeLock.acquire(10 * 60 * 1000L);
        } catch (Exception e) {
            Log.e(TAG, "Wake lock failed (non-fatal): " + e.getMessage());
        }

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        if (webView == null) {
            Log.e(TAG, "WebView is null! Layout issue.");
            finish();
            return;
        }
        configureWebView();

        // Try loading from cached server URL first, fall back to assets
        String cachedUrl = getSharedPreferences("valen", MODE_PRIVATE).getString("server_url", null);
        if (cachedUrl != null) {
            Log.d(TAG, "Loading cached server URL: " + cachedUrl);
            webView.loadUrl(cachedUrl);
        } else {
            Log.d(TAG, "No cached URL, loading from assets for discovery");
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new ValenBridge(this, webView), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }
}
