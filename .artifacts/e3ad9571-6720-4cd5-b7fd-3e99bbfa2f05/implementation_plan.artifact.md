# Investigation: Isolate the Exact Source of DataSource.open() Latency

This plan aims to identify the specific component responsible for the 7–8 second delay observed during `DataSource.open()`.

## User Review Required

> [!IMPORTANT]
> This is a diagnostic-only change. I will be adding temporary logs to the playback path. These logs should be removed once the bottleneck is identified.

## Proposed Changes

### [Audio Components]

#### [MODIFY] [MediaCache.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/MediaCache.kt)
- Add timing around `SimpleCache` initialization to see if it occurs during playback and how long it takes.

#### [MODIFY] [SmartDataSourceFactory.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/SmartDataSourceFactory.kt)
- Introduce a `DiagnosticDataSource` wrapper class to measure the lifecycle of `open()` and the first `read()`.
- Wrap `CacheDataSource` and its upstream `DefaultDataSource` with this diagnostic wrapper.
- Log specific events with high-resolution timestamps (`SystemClock.elapsedRealtime()`):
    - `SMART_OPEN_START`
    - `CACHE_DS_OPEN_START`
    - `UPSTREAM_OPEN_START`
    - `HTTP_HEADERS_RECEIVED` (End of `upstream.open()`)
    - `FIRST_BYTE_RECEIVED` (End of first `upstream.read()`)
    - `UPSTREAM_OPEN_END`
    - `CACHE_DS_OPEN_END`
    - `SMART_OPEN_END`

## Verification Plan

### Manual Verification
- Deploy the app and start playback.
- Observe the Logcat for the diagnostic logs.
- Analyze the `elapsed` times to determine which layer is slow.

## Stopping Condition

> [!IMPORTANT]
> If more than 80% of the total `SMART_OPEN` duration is consistently attributable to a single layer across multiple playback attempts, the investigation is complete. Do not continue adding instrumentation after identifying the dominant bottleneck.
