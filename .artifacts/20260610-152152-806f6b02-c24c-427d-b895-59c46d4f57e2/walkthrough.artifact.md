# Baseline Profiles Setup Walkthrough

I have successfully integrated Baseline Profiles into the "My Artifact" project. This setup will help improve the app's startup performance and reduce UI jank.

## Changes Made

### 1. Dependency and Plugin Configuration
- Added `androidx.baselineprofile`, `androidx.profileinstaller`, `uiautomator`, and `benchmark-macro-junit4` to [libs.versions.toml](file:///F:/Android Project/01/gradle/libs.versions.toml).
- Applied the `androidx.baselineprofile` plugin in the root [build.gradle.kts](file:///F:/Android Project/01/build.gradle.kts) and the app's [build.gradle.kts](file:///F:/Android Project/01/app/build.gradle.kts).

### 2. New Module: `:baselineprofile`
- Created a dedicated module for generating baseline profiles.
- Configured [build.gradle.kts](file:///F:/Android Project/01/baselineprofile/build.gradle.kts) with the `com.android.test` and `androidx.baselineprofile` plugins.
- Included the module in [settings.gradle.kts](file:///F:/Android Project/01/settings.gradle.kts).

### 3. Profile Generator Implementation
- Implemented [BaselineProfileGenerator.kt](file:///F:/Android Project/01/baselineprofile/src/main/java/com/saurabh/artifact/baselineprofile/BaselineProfileGenerator.kt).
- This test starts the application and captures the startup path to generate the profile.

## Verification Results

### Configuration Check
- Ran `./gradlew :app:generateBaselineProfile` which finished successfully, confirming that the tasks and module dependencies are correctly wired.

> [!NOTE]
> To actually generate the profile files (which will appear in `app/src/release/generated/baselineProfiles/`), you need to run the generation task while a physical device or emulator is connected. The plugin will automatically run the test on the device and pull the results.

### Next Steps
1. Connect a device/emulator.
2. Run `./gradlew :app:generateBaselineProfile`.
3. The generated profiles will be bundled with your release APK/Bundle automatically.
