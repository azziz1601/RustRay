# Keep the xray-rust JNI surface.
-keep class org.xrayrust.mobile.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep Shizuku API & reflected Shizuku.newProcess.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.server.** { *; }
