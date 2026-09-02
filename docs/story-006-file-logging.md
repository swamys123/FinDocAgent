# Story 006: File-based troubleshooting logging

## Status

Complete

## Completed Work

- Added a Logback file appender in `logback-spring.xml` so application logs are written to a persistent runtime file while the app is running.
- Added default file path and file name settings under `logging.file` in the Spring Boot configuration so the location is easy to override in local troubleshooting.
- Kept the change narrow and focused on troubleshooting output without altering the core runtime architecture.

## Pending Work

- Validate the live startup path with the full local stack and confirm the log file is written under the configured directory.
- Use the generated log file during issue triage and adjust the path if a deployment-specific location is required.

## Validation Performed

- The Logback XML and Spring configuration were configured for file-based DA logging.
- The app resource configuration was reviewed for the matching property values required by the Logback appender.

## Next Implementation Item

Run the app in the local workspace and verify the log file is created under the configured `./logs` location and receives runtime entries during startup.
