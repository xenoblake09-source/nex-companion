# NEX ProGuard/R8 rules.
#
# NEX parses JSON by hand (org.json) and reflects nothing, so there are no model classes to
# keep. These rules only silence warnings from OkHttp's optional TLS providers and keep
# line numbers for readable crash reports.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp: optional platform TLS providers that may be absent on a device.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# CameraX uses reflection-free but interface-heavy bindings; keep the public entry points.
-keep class androidx.camera.lifecycle.ProcessCameraProvider { *; }
-keepclassmembers class androidx.camera.core.** { public <init>(...); }

# Kotlin metadata is not needed at runtime.
-dontwarn kotlin.**
