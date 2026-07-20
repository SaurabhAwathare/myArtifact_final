# Implementation Plan - Isolate DataSource.open() Latency

This plan aims to instrument `SmartDataSourceFactory` and the underlying `DataSource` chain to identify the specific operation causing the 7-8 second latency in `open()`.

## User Review Required

> [!IMPORTANT]
> This investigation involves adding detailed logging and wrapping standard Media3 components with diagnostic layers. While "instrumentation only", it may slightly increase log volume. The changes will be reverted once the bottleneck is identified.

## Proposed Changes

### Audio Component

#### [MODIFY] [SmartDataSourceFactory.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/SmartDataSourceFactory.kt)

- Introduce an internal `DiagnosticDataSource` wrapper class to measure `open()` and `read()` (TTFB) durations.
- Instrument `open()` to measure:
    - Initial logic and DataSource construction.
    - `CacheDataSource.open()` duration.
    - `Upstream (Network) DataSource.open()` duration.
    - Time to first byte (TTFB) from the network.
- Log Firebase Auth state and potential token refresh overhead during `open()`.
- Record HTTP response headers (Content-Length, Content-Type, etc.) to evaluate Firebase Storage response.

## Verification Plan

### Manual Verification
1. Deploy the app to a physical device or emulator.
2. Clear the app cache to ensure a network fetch (cache miss).
3. Start playback of a remote artifact.
4. Monitor Logcat for `DIAGNOSTIC_OPEN`, `DIAGNOSTIC_NETWORK_OPEN`, `TTFB`, and `AUTH_CHECK` logs.
5. Identify which stage accounts for the majority of the 7-8 second delay.

### Expected Log Output Example:
- `AUTH_CHECK: elapsed=5ms, user=uid123`
- `DATASOURCE_INIT: elapsed=2ms`
- `NETWORK_OPEN_START: uri=https://...`
- `NETWORK_OPEN_END: elapsed=7200ms` <-- If this is high, it's network/server.
- `TTFB: elapsed=7350ms`
- `OUTER_OPEN_END: elapsed=7400ms`
