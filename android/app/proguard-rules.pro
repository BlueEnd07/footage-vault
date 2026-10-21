# OkHttp / Okio: optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# osmdroid
-dontwarn org.osmdroid.**
-keep class org.osmdroid.** { *; }
# WorkManager creates workers by class name
-keep class com.footagevault.app.work.BackupWorker { <init>(...); }
