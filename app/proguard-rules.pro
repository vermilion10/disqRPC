# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Retrofit ships its own consumer rules; keep the service interfaces for safety.
-keep,allowobfuscation,allowshrinking interface com.github.vermilion10.disqrpc.data.remote.** {
    public *;
}

# Room entity used by generated DAO/DB code.
-keep,allowobfuscation,allowshrinking class com.github.vermilion10.disqrpc.data.local.GameConfig {
    *;
}

# Models carried across module boundaries / UI state.
-keep class com.github.vermilion10.disqrpc.util.Logger$LogEntry { *; }
-keep class com.github.vermilion10.disqrpc.data.remote.PlayStoreScraper$AppMetadata { *; }

# Preserve line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile