# kotlinx.serialization keeps its generated serializers via annotations.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class co.chatie.app.** {
    *** Companion;
}
-keepclasseswithmembers class co.chatie.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio platform-specific optional classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
