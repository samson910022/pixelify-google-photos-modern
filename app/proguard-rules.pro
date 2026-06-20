# Keep the module entry class
-keep class balti.xposed.pixelifygooglephotos.PixelifyModule

# libxposed API: keep module entry even with R8 optimization
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# libxposed service (provided by framework at runtime)
-keep class io.github.libxposed.service.** { *; }
-dontwarn io.github.libxposed.service.IXposedService
