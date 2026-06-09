# Hilt
-keep class com.saurabh.artifact.ArtifactApplication { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class *

# Firebase
# Firebase libraries include their own consumer ProGuard rules.
# Broad keep rules for com.google.firebase.** are usually unnecessary and bloat the APK.

# Media3 / ExoPlayer
# Media3 also provides its own consumer ProGuard rules.

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# Project Models
# Keeping model classes to prevent issues with reflection/serialization (e.g. Firebase)
-keep class com.saurabh.artifact.model.** { *; }

# Moshi
# If using Moshi with codegen, these are often handled by the generated JsonAdapters.
-keep class com.saurabh.artifact.model.**JsonAdapter { *; }

# AppSearch
-keep class androidx.appsearch.app.DocumentClassFactory { *; }
-keep class * implements androidx.appsearch.app.DocumentClassFactory { *; }
-keep class com.saurabh.artifact.model.** { *; }
-keep class **.$$__AppSearch__* { *; }
-keep @androidx.appsearch.annotation.Document class * { *; }
