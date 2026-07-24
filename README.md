# Affiliateo Elements (Kotlin / Android)

Embed [Affiliateo Elements](https://affiliateo.com/docs/elements) in a native Android app. The element renders in a `WebView`; there is no native-UI rewrite, because balance, withdraw, and identity are money and camera surfaces safest run once, on Affiliateo's side, and reused everywhere.

## Add the module

This ships as a source module. Clone this repo (or add it as a git submodule)
next to your project, then include it in your Gradle build:

```bash
git clone https://github.com/affiliateo/affiliateo-elements-android.git
```

```kotlin
// settings.gradle.kts
include(":affiliateo-elements")
project(":affiliateo-elements").projectDir = file("path/to/affiliateo-elements-android")
```

```kotlin
// app/build.gradle.kts
dependencies { implementation(project(":affiliateo-elements")) }
```

(A Maven Central release can follow once the API settles.)

This repo is the release mirror of `packages/elements-android` in the main
[affiliateo/affiliateo](https://github.com/affiliateo/affiliateo) repo, which
is where development happens; issues and pull requests belong there.

## Use it

Mint the session on your **backend** with your `afk_` secret key and
`"platform": "native"` (omit `allowed_origins` — nothing frames a WebView),
return the `client_secret` to the app, then:

```kotlin
val element = AffiliateoElementView(context).apply {
    onComplete = { status ->
        // "complete" when a withdrawal or verification finished
    }
}
setContentView(element)

lifecycleScope.launch {
    val secret = api.affiliateoClientSecret()   // calls your backend
    element.load(AffiliateoComponent.BALANCE, secret)
}
```

`AffiliateoElementView` is a `FrameLayout`, so you can also drop it straight
into XML and call `load(...)` from code.

## Permissions

`INTERNET` is declared by the module. The `identity` element also needs the
camera: keep `CAMERA` in your manifest and request the **runtime** permission
before showing that element. The view grants the WebView's own camera request
only once your app holds the OS permission, only for the camera (never the
microphone), and only to pages on your configured Affiliateo origin — every
other permission request is denied.

## Notes

- Mint with `"platform": "native"`. A `web` session expects framing origins and
  will not behave correctly top-level in a WebView.
- Gated elements open a confirmation window (on affiliateo.com) via
  `window.open`; the view hosts it in a layered WebView. When the window closes
  after a finished withdrawal/verification the element reloads to reflect it;
  when a plain confirm window closes the element is left alone — it receives
  the confirmation over its own channel and updates in place. Links inside that
  window (terms, privacy) open in the system browser. Nothing sensitive passes
  through your app.
- Sessions last an hour; call `load(...)` again with a freshly minted secret to
  refresh.
- The view destroys its WebViews when detached from the window, so it is
  single-use: create a fresh instance if the screen is shown again.

> Status: initial release, not yet verified on a physical device. File issues at
> https://github.com/affiliateo/affiliateo/issues.
