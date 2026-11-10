# Keep BouncyCastle provider internals (registered reflectively by the JCA framework).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# apksig loads some classes reflectively.
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
