# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.southstar21.volum.** { *** Companion; }
-keepclasseswithmembers class dev.southstar21.volum.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
