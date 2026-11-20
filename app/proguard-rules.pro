# Keep the xray-rust JNI surface.
-keep class org.xrayrust.mobile.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep RustRay native SSH JNI bridge and VpnService.protectSocket callback.
-keep class io.rustray.app.RustSshBridge { *; }
-keep class io.rustray.app.AppVpnService {
    public boolean protectSocket(int);
}

# Keep Shizuku API & reflected Shizuku.newProcess.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.server.** { *; }
