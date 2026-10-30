# Keep WebRTC native symbols
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }

# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep model classes used in serialization
-keep class com.remotebridge.core.model.** { *; }

# Keep the sealed Signal hierarchy (kotlinx.serialization polymorphic)
-keep class com.remotebridge.core.signaling.** { *; }

# Keep the JavaScript bridge used by the embedded web-dashboard WebView
-keepclassmembers class com.remotebridge.ui.screens.web.** {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager (kept automatically, explicit for R8 full mode safety)
-keep class androidx.work.Worker { *; }
-keep class androidx.work.CoroutineWorker { *; }
-dontwarn androidx.work.**

# OkHttp (webrtc transport diagnostics)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
