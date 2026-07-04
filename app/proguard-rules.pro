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

# Preserve constructor parameters for Artifact to support reflection-based deserialization
# Note: This rule preserves constructor information only. It should be retained only if runtime validation confirms it is required.
# If the annotation fix alone resolves the issue, this rule should be removed to keep the ProGuard configuration minimal.
-keepclassmembers class com.saurabh.artifact.model.Artifact {
    <init>(...);
}

# Preserve metadata for Firestore deserializer
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,MethodParameters,InnerClasses,EnclosingMethod

# Keep Startup Initializers (required for manifest-based discovery)
-keep class * implements androidx.startup.Initializer {
    <init>();
}

# Moshi
# If using Moshi with codegen, these are often handled by the generated JsonAdapters.
-keep class com.saurabh.artifact.model.**JsonAdapter { *; }

# AppSearch
-keep class androidx.appsearch.app.DocumentClassFactory { *; }
-keep class * implements androidx.appsearch.app.DocumentClassFactory { *; }
-keep class com.saurabh.artifact.model.** { *; }
-keep class **.$$__AppSearch__* { *; }
-keep @androidx.appsearch.annotation.Document class * { *; }
