# Kura Scan — règles R8.

# kotlinx.serialization : garder les sérialiseurs générés (modèles de config, opérations, résultats).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.kura.** {
    *** Companion;
}
-keepclasseswithmembers class dev.kura.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.kura.**$$serializer { *; }

# Bouncy Castle : seules l'API légère Argon2 / Ed25519 est utilisée, le reste est retiré par R8.
-dontwarn org.bouncycastle.**

# OkHttp : plateformes optionnelles absentes sur Android.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
