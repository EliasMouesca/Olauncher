# Repository Guidelines

This fork preserves Olauncher’s Android structure. Document intentional fork-specific behavior when it diverges from upstream.

## Living Repository Notes

Keep this file as the fork’s contributor guide. When work reveals a reusable command, architecture detail, test requirement, release or translation caveat, or project-specific pitfall, update the relevant section in the same change. Record verified, durable guidance and remove stale notes.

## Project Structure & Module Organization

This repository contains one Android application module, `app/`. Kotlin production code is under `app/src/main/java/com/elicapo/launcher/`, organized into feature/UI, data, listener, and helper packages. Android manifests, layouts, drawables, XML configuration, fonts, and localized strings live in `app/src/main/`. Custom fonts belong in `app/src/main/res/font/`, are wired through the shared `mainFontFamily` theme attribute, and must retain their accompanying third-party license. JVM unit tests are under `app/src/test/`; place device or framework-dependent tests under `app/src/androidTest/`. Release metadata and screenshots are maintained in `fastlane/metadata/`. Root Gradle files and `gradle/libs.versions.toml` define shared build configuration and dependency versions.

Widgets are hosted by `HomeFragment` through `LauncherAppWidgetHost` using the stable host ID in `Constants`. Widget instances are persisted as version-tolerant JSON in `Prefs`; `WidgetCanvasView` owns the invisible four-column placement grid, while `EditableAppWidgetHostView` detects long-press editing at the host parent and forwards only active move/resize gestures to the canvas, leaving normal provider touch handling intact. Keep the external `PinItemActivity` flow and the home-host flow in sync when changing widget persistence or placement behavior.

Double-tap app actions follow the swipe-app persistence pattern in `Prefs`, including activity, user profile, and pinned-shortcut data. Selecting an app enables it and disables double-tap locking; long-pressing the configured app toggles it off so the existing lock action can be enabled again. Keep this mode precedence synchronized between `SettingsFragment` and `HomeFragment`.
This fork does not surface self-promotional content in the app. Do not add review/share requests, external-app recommendations, donation or affiliate prompts, social-follow links, scheduled announcements, or optional onboarding tips. Keep functional settings, permission explanations, and error feedback; update both orientation layouts and every localized `strings.xml` when user-visible content changes.

## Build, Test, and Development Commands

Use the Gradle wrapper from the repository root:

- `./gradlew :app:assembleDebug` builds the debuggable APK.
- `./gradlew :app:installDebug` builds and installs the debug app on a connected device or emulator.
- `./gradlew test` runs JVM unit tests.
- `./gradlew connectedAndroidTest` runs instrumented tests on a connected Android device or emulator.
- `./gradlew lint` runs Android lint checks.

Use Java 17, matching the module’s compatibility. Do not commit generated `build/` or IDE files.

## Coding Style & Naming Conventions

Follow standard Kotlin and Android Studio formatting: four-space indentation, trailing commas where formatter-compatible, `PascalCase` for classes and composables, `camelCase` for methods/properties, and `UPPER_SNAKE_CASE` for constants. Keep package names lowercase and place new code in the narrowest appropriate `com.elicapo.launcher` subpackage. Use `snake_case` resource names and `string` resources for user-visible text. Keep translations synchronized when changing localized strings.

## Testing Guidelines

Unit tests use JUnit 4 and mirror production packages, for example `app/src/test/java/com/elicapo/launcher/data/ShortcutIdentityTest.kt`. Name test classes with a `Test` suffix and test behavior, including edge cases. Add or update tests for data and utility changes; run `./gradlew test` before submitting. Verify UI, lifecycle, or device-specific behavior with `androidTest` on a representative emulator or device.

For widget changes, validate provider selection, binding permission, configuration cancellation, resize/move/remove, launcher recreation, provider uninstallation, and profile behavior on at least one API 24+ device. Widgets requiring binding permission must be tested with the launcher set as the default home app.

## Commit & Pull Request Guidelines

Recent commits use short, action-oriented subjects such as `Improve e-ink detection...`, `Apply pending...`, and `App version bumped...`. Keep commits focused and describe the user-visible or maintenance change in the subject. Pull requests should explain the change, link related issues, list validation commands and devices tested, and include screenshots or recordings for UI changes. Call out translation, manifest, permission, or release-impacting changes explicitly.
