# Filament is driven through JNI: its classes, fields and methods are looked up by
# name from native code, so they must survive shrinking and obfuscation.
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.android.** { *; }
-keep class com.google.android.filament.filamat.** { *; }
-dontwarn com.google.android.filament.**
