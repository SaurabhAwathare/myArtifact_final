# Networking Stack Modernization

This plan adds a standard, professional networking stack using Retrofit and OkHttp to the project. This complements the existing Firebase SDK by providing a robust way to call external APIs (like Gemini, OpenAI, or custom backends) with built-in logging and automatic Firebase authentication.

## Proposed Changes

### Build Configuration

#### [libs.versions.toml](file:///F:/Android Project/01/gradle/libs.versions.toml)
- Add versions and libraries for Retrofit, OkHttp, and Logging Interceptor.

#### [app/build.gradle.kts](file:///F:/Android Project/01/app/build.gradle.kts)
- Add the new networking dependencies.

---

### Networking Components

#### [NEW] [AuthInterceptor.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/util/AuthInterceptor.kt)
- A custom OkHttp interceptor that automatically attaches the current Firebase ID Token to every outgoing request's `Authorization` header.
- This ensures your custom backend or protected APIs can always verify the user's identity.

#### [NetworkModule.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/di/NetworkModule.kt)
- Provide `HttpLoggingInterceptor` (enabled only in Debug builds).
- Provide the `AuthInterceptor`.
- Provide a singleton `OkHttpClient` configured with both interceptors.
- Provide a singleton `Retrofit` instance configured with the `OkHttpClient` and the existing `Moshi` converter.

---

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure everything compiles correctly.
- Add a temporary test or check in `MainActivity` to verify `Retrofit` can be injected via Hilt.

### Manual Verification
- Verify that network logs appear in Logcat when a (future) Retrofit call is made.
- Verify that the `Authorization` header is present in outgoing requests (can be seen in Logcat via the logging interceptor).
