# ============================================================================
#  Autoqqpet release R8 rules（源码公开版）
#
#  只做代码裁剪/优化，不做混淆：类名、方法名保持原样，崩溃堆栈可直接读。
#  下面只保留功能正确所必需的 keep 规则。
# ============================================================================

-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# --- (a) Xposed / LSPosed module entry -------------------------------------
# The framework loads this exact FQCN from META-INF/xposed/java_init.list.
-keep class io.github.juwanhwang.autoqqpet.hook.ApplicationHook { *; }
-keep class * extends io.github.libxposed.api.XposedModule { *; }
-dontwarn io.github.libxposed.**

# --- (b) Manifest-declared components (referenced by name from the manifest) -
-keep class io.github.juwanhwang.autoqqpet.AutoqqpetApplication { *; }
-keep class io.github.juwanhwang.autoqqpet.keepalive.BootReceiver { *; }
-keep class io.github.juwanhwang.autoqqpet.keepalive.KeepAliveService { *; }
-keep class io.github.juwanhwang.autoqqpet.keepalive.StatusReceiver { *; }
-keep class io.github.juwanhwang.autoqqpet.store.StoreReceiver { *; }
-keep class io.github.juwanhwang.autoqqpet.ui.JobCatalogReceiver { *; }
-keep class io.github.juwanhwang.autoqqpet.ui.MainActivity { *; }

# --- (c) Enums serialized/parsed by constant NAME --------------------------
# Schedule persistence writes `enum.name` into schedule_json and reads it back with
# enumValueOf/valueOf. Keeping values()/valueOf() + field names for every enum removes the risk.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# --- (d) Kotlin coroutines main-dispatcher ServiceLoader -------------------
# Compose + coroutines find the Android main dispatcher via META-INF/services.
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.**

# --- (e) org.json is provided by the Android platform ----------------------
-dontwarn org.json.**
