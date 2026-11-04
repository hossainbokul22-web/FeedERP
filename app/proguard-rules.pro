-keep class net.zetetic.database.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }

# Ktor সার্ভার/ক্লায়েন্ট (Android এ নেই এমন JVM ক্লাসের সতর্কতা)
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
