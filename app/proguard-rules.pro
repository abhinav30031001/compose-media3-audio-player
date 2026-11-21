# ProGuard & R8 Hardening Rules for YT Music Clone
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Gson serialized model classes and specific local storage entities
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.ytmusic.clone.data.model.** { *; }
-keep class com.ytmusic.clone.data.local.Playlist { *; }
-keep class com.ytmusic.clone.data.local.LibraryData { *; }
-keep class com.ytmusic.clone.data.local.SavedPlayerState { *; }
-keep class com.ytmusic.clone.data.local.BackupEnvelope { *; }
-keep class com.ytmusic.clone.data.local.BackupSummary { *; }
-keep class com.ytmusic.clone.data.local.AudioSettingsBackup { *; }

# Strip Android Log calls in release builds to eliminate information disclosure
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# OkHttp & Okio rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coroutines internal factories
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Android Entrypoints & Automotive Service
-keep public class com.ytmusic.clone.player.MusicPlayerService { *; }
-keep public class com.ytmusic.clone.MainActivity { *; }
-keep public class com.ytmusic.clone.YTMusicApplication { *; }

# BouncyCastle Ed25519 strip rules (Task B1)
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.signers.Ed25519Signer {
    public <init>();
    public void init(boolean, org.bouncycastle.crypto.CipherParameters);
    public void update(byte[], int, int);
    public boolean verifySignature(byte[]);
}
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters {
    public <init>(byte[], int);
}
