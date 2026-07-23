# My Artifact

## Developer Setup

### Android Build Environment

> [!IMPORTANT]
> **Android Gradle Plugin (AGP) 9.2.1+ Compatibility**
>
> To avoid build failures during Gradle configuration (e.g., `Failed to create AndroidLocationsBuildService`), ensure that only `ANDROID_USER_HOME` is set in your environment variables.
>
> * **Keep**: `ANDROID_USER_HOME` (e.g., `C:\Users\<user>\.android`)
> * **Remove**: `ANDROID_PREFS_ROOT` (Deprecated)
>
> If both are set, AGP will throw an `AndroidLocationsException`, preventing the build from starting.
