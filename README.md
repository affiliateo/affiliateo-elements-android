# Affiliateo Elements (Kotlin / Android)

Embed [Affiliateo Elements](https://affiliateo.com/docs/elements) in a native Android app. The element renders in a `WebView`; there is no native-UI rewrite, because withdraw and identity are money and camera surfaces safest run once, on Affiliateo's side, and reused everywhere.

## Add the module

This ships as a source module. Clone the release mirror (or add it as a git
submodule) next to your project, then include it in your Gradle build:

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

(A Maven Central release can follow once the API settles.) Development
happens in this directory of the main repo; each release is copied to the
mirror and tagged.

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

## The recommended setup

Nine components is a lot of freedom, and freedom is not a layout. This is the
one we ship in our own apps. Start here.

```
Tab 1   Link       qr, link, stats, products
Tab 2   Balance    balance, activity
Tab 3   Withdraw   withdraw
```

Three tabs in Material's own **`NavigationBar`**, not a hand-built one, so you
get the platform's hit-testing, ripple and TalkBack support for free.

```kotlin
Scaffold(
    bottomBar = {
        NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Icon(t.icon, null) },
                    label = { Text(t.label) },
                )
            }
        }
    }
) { padding -> ElementColumn(tab, Modifier.padding(padding)) }
```

Each tab is one scrolling column with its elements stacked, nothing between
them, and **your** background behind. The view keeps its WebView transparent on
purpose, so the element sits on your screen rather than on a white card, which
means the background has to come from you.

**Do not give `identity` a tab of its own next to `withdraw`.** Withdraw
already walks an unverified affiliate through the ID check exactly where they
need it, and skips it forever once they pass. A separate tab shows them the
same step twice. Use `identity` alone only when you are not showing withdraw
at all.

## Sizing and theme

A full-screen element needs neither. Stacking several in a `ScrollView` does,
and both are one line.

```kotlin
// The element measures its own content and reports every change, in dp, so a
// stacked element never needs a guessed height.
element.onContentHeight = { dp ->
    element.layoutParams = element.layoutParams.also {
        it.height = (dp * resources.displayMetrics.density).toInt()
    }
}
```

Theme with the `appearance` tokens you pass when you **mint the session**:
nothing about your design is visible from inside an element, so one you never
theme renders in Affiliateo's own colours. `contentPadding` is worth setting
too, since elements carry no outer margin (on the web that comes from your own
padded layout) and a WebView is edge to edge. The token list is at
https://affiliateo.com/docs/elements.

Set the same tokens on `appearance` here and a live element restyles in place,
which is what a dark-mode switch needs: reloading would throw away whatever
the person was in the middle of. Keep both. The minted copy handles first
paint, so an element never flashes our defaults before your brand lands, and
this copy is re-applied after every reload.

```kotlin
element.appearance = mapOf("colorBackground" to "#0B0B0D", "colorText" to "#EEEEEE")

// Drive the date window from your own controls (affiliate, activity).
element.updateRange(AffiliateoDateRange("2026-07-01", "2026-07-31"))
element.updateRange(null)   // all-time
```

## Hiding your own placeholder

`onReady` fires when the element has rendered its first frame, with your
appearance and range already applied. Do not use `onPageFinished` for this: it
fires when the document loads, which is before the element has fetched and
laid out, so a spinner removed there uncovers an empty view.

```kotlin
element.onReady = { spinner.isVisible = false }
```

It fires again after any reload (a finished withdrawal, an hourly session
refresh), which is when you want the placeholder back anyway.

## Sessions

Sessions last an hour, and `load()` takes a secret you already minted, so
this view has nothing to call when one lapses. **Set `onSessionExpiring` or a
screen left open for an hour goes dead**, quietly. With it set, the view
re-mints about two minutes before each expiry, catches up when the app
returns to the foreground, and retries on a short backoff if your backend is
briefly unavailable.

```kotlin
element.onSessionExpiring = { deliver ->
    lifecycleScope.launch { deliver(api.mintAffiliateoSecret()) }
}
element.load(AffiliateoComponent.BALANCE, firstSecret)
```

`deliver` is safe to call from any thread, and a late or duplicate delivery
(the person navigated away, or a slow call landed after a retry) is ignored
rather than swapping the URL under their finger.

## When the sign-in lapses

One confirm covers every gated element and keeps covering it: confirming signs
the person in on Affiliateo's side, so the others on screen recognise that
session with no second email, and so does the next launch. The step-up itself
lasts an hour and lives only in the element's memory; the sign-in behind it
outlives that.

When it eventually lapses the element goes back to asking, and says so. The
view forgets its own elevation on it automatically. Set `onLocked` only if
YOUR app remembers the signed-in state somewhere:

```kotlin
element.onLocked = {
    // Clear whatever you cached, or the rest of the screen keeps acting
    // signed in beside a login form.
    affiliateState.deviceConfirmed = false
}
```

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
- Make it feel instant: create the views for all your tabs when the
  affiliate screen opens (not on tab tap) and keep them attached across
  switches, so every tab is already rendered when it appears. Gated elements
  (withdraw, identity) are safe to pre-create too: one asks for a
  sign-in code only once it has actually been on screen, so an off-screen
  warm-up stays silent.

> Status: initial release, not yet verified on a physical device. File issues at
> https://github.com/affiliateo/affiliateo-elements-android/issues, or email
> support@affiliateo.com.
