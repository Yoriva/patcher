# Patcherlib: signed bundles/manifests are (de)serialized by kotlinx.serialization at runtime.
-keep class com.yoriva.lib.BundleManifest { *; }
-keep class com.yoriva.lib.BundleManifest$* { *; }
-keep class com.yoriva.lib.ExcludeRule { *; }
-keep class com.yoriva.lib.Bundle { *; }
-keep class com.yoriva.lib.Cli { *; }
-keep class com.yoriva.lib.ElfInfo { *; }
-keep class com.yoriva.lib.ElfVerdict { *; }
-keep class com.yoriva.lib.ElfVerdict$* { *; }
-keepnames @kotlinx.serialization.Serializable class **

# apksig: called by patcherlib directly; keep its signatures intact.
-keep class com.android.apksig.** { *; }

# patcherlib entry points used by the Android app and by scripts.
-keep class com.yoriva.lib.ApkPatcher { *; }
-keep class com.yoriva.lib.ApkPatcher$* { *; }
-keep class com.yoriva.lib.ApkSignerTool { *; }
-keep class com.yoriva.lib.ApkSignerTool$* { *; }
-keep class com.yoriva.lib.BundleBuilder { *; }
-keep class com.yoriva.lib.SigningKeystore { *; }
-keep class com.yoriva.lib.ZipAnalyzer { *; }
-keep class com.yoriva.lib.ZipAnalyzer$* { *; }
-keep class com.yoriva.lib.ZipRepacker { *; }
-keep class com.yoriva.lib.ZipRepacker$* { *; }
-keep class com.yoriva.lib.ZipRaw { *; }
-keep class com.yoriva.lib.ZipRaw$* { *; }
-keep class com.yoriva.lib.ElfKt { *; }
