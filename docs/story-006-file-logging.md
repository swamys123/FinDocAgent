# Story 006: File-based troubleshooting logging

## Status

Complete

## Completed Work

- Replaced the fixed `FileAppender` with a daily `RollingFileAppender` in `logback-spring.xml`.
- Configured the daily rollover pattern as `${LOG_PATH}/${LOG_FILE}.%d{yyyy-MM-dd}.log` so each new date creates a separate archived log file.
- Kept the active file as `${LOG_PATH}/${LOG_FILE}` while preserving previous day logs without deleting them.
- Set `cleanHistoryOnStart` to `false` so no historical log files are removed during startup.
- Verified the project still compiles successfully with the updated Logback configuration.

## Pending Work

- Start the application under the full local runtime stack and confirm dated archived logs are created at the next daily boundary.
- Optionally add retention controls later if deployment policy requires trimming older archive files.

## Validation Performed

- Confirmed the current appender was a plain file appender and that daily rollover was missing.
- Updated the XML to a `TimeBasedRollingPolicy` based on the date.
- Ran `./gradlew compileJava --console=plain` successfully.
- Ran `./gradlew test --console=plain` successfully.

## Next Implementation Item

Run the app on a fresh day boundary and confirm the log file is renamed to the dated archive pattern without deleting old logs.
