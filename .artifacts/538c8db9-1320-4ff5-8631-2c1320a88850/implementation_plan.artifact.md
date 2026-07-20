# Investigation Plan: Isolate DataSource.open() Latency

This plan aims to identify the specific component within the `DataSource` chain responsible for the 7–8 second delay during playback start.

## User Review Required

> [!IMPORTANT]
> This is an **investigation-only** change. I will add detailed instrumentation to the `DataSource` chain. No production logic or behavior will be changed.

## Proposed Changes

### [Audio Components]

#### [MODIFY] [MediaCache.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/MediaCache.kt)
- Add timing instrumentation for `SimpleCache` initialization.

#### [MODIFY] [SmartDataSourceFactory.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/SmartDataSourceFactory.kt)
- Introduce `DiagnosticDataSource` and `DiagnosticDataSourceFactory` wrappers.
- Wrap `CacheDataSource`, `DefaultDataSource`, and `HttpDataSource` with diagnostic wrappers.
- Measure and log construction time for each layer.
- Add granular logging for each phase of `open()`.

## Verification Plan

### Manual Verification
- Deploy the app to the device.
- Play a network-based artifact (one that is not yet cached).
- Monitor Logcat for `DIAG_DS_OPEN_*` events.
- Analyze the reported durations for:
    - `SimpleCache` init.
    - `HttpDataSource.open()` (Network latency).
    - `CacheDataSource.open()` (Total overhead).
    - `SmartDataSourceFactory.open()` (Overall duration).
