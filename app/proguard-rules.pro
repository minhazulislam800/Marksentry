# Keep any @JavascriptInterface-annotated methods in case a JS bridge is
# added later. Not currently used, but harmless to keep as a safety net.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Standard: keep line numbers for readable stack traces in crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
