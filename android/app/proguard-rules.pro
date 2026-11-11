# Patcherlib: signed bundles/manifests are (de)serialized by kotlinx.serialization at runtime.
-keep class com.pickle.patcher.lib.BundleManifest { *; }
-keep class com.pickle.patcher.lib.BundleManifest$* { *; }
-keep class com.pickle.patcher.lib.ExcludeRule { *; }
-keep class com.pickle.patcher.lib.Bundle { *; }
-keep class com.pickle.patcher.lib.Cli { *; }
-keep class com.pickle.patcher.lib.ElfInfo { *; }
-keep class com.pickle.patcher.lib.ElfVerdict { *; }
-keep class com.pickle.patcher.lib.ElfVerdict$* { *; }
-keepnames @kotlinx.serialization.Serializable class **

# apksig: called by patcherlib directly; keep its signatures intact.
-keep class com.android.apksig.** { *; }

# patcherlib entry points used by the Android app and by scripts.
-keep class com.pickle.patcher.lib.ApkPatcher { *; }
-keep class com.pickle.patcher.lib.ApkPatcher$* { *; }
-keep class com.pickle.patcher.lib.ApkSignerTool { *; }
-keep class com.pickle.patcher.lib.ApkSignerTool$* { *; }
-keep class com.pickle.patcher.lib.BundleBuilder { *; }
-keep class com.pickle.patcher.lib.SigningKeystore { *; }
-keep class com.pickle.patcher.lib.ZipAnalyzer { *; }
-keep class com.pickle.patcher.lib.ZipAnalyzer$* { *; }
-keep class com.pickle.patcher.lib.ZipRepacker { *; }
-keep class com.pickle.patcher.lib.ZipRepacker$* { *; }
-keep class com.pickle.patcher.lib.ZipRaw { *; }
-keep class com.pickle.patcher.lib.ZipRaw$* { *; }
-keep class com.pickle.patcher.lib.ElfKt { *; }
