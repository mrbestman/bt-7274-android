# Keep WebView JavaScript interface
-keepclassmembers class com.valen.assistant.ValenBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep SSL helper
-keep class com.valen.assistant.SslHelper { *; }
