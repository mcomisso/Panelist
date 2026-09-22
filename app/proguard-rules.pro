# Keep snakeyaml (loaded reflectively by the parser)
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# Kotlin coroutines / OkHttp tweaks
-dontwarn okhttp3.**
-dontwarn okio.**
