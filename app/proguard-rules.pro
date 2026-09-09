# OptIcon ProGuard/R8 Rules
# Based on libxposed API 102 official recommendations

# Suppress annotation warnings
-dontwarn io.github.libxposed.annotation.**

# Rewrite java_init.list when entry classes are obfuscated
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Keep module entry classes from being removed
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Note: no keep rules needed for SystemUI/framework classes (Notification,
# IconManager, StatusBarIconView, ...) — they live in the hook target process,
# not in this APK. R8 never strips classes it doesn't contain.
