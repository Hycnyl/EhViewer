-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers,allowobfuscation class com.hippo.ehviewer.coil.AnimatedWebPDrawable {
    java.nio.ByteBuffer source;
}

# Ktor logger
-dontwarn org.slf4j.impl.StaticLoggerBinder

# https://issuetracker.google.com/222232895
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.Sidecar*

-keepattributes LineNumberTable

-allowaccessmodification
-repackageclasses

# ReadCacheCleaner enumerates existing Coil 3.3 entries through this field.
-keep class coil3.disk.RealDiskCache {
    coil3.disk.DiskLruCache cache;
}
