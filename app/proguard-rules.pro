# Kotlin serialization (the chat/session wire + persistence format).
# The release build runs R8: without these keeps the generated serializers
# can be stripped or renamed and every JSON round-trip breaks at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep the `Companion`/`serializer()` machinery of any @Serializable class
# in THIS app's package (Msg, Session, ToolCall, …).
-keepclassmembers class id.codingsachet.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class id.codingsachet.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class id.codingsachet.mobile.**$$serializer { *; }

# OkHttp platform probes (from the okhttp proguard README).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
