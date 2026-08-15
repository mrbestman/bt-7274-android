# Keep all app classes
-keep class com.valen.assistant.** { *; }

# Keep WebView JavaScript interface
-keepclassmembers class com.valen.assistant.ValenBridge {
    @android.webkit.JavascriptInterface <methods>;
}
