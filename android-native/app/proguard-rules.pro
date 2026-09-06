# AScan Agent — ofuscacao basica (release)
-keep class com.ascan.ascanagent.MainActivity { *; }
-keep class com.ascan.ascanagent.ui.ScanViewModel { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.json.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
