/* Keep the app small and fast. Most of the hot path is Kotlin data plumbing. */
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# --- Serialized network models ---------------------------------------------------------------
-keepclassmembers class dev.ibb.launcher.data.** {
    <init>(...);
    *** Companion;
}
-keepclasseswithmembers class dev.ibb.launcher.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.ibb.launcher.**$$serializer { *; }

# --- JNI bridge ------------------------------------------------------------------------------
# The native execution layer resolves these by exact name; never rename or strip them.
-keep class dev.ibb.launcher.game.nativebridge.** { *; }
-keepclasseswithmembernames class com.oracle.dalvik.** { native <methods>; }
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class org.lwjgl.glfw.GLFW { *; }
-keep class dev.ibb.launcher.game.lwjglstub.** { *; }

# --- Reflection-instantiated components ------------------------------------------------------
-keep class * extends android.app.Service
-keep class * extends android.app.Application
-keep class * extends androidx.activity.ComponentActivity

# --- Optional: readable crash reporting ------------------------------------------------------
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Quiet the usual Compose / Kotlin metadata warnings.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.serialization.**

# ------------------------------------------------------------------------------------------------
# Native bridge: the symbol names are derived from these classes, so they must not be renamed or
# stripped. `libpojavexec.so` looks them up by the literal `Java_net_kdt_pojavlaunch_utils_...`
# symbol name — a renamed method is a hard crash inside the game thread with no Kotlin stack.
# ------------------------------------------------------------------------------------------------
-keep class net.kdt.pojavlaunch.utils.** { *; }
-keep class com.oracle.dalvik.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers class * {
    @JvmStatic <methods>;
}
