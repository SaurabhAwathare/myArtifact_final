# Android App Links Migration & Domain Strategy

This document outlines the Verified App Links infrastructure for Artifact and the procedure for future custom domain migration.

## Current Infrastructure
The following domains are currently verified for Android App Links:
- `myartifact-555e3.web.app` (Primary Firebase Hosting)
- `myartifact-555e3.firebaseapp.com` (Secondary Firebase Hosting)

All deep links are scoped to the `/a/*` path prefix.

## Asset Links
The Digital Asset Links file is located at `public/.well-known/assetlinks.json`. It supports:
- **Debug Fingerprint**: For local development verification.
- **Release Fingerprint**: For signed release builds.
- **Play Signing Fingerprint**: For distribution via Google Play.

## Custom Domain Migration Procedure
When migrating to a custom domain (e.g., `https://myartifact.app`):

1. **Hosting Setup**:
   - Connect the custom domain to Firebase Hosting via the Firebase Console.
   - Ensure the domain is verified and SSL is active.

2. **Asset Links Update**:
   - Ensure the new domain serves the same `assetlinks.json` at `https://myartifact.app/.well-known/assetlinks.json`.

3. **Manifest Update**:
   - Update `AndroidManifest.xml` to include the new host:
     ```xml
     <data android:host="myartifact.app" />
     ```

4. **Redeployment**:
   - Deploy the Hosting updates: `firebase deploy --only hosting`.
   - Publish the updated App Manifest via a new app release.

5. **Verification**:
   - Run `adb shell pm verify-app-links --re-verify com.saurabh.artifact` on a device with the new version.
   - Run `adb shell pm get-app-links com.saurabh.artifact` to confirm the new host status is `verified`.
