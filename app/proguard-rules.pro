# zip4j
-keep class net.lingala.zip4j.** { *; }
-keepclassmembers class net.lingala.zip4j.** { *; }

# Coil
-dontwarn coil.**

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
