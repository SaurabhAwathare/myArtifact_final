# Baseline Profiles Integration

This plan outlines the steps to integrate Baseline Profiles into the "My Artifact" project to improve app startup speed and reduce UI jank in the Compose-based application.

## Proposed Changes

### [Dependency Management]

#### [libs.versions.toml](file:///F:/Android Project/01/gradle/libs.versions.toml)

- Add versions for `baselineprofile`, `profileinstaller`, `uiautomator`, and `benchmark-macro-junit4`.
- Add library definitions for the above.
- Add the `androidx.baselineprofile` plugin definition.

### [Root Project Configuration]

#### [build.gradle.kts](file:///F:/Android Project/01/build.gradle.kts) (root)

- Register the `androidx.baselineprofile` plugin in the `plugins` block.

#### [settings.gradle.kts](file:///F:/Android Project/01/settings.gradle.kts)

- Include the new `:baselineprofile` module.

### [App Module Configuration]

#### [build.gradle.kts](file:///F:/Android Project/01/app/build.gradle.kts)

- Apply the `androidx.baselineprofile` plugin.
- Add `androidx.profileinstaller` dependency.
- Configure the `baselineProfile` block to point to the `:baselineprofile` module.

### [Baseline Profile Module]

#### [NEW] [build.gradle.kts](file:///F:/Android Project/01/baselineprofile/build.gradle.kts)

- Configure the module as a test module with the `androidx.baselineprofile` plugin.

#### [NEW] [AndroidManifest.xml](file:///F:/Android Project/01/baselineprofile/src/main/AndroidManifest.xml)

- Basic manifest for the test module.

#### [NEW] [BaselineProfileGenerator.kt](file:///F:/Android Project/01/baselineprofile/src/main/java/com/saurabh/artifact/baselineprofile/BaselineProfileGenerator.kt)

- UI test to generate the baseline profile by starting the app and performing basic interactions.

## Verification Plan

### Automated Tests
- Run the baseline profile generation task:
  ```powershell
  ./gradlew :app:generateBaselineProfile
  ```
- This will:
    1. Build the release version of the app.
    2. Run the `BaselineProfileGenerator` test.
    3. Generate the `baseline-prof.txt` file in `app/src/main/generated/baselineProfiles/`.

### Manual Verification
- Verify that `baseline-prof.txt` is created in the `app` module.
- Check the Gradle output for any errors during generation.
