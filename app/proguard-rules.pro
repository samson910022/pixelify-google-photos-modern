# Keep the module entry class
-keep class io.github.samson910022.pixelifyphotos.PixelifyModule

# libxposed API: keep module entry even with R8 optimization
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# libxposed service (provided by framework at runtime)
-keep class io.github.libxposed.service.** { *; }
-dontwarn io.github.libxposed.service.IXposedService

# Keep the entry-point resource synchronized if R8 renames the module class.
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Keep DeviceSpoofer + nested JNI bridge names stable for libpixelify_build.
-keep class io.github.samson910022.pixelifyphotos.DeviceSpoofer {
    *;
}
-keep class io.github.samson910022.pixelifyphotos.DeviceSpoofer$BuildFieldNative {
    native <methods>;
    *;
}
