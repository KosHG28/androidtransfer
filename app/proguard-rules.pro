# Keep kotlinx.serialization models used for the transfer protocol
-keepattributes *Annotation*, InnerClasses
-keepclasseswithmembers class dev.androidtransfer.app.core.transfer.** { *; }
-keepclasseswithmembers class dev.androidtransfer.app.core.transport.** { *; }
-dontwarn kotlinx.serialization.**
