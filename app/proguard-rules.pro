# Keep data models for Firestore & local serialization
-keep class ru.pokanevich.familybudget.data.** { *; }

# NotificationListenerService для автоучёта пушей Сбера и Mir Pay (биндится системой по имени из манифеста)
-keep class ru.pokanevich.familybudget.service.BankPushListenerService { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# org.json
-keep class org.json.** { *; }
-dontwarn org.json.**

# ML Kit Barcode Scanning
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**

# CameraX
-keep class androidx.camera.** { *; }
-keepclassmembers class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# BiometricPrompt (FragmentActivity / BiometricFragment / BiometricViewModel)
-keep class androidx.biometric.** { *; }
-keepclassmembers class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**
