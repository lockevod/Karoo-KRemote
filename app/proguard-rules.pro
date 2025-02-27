# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.

# Mantener la extensión Karoo
-keep class io.hammerhead.karooext.** { *; }

# Mantener las clases de tu aplicación
-keep class com.enderthor.kremote.** { *; }
-keep class com.enderthor.kremote.ant.** { *; }
-keep class com.enderthor.kremote.activity.** { *; }
-keep class com.enderthor.kremote.data.** { *; }
-keep class com.enderthor.kremote.extension.** { *; }
-keep class com.enderthor.kremote.receiver.** { *; }
-keep class com.enderthor.kremote.service.** { *; }
-keep class com.enderthor.kremote.screens.** { *; }
-keep class com.enderthor.kremote.viewmodel.** { *; }


# Reglas para Timber
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.** { *; }

# Reglas generales para Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# Mantener constructores necesarios para JSON/Serialización si los usas
-keepclassmembers class * {
    public <init>();
}

# Mantener enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Si usas Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Mantener interfaces de callbacks y listeners
-keepclassmembers class * {
    void onCommand(**);
    void onStateChange(**);
    void onConnectionStateChange(**);
}

# Reglas específicas para servicios en segundo plano
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver

# Si usas Composables
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.** *;
}

-keep class com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc { *; }
-keep class com.dsi.ant.plugins.antplus.pcc.controls.defines.CommandStatus { *; }
-keep class com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber { *; }
-keep class com.dsi.ant.plugins.antplus.pcc.defines.** { *; }
-keep class com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle { *; }

# Mantener interfaces específicas usadas
-keep interface com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc$IGenericCommandReceiver { *; }
-keep interface com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc$IDeviceStateChangeReceiver { *; }
-keep interface com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc$IPluginAccessResultReceiver { *; }

# Mantener enums y eventos
-keep class com.dsi.ant.plugins.antplus.pcc.defines.EventFlag { *; }
-keep class com.dsi.ant.plugins.antplus.pcc.defines.DeviceState { *; }
-keep class com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult { *; }
