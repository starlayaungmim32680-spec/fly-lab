# Fly Lab - R8 keep rules (16 Nov 2026).
# MediaPipe Tasks: the native graph calls back into these Java classes and
# reads its options as protobuf messages by reflection - R8 must keep them.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# AutoValue classes used by MediaPipe options builders.
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
# GPUPixel (same rule as Fly): JNI calls back into these classes by name.
-keep class com.pixpark.gpupixel.** { *; }
# 16 Nov 2026 (second test: "IllegalStateException: no caller found on the
# stack for: v0.c"): MediaPipe logs through Google's Flogger, which finds its
# caller by CLASS NAME on the stack - R8 had renamed the Flogger classes
# (v0.c). Keep Flogger (and the Guava classes MediaPipe uses) un-renamed.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
