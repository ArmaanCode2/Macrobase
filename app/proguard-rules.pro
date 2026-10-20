# MacroBase ProGuard / R8 Rules

# Shrink and optimise, but keep class and method names, source files and line numbers: a crash
# stack trace stays mostly readable on its own (the app sends no crash reports anywhere), and the
# startup profile's rules match. Frames in code R8 inlined still need that release's mapping.txt
# (app/build/outputs/mapping/release/) to expand.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Room SQLite preservation
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Koin DI preservation
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
