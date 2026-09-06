# Repository Guidelines

This fork preserves Olauncher’s Android structure. Document intentional fork-specific behavior when it diverges from upstream.

## Living Repository Notes

Keep this file as the fork’s contributor guide. When work reveals a reusable command, architecture detail, test requirement, release or translation caveat, or project-specific pitfall, update the relevant section in the same change. Record verified, durable guidance and remove stale notes.

## Project Structure & Module Organization

This repository contains one Android application module, `app/`. Kotlin production code is under `app/src/main/java/com/elicapo/launcher/`, organized into feature/UI, data, listener, and helper packages. Android manifests, layouts, drawables, XML configuration, fonts, and localized strings live in `app/src/main/`. Custom fonts belong in `app/src/main/res/font/`, are wired through the shared `mainFontFamily` theme attribute, and must retain their accompanying third-party license. JVM unit tests are under `app/src/test/`; place device or framework-dependent tests under `app/src/androidTest/`. Release metadata and screenshots are maintained in `fastlane/metadata/`. Root Gradle files and `gradle/libs.versions.toml` define shared build configuration and dependency versions.

Widgets are hosted by `HomeFragment` through `LauncherAppWidgetHost` using the stable host ID in `Constants`. Widget instances are persisted as version-tolerant JSON in `Prefs`; `WidgetCanvasView` owns the invisible four-column placement grid, while `EditableAppWidgetHostView` detects long-press editing at the host parent and forwards only active move/resize gestures to the canvas, leaving normal provider touch handling intact. Because the launcher uses an AppCompat activity, create the widget host and its `AppWidgetHostView` instances with an application/non-AppCompat context; otherwise `RemoteViews` can inflate standard provider views as AppCompat classes and reject valid widget actions. Keep the external `PinItemActivity` flow and the home-host flow in sync when changing widget persistence or placement behavior. Provider configuration must be launched through `LauncherAppWidgetHost.startAppWidgetConfigureActivityForResult`; some providers declare their configuration activity non-exported, so a direct cross-package activity launch fails with a `SecurityException`. Because that host API starts through the activity, forward its result from `MainActivity` to `HomeFragment`.

Double-tap app actions follow the swipe-app persistence pattern in `Prefs`, including activity, user profile, and pinned-shortcut data. Selecting an app enables it and disables double-tap locking; long-pressing the configured app toggles it off so the existing lock action can be enabled again. Keep this mode precedence synchronized between `SettingsFragment` and `HomeFragment`.
The app drawer is a persistent Activity-level overlay hosted by `DrawerHostLayout`, not a Navigation destination. `AppDrawerController` owns the reusable search/list/action behavior and is configured with an `AppDrawerRequest` for normal launch, hidden apps, or app-selection modes. The host tracks upward Home gestures with raw touch events, moves the drawer with the finger, settles it by distance/velocity, and leaves the `RecyclerView` in charge of normal scrolling once open. It fades the underlying NavHost during the transition so the translucent drawer background does not duplicate Home labels. Keep settle behavior immediate when system animations are disabled or on e-ink displays, and do not reintroduce the old staggered row animation.
Because this overlay is reused across openings, stop any pending app-list fling and reset the `RecyclerView` to its first item before each opening; the opening gesture's velocity belongs only to the drawer settle decision.
`MainActivity.onResume` starts an asynchronous app-list refresh before the drawer can be opened. `MainViewModel` keeps the last normal app list in memory and in `Prefs`, keyed by whether hidden apps are included, so the persistent drawer can render the previous list immediately while the refresh runs. Use `forceRefresh` after package, shortcut, hide, delete, or rename changes.
`DrawerHostLayout` only intercepts a clearly vertical upward gesture while Home is the current Navigation destination; taps, long-presses, horizontal/downward swipes, widgets, and Settings scrolling must remain delegated to their existing views. The drawer closes from Back, lifecycle transitions, app selection policies, or downward overscroll at the top of its list. Initialize the drawer through the Activity-level `ViewStub` after the first Home frame so the first interactive swipe does not pay the inflation cost.
The swipe touch listeners use `GestureDetector`'s native long-press threshold. Do not add a second delayed callback after `onLongPress`, because that makes launcher actions feel about twice as slow and can leave unnecessary coroutine work behind.
This fork does not surface self-promotional content in the app. Do not add review/share requests, external-app recommendations, donation or affiliate prompts, social-follow links, scheduled announcements, or optional onboarding tips. Keep functional settings, permission explanations, and error feedback; update both orientation layouts and every localized `strings.xml` when user-visible content changes.

## Build, Test, and Development Commands

Use the Gradle wrapper from the repository root:

- `./gradlew :app:assembleDebug` builds the debuggable APK.
- `./gradlew :app:installDebug` builds and installs the debug app on a connected device or emulator.
- `./gradlew test` runs JVM unit tests.
- `./gradlew connectedAndroidTest` runs instrumented tests on a connected Android device or emulator.
- `./gradlew lint` runs Android lint checks.

Use Java 17, matching the module’s compatibility. The current Gradle/AGP combination cannot run its test tasks under Java 24; set `JAVA_HOME` to a Java 17 installation before running Gradle. Do not commit generated `build/` or IDE files.

## Coding Style & Naming Conventions

Follow standard Kotlin and Android Studio formatting: four-space indentation, trailing commas where formatter-compatible, `PascalCase` for classes and composables, `camelCase` for methods/properties, and `UPPER_SNAKE_CASE` for constants. Keep package names lowercase and place new code in the narrowest appropriate `com.elicapo.launcher` subpackage. Use `snake_case` resource names and `string` resources for user-visible text. Keep translations synchronized when changing localized strings.

## Testing Guidelines

Unit tests use JUnit 4 and mirror production packages, for example `app/src/test/java/com/elicapo/launcher/data/ShortcutIdentityTest.kt`. Name test classes with a `Test` suffix and test behavior, including edge cases. Add or update tests for data and utility changes; run `./gradlew test` before submitting. Verify UI, lifecycle, or device-specific behavior with `androidTest` on a representative emulator or device.

For widget changes, validate provider selection, binding permission, configuration cancellation, resize/move/remove, launcher recreation, provider uninstallation, and profile behavior on at least one API 24+ device. Widgets requiring binding permission must be tested with the launcher set as the default home app.

## Commit & Pull Request Guidelines

Recent commits use short, action-oriented subjects such as `Improve e-ink detection...`, `Apply pending...`, and `App version bumped...`. Keep commits focused and describe the user-visible or maintenance change in the subject. Pull requests should explain the change, link related issues, list validation commands and devices tested, and include screenshots or recordings for UI changes. Call out translation, manifest, permission, or release-impacting changes explicitly.
