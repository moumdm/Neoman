# junrar / commons-compress 保留规则
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
