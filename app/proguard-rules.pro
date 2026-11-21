# ==========================================
# Komorebi R8 / ProGuard rules
# ==========================================

# First R8 rollout: keep shrinking and optimization while preserving names for
# Gson, JNI, enum.valueOf, and readable stack traces.
-dontobfuscate

# Preserve generic signatures, annotations, and anonymous TypeToken metadata in R8 fullMode.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# ------------------------------------------
# 1. Gson / JSON models & TypeToken
# ------------------------------------------
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class com.beeregg2001.komorebi.data.model.** { *; }

# Gson types outside data.model (Ktor setup routes & DataStore JSON lists)
-keep class com.beeregg2001.komorebi.data.ConnectionSettingsSnapshot { *; }
-keep class com.beeregg2001.komorebi.data.ConnectionSourceSettings { *; }
-keep class com.beeregg2001.komorebi.data.ConnectionAccessSettings {
    <init>(...);
    <fields>;
}
-keep class com.beeregg2001.komorebi.data.ConnectionSmbSettings {
    <init>(...);
    <fields>;
}
-keep class com.beeregg2001.komorebi.data.ConnectionAccountSettings {
    <init>(...);
    <fields>;
}
-keep class com.beeregg2001.komorebi.viewmodel.SmbServer {
    <init>(...);
    <fields>;
}
-keep class com.beeregg2001.komorebi.viewmodel.PostRecordingBatch {
    <init>(...);
    <fields>;
}

# Enum reflection safety
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ------------------------------------------
# 2. JNI (app/src/main/cpp/native-lib.cpp)
# ------------------------------------------
# external fun holder (prevent class merging / descriptor alteration of JNI entry points)
-keep,includedescriptorclasses class com.beeregg2001.komorebi.NativeLib {
    native <methods>;
}

# Caption models instantiated via FindClass + NewObject in native-lib.cpp
-keep class com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue {
    <init>(...);
}
-keep class com.beeregg2001.komorebi.ui.subtitle.NativeCaptionImage {
    <init>(...);
}
-keep class com.beeregg2001.komorebi.ui.subtitle.NativeCaptionRegion {
    <init>(...);
}

# TLV demuxer callback invoked via GetObjectClass + GetMethodID in native-lib.cpp
-keep interface com.beeregg2001.komorebi.util.mmts.NativeTlvDemuxer$Callback { *; }
-keepclassmembers class * implements com.beeregg2001.komorebi.util.mmts.NativeTlvDemuxer$Callback {
    public <methods>;
}

# ------------------------------------------
# 3. Third-party native / reflection libraries
# ------------------------------------------
# DanmakuFlameMaster (dfm) & ndkbitmap
-keep class master.flame.danmaku.** { *; }
-keep class tv.cjump.jni.** { *; }

# jcifs-ng (SMB) — suppress optional JDK Kerberos/JAAS/JGSS and Servlet API references
-keep class jcifs.** { *; }
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.servlet.**
-dontwarn org.ietf.jgss.**

# Jellyfin Media3 FFmpeg audio decoder (loaded via reflection by DefaultRenderersFactory)
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# ------------------------------------------
# 4. Ktor missing platform references
# ------------------------------------------
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
