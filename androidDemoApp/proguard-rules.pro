# Add project specific ProGuard rules here.

# Keep all resources including launcher icons
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep drawable and mipmap resources
-keep class **.R$drawable { *; }
-keep class **.R$mipmap { *; }

# Preserve all native method names and the names of their classes.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===== Your App Classes =====
# Keep all ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
-keep class com.michaeltchuang.walletsdk.demo.** { *; }
-keep class com.michaeltchuang.walletsdk.ui.** { *; }
-keep class com.michaeltchuang.walletsdk.core.** { *; }

# Keep all Koin modules and definitions
-keep class * extends org.koin.core.module.Module { *; }
-keepclassmembers class com.michaeltchuang.** {
    public <init>(...);
}

# ===== Kotlin Serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializers
-keep,includedescriptorclasses class com.michaeltchuang.**$$serializer { *; }
-keepclassmembers class com.michaeltchuang.** {
    *** Companion;
}
-keepclasseswithmembers class com.michaeltchuang.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable classes
-keep @kotlinx.serialization.Serializable class * { *; }

# ===== Ktor =====
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }

# ===== Koin =====
-keep class org.koin.** { *; }
-keep class org.koin.core.** { *; }
-keep class org.koin.dsl.** { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}
# Keep all classes that might be injected by Koin
-keepnames class * {
    <init>(...);
}
-keepclassmembers class * {
    public <init>(...);
}

# ===== Room Database (KMP) =====
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** getInstance(android.content.Context);
}
-dontwarn androidx.room.paging.**

# ===== DataStore =====
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.core.DataStore {
    *;
}

# ===== Compose =====
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.animation.tooling.**
-dontwarn androidx.compose.desktop.**
-dontwarn androidx.compose.ui.tooling.**

# ===== Coil (Image Loading) =====
-keep class coil3.** { *; }
-keep interface coil3.** { *; }
-dontwarn coil3.**

# ===== Algorand SDKs =====
-keep class com.algorand.** { *; }
-keep interface com.algorand.** { *; }
-keepclassmembers class com.algorand.** { *; }

# ===== Crypto/Security Libraries =====
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }

-keep class org.bitcoinj.** { *; }

# Keep Tink crypto
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }

# ===== Kotlin Coroutines =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ===== QR Code Libraries =====
-keep class network.chaintech.qr_kit.** { *; }
-keep class com.google.zxing.** { *; }

# ===== Navigation =====
-keep class androidx.navigation.** { *; }
-keepclassmembers class androidx.navigation.** { *; }

# ===== WebView =====
-keep class com.multiplatform.webview.** { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}

# ===== Firebase =====
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ===== Kotlin Parcelize =====
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep @kotlinx.parcelize.Parcelize class * { *; }

# ===== BigNum =====
-keep class com.ionspin.kotlin.bignum.** { *; }

# ===== Animation (Compottie/Lottie) =====
-keep class io.github.alexzhirkevich.compottie.** { *; }

# ===== Ignore warnings for missing classes that are not used on Android =====
-dontwarn com.goterl.resourceloader.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn java.lang.management.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.swing.**
-dontwarn com.sun.jna.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn net.pwall.json.pointer.JSONPointer
-dontwarn net.pwall.json.schema.JSONSchema
-dontwarn net.pwall.json.schema.output.BasicOutput
-dontwarn org.joda.time.**
-dontwarn org.ktorm.entity.**
-dontwarn sun.nio.ch.DirectBuffer
-dontwarn okhttp3.internal.platform.**
-dontwarn org.slf4j.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn javax.annotation.**

# ===== Keep line numbers for debugging =====
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
