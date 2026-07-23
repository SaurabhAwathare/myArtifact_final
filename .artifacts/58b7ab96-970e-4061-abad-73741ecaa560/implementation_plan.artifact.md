# Investigation of Gradle/Android Build Environment Failure

This plan outlines the findings of the investigation into the `AndroidLocationsBuildService` initialization failure and the proposed corrective action.

## Problem Statement

The Gradle build fails during the configuration phase with the following error:
- `Failed to apply plugin 'com.android.application'`
- `Failed to create AndroidLocationsBuildService`
- `Could not create provider for AndroidDirectoryCreator`

This prevents any Gradle tasks, including compilation and testing, from running.

## Evidence Collected

- **Stack Trace Analysis**: Running `./gradlew help --stacktrace` revealed the root cause:
  `Caused by: com.android.prefs.AndroidLocationsException: Several environment variables and/or system properties contain different paths to the Android Preferences folder.`
- **Environment Variable Check**: The environment variables `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME` are both set to `C:\Users\monua\.android`.
- **AGP/Gradle Versions**: The project uses AGP `9.2.1` and Gradle `9.5.0`. Recent versions of AGP have stricter checks for preference location configuration.
- **Verification**: Manually unsetting `ANDROID_PREFS_ROOT` in the shell session allowed the build to complete successfully (`BUILD SUCCESSFUL in 6s`).

## Root Cause

The root cause is a **configuration conflict** in the environment variables. The Android Gradle Plugin (AGP) detects multiple environment variables (`ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME`) that define the location of the Android preferences folder (`.android`). Even though they point to the same path, AGP 9.2.1 requires that only one (preferably `ANDROID_USER_HOME`) be set.

`AndroidDirectoryCreator` fails because it relies on the `AndroidLocationsBuildService`, which in turn fails to initialize when `PathLocator` throws the `AndroidLocationsException` due to this conflict.

## Confidence Level

**High (100%)**: The error message explicitly identified the conflicting variables, and temporary removal of the redundant variable resolved the issue.

## Proposed Changes

> [!IMPORTANT]
> This investigation was focused on the build environment only. No modifications to production source code are required.

### Build Environment

#### [MODIFY] User/System Environment Variables
- **Action**: Permanently remove the `ANDROID_PREFS_ROOT` environment variable from the system or user profile.
- **Rationale**: `ANDROID_PREFS_ROOT` is deprecated. `ANDROID_USER_HOME` is the modern and recommended way to specify the preferences location.

## Verification Plan

### Manual Verification
1. Open a new terminal session (to ensure environment variables are refreshed).
2. Run the following command to verify the fix:
   ```powershell
   ./gradlew help
   ```
3. If the environment variable cannot be removed globally immediately, use this one-liner for the current session:
   ```powershell
   $env:ANDROID_PREFS_ROOT=$null; ./gradlew help
   ```
