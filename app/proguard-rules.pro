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

# ─── Glance ActionCallbacks (referenced by class name via actionRunCallback) ──
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }

# ─── Kotlinx Serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
# Keep serializable data classes and their generated $$serializer companions
-keep @kotlinx.serialization.Serializable class com.enderthor.kremote.** {
    <fields>;
    *** Companion;
}
-keep class com.enderthor.kremote.**$$serializer { *; }
-dontwarn kotlinx.serialization.**

# ─── Hammerhead karoo-ext — AIDL stubs (IPC with Karoo system) ───────────────
# Small package (~5 interfaces), safe to keep entirely
-keep class io.hammerhead.karooext.aidl.** { *; }

# ─── Hammerhead karoo-ext — base classes our code extends ────────────────────
-keep class io.hammerhead.karooext.extension.KarooExtension { *; }
-keep class io.hammerhead.karooext.extension.DataTypeImpl { *; }
-keep class io.hammerhead.karooext.KarooSystemService { *; }
-keep class io.hammerhead.karooext.internal.ViewEmitter { *; }

# ─── Hammerhead karoo-ext — models used in dispatch / addConsumer calls ───────
# Keep field names and constructors (needed for IPC serialization/reflection)
# but allow R8 to remove unused model classes
-keepnames class io.hammerhead.karooext.models.** { }
-keepclassmembers class io.hammerhead.karooext.models.** {
    <fields>;
    <init>(...);
}


# NO hay regla keep-all del paquete de la app: anularía shrinking/inlining de todo
# nuestro código. Lo que necesita sobrevivir a R8 ya está cubierto por reglas finas:
# serialización (arriba), enums values/valueOf, Services/Receivers (manifest + regla
# abajo), @Composable, callbacks onCommand/onStateChange y el stack ANT completo.
# Si una release crashea por ClassNotFound/NoSuchMethod tras este cambio, añadir la
# regla fina correspondiente — no restaurar el keep-all.

# Reglas para Timber
-dontwarn org.jetbrains.annotations.**
-dontwarn timber.log.**
-dontnote timber.log.**
# Elimina en release las llamadas a Timber.v/d/i/w (y sus argumentos si no tienen side effects)
# Solo ERROR y ASSERT llegan al árbol de release en runtime;
# con esto R8 elimina también la construcción de strings en call site.
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

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

# Jetpack Compose ya incluye sus propias reglas ProGuard en las AARs — no se necesita -keep general.
# Solo mantenemos los miembros anotados con @Composable que puedan ser accedidos por reflexión.
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
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
