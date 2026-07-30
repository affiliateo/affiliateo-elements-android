package com.affiliateo.elements

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Message
import android.util.AttributeSet
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * Renders one Affiliateo element in a top-level [WebView].
 *
 * This is the native counterpart to the web SDK. Instead of an iframe the
 * element loads top-level, which a native WebView actually makes simpler: the
 * document is first-party, so there is no third-party-cookie partitioning and
 * no nested-frame camera restriction.
 *
 * Usage:
 *  1. On your backend, mint a session with `"platform": "native"` (omit
 *     `allowed_origins`) and return the `client_secret`.
 *  2. Call [load] with the component and that secret. The view builds
 *     `<origin>/embed/<component>/<secret>` and loads it.
 *
 * For the IDENTITY element, declare CAMERA in your manifest and request the
 * runtime permission before showing the view; [onPermissionRequest] grants the
 * WebView once the app itself holds it — camera only, and only for [origin].
 *
 * The view is single-use: it destroys its WebViews when detached from the
 * window, so create a fresh instance if the screen is shown again.
 */
class AffiliateoElementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    /** Your Affiliateo origin. Override for self-hosted or staging. */
    var origin: String = "https://affiliateo.com"

    /**
     * Fired when a money/identity flow finishes, carrying `affiliateo_status`
     * (e.g. "complete") when present.
     */
    var onComplete: ((String?) -> Unit)? = null

    /**
     * The element's own measurement of its content, every time it changes.
     *
     * A WebView no more grows to fit its content than an iframe does, so an
     * element stacked with others needs someone to size it. Set this and give
     * the view a matching height instead of guessing one that clips the first
     * time a product row or an error line appears.
     *
     * The value is in CSS pixels, which is dp, so scale it before it reaches
     * layout params:
     *
     * ```kotlin
     * element.onContentHeight = { dp ->
     *     layoutParams = layoutParams.also {
     *         it.height = (dp * resources.displayMetrics.density).toInt()
     *     }
     * }
     * ```
     *
     * Always called on the main thread.
     */
    var onContentHeight: ((Int) -> Unit)? = null

    /**
     * A gated element is asking to sign in again, because the session behind it
     * lapsed.
     *
     * This view already forgets its own elevation on it, so you only need this
     * if YOUR app remembers somewhere that the person is signed in. Clear that
     * here. Ours did not, and the rest of the screen went on acting signed in
     * underneath a login form. Always called on the main thread.
     */
    var onLocked: (() -> Unit)? = null

    /**
     * The element has rendered its first frame. Hide your own placeholder
     * here.
     *
     * Deliberately not [WebViewClient.onPageFinished]: that fires when the
     * document has loaded, which is before the element has fetched its data
     * and laid out, so a spinner removed there uncovers an empty view. This
     * fires after layout, and again after every reload (a finished
     * withdrawal, an hourly session refresh), which is when you want the
     * placeholder back anyway.
     *
     * Called on the main thread, after any [appearance] and range you set
     * have been re-applied, so what you uncover is already in your own brand.
     */
    var onReady: (() -> Unit)? = null

    /**
     * Theme tokens, applied to the live element without reloading it.
     *
     * Set this whenever your theme changes (a dark-mode switch) and the
     * element restyles in place rather than tearing down and losing whatever
     * the person was in the middle of. The appearance you bake into the
     * session at mint time still handles FIRST paint, which is what keeps an
     * element from flashing our defaults before your brand lands. Set both.
     *
     * Re-applied automatically after every reload, so it survives a finished
     * money flow and the hourly session refresh.
     *
     * The current token list is at https://affiliateo.com/docs/elements.
     * Unknown keys are ignored server-side, so a new token never needs an SDK
     * update.
     */
    var appearance: Map<String, String>? = null
        set(value) {
            if (field == value) return
            field = value
            pushAppearance()
        }

    /**
     * Called shortly before the current session expires, so the element can
     * swap to a fresh one instead of dying at the hour mark.
     *
     * Mint a NEW secret on your backend and hand it back through `deliver`.
     * Called on the main thread; `deliver` may be called on any thread and is
     * safe to call from a coroutine that finished after the user navigated
     * away (a late or duplicate delivery is ignored).
     *
     * ```kotlin
     * element.onSessionExpiring = { deliver ->
     *     lifecycleScope.launch { deliver(api.mintAffiliateoSecret()) }
     * }
     * ```
     *
     * Leave it null and the element keeps its single session, which is the
     * old behaviour: correct for a screen that is never open for an hour, and
     * a silent death for one that is.
     */
    var onSessionExpiring: ((deliver: (String) -> Unit) -> Unit)? = null

    /**
     * Whether [updateRange] has ever been called. Distinguishes "not driving
     * the range" (the element's own filter is in charge) from "all-time",
     * which is a real instruction and must survive a reload.
     */
    var isDrivingRange = false
        private set

    private var range: AffiliateoDateRange? = null

    private val webView = WebView(context)
    private var popup: WebView? = null
    private var lastContentHeight = 0

    /** What [load] was last asked for, so a refresh can rebuild the same URL. */
    private var currentComponent: AffiliateoComponent? = null

    /** When the current session needs replacing, in epoch millis. */
    private var refreshAtMs = 0L
    private var refreshRunnable: Runnable? = null

    /**
     * Bumped on every mint request. A secret delivered against a superseded
     * generation is dropped, which is what makes a slow backend call that
     * lands after the next attempt harmless rather than a URL swap under the
     * person's finger.
     */
    private var fetchGeneration = 0

    /**
     * Whether this element's own page confirmed the sign-in code. A page
     * that already did never needs the courtesy reload in [notifyElevated].
     */
    private var didElevate = false

    /**
     * Whether the current popup ever reached a completion URL (`/embed/done`
     * or an `affiliateo_status` redirect). Decides what closing it means: a
     * finished withdraw/identity flow reloads the element so the new state
     * shows; a step-up confirm window closing must NOT reload, because the
     * element keeps its step-up elevation in page state and receives the token
     * over its own same-origin channel — reloading would wipe the elevation
     * and loop the person back to the confirm gate forever.
     */
    private var popupSawCompletion = false

    init {
        configure(webView)
        webView.webViewClient = ElementClient()
        webView.webChromeClient = ElementChrome()
        // The element pages' outbound bridge: a typed sign-in confirm (so
        // siblings can unlock without their own email code), a silent
        // recognition, and the element's own height. Main WebView only: the
        // popup never hosts an element page, so it has no business holding
        // the bridge.
        webView.addJavascriptInterface(ElementBridge(), "AffiliateoAndroid")
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        synchronized(registry) { registry.add(this) }
    }

    /** Load (or reload) an element for a freshly minted client secret. */
    fun load(component: AffiliateoComponent, clientSecret: String) {
        currentComponent = component
        // A caller handing over a secret themselves resets the retry ladder:
        // whatever went wrong before, this is a fresh start.
        fetchGeneration++
        applySecret(clientSecret)
    }

    /**
     * Set the date window the filterable elements show, driven from your own
     * controls instead of the element's built-in filter.
     *
     * Pass null for all-time. Nothing is unlocked by this: the element
     * re-fetches its OWN data for the window over the session it already
     * holds. Re-applied after every reload, like [appearance].
     */
    fun updateRange(range: AffiliateoDateRange?) {
        this.range = range
        isDrivingRange = true
        pushRange()
    }

    /** Point the web view at a session and arm the next refresh. */
    private fun applySecret(clientSecret: String) {
        val component = currentComponent ?: return
        val base = origin.trimEnd('/')
        webView.loadUrl("$base/embed/${component.slug}/${Uri.encode(clientSecret)}")

        val expiry = tokenExpiryMs(clientSecret)
        refreshAtMs = expiry - REFRESH_LEAD_MS
        // Never sooner than the floor: a backend that hands back an already
        // stale secret must not turn refresh into a tight loop.
        scheduleRefresh((refreshAtMs - System.currentTimeMillis()).coerceAtLeast(MIN_REFRESH_DELAY_MS))
    }

    private fun scheduleRefresh(delayMs: Long) {
        cancelRefresh()
        // Nothing to refresh WITH. Leaving the timer unarmed is the old
        // single-session behaviour rather than a wake-up that can do nothing.
        if (onSessionExpiring == null) return
        val runnable = Runnable {
            refreshRunnable = null
            requestFreshSecret(attempt = 0)
        }
        refreshRunnable = runnable
        postDelayed(runnable, delayMs)
    }

    private fun cancelRefresh() {
        refreshRunnable?.let { removeCallbacks(it) }
        refreshRunnable = null
    }

    /**
     * Ask the host for a new secret and swap to it.
     *
     * A host whose mint call fails (or never answers) would otherwise leave
     * the element to die quietly at the hour mark, so a watchdog retries on a
     * short ladder and then stops, leaving the element showing its own
     * expired state. That beats tearing it down under the person's finger.
     */
    private fun requestFreshSecret(attempt: Int) {
        val fetch = onSessionExpiring ?: return
        val generation = ++fetchGeneration

        fetch { secret ->
            post {
                // Superseded by a later attempt or by an explicit load(), or
                // the view is gone. Either way this secret is not wanted.
                if (generation != fetchGeneration || !isAttachedToWindow) return@post
                if (secret.isEmpty()) return@post
                applySecret(secret)
            }
        }

        if (attempt < RETRY_DELAYS_MS.size) {
            cancelRefresh()
            val runnable = Runnable {
                refreshRunnable = null
                // Only if nothing landed in the meantime; a delivery bumps
                // the generation past the one this attempt was issued under.
                if (generation == fetchGeneration) requestFreshSecret(attempt + 1)
            }
            refreshRunnable = runnable
            postDelayed(runnable, RETRY_DELAYS_MS[attempt])
        }
    }

    /**
     * When this session expires, in epoch millis, read from the secret itself.
     *
     * The secret is a JWT whose middle segment carries `exp` in seconds, so
     * the refresh clock needs nothing from the integrator beyond the secret
     * they already return. Any surprise in the shape falls back to the known
     * one-hour TTL, which is correct for a secret that was just minted.
     */
    private fun tokenExpiryMs(clientSecret: String): Long {
        try {
            val parts = clientSecret.split(".")
            if (parts.size < 2) return System.currentTimeMillis() + FALLBACK_TTL_MS
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val exp = JSONObject(String(decoded, Charsets.UTF_8)).optLong("exp", 0L)
            if (exp > 0L) return exp * 1000L
        } catch (_: Exception) {
            // Fall through to the fallback TTL.
        }
        return System.currentTimeMillis() + FALLBACK_TTL_MS
    }

    /**
     * The app came back to the foreground. [postDelayed] does not fire while
     * the device is dozing, so a phone left in a pocket past the refresh point
     * returns with a dead session and a callback that may be long overdue.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) return
        if (onSessionExpiring == null || refreshAtMs == 0L) return
        if (System.currentTimeMillis() < refreshAtMs) return
        cancelRefresh()
        requestFreshSecret(attempt = 0)
    }

    private fun pushAppearance() {
        val tokens = appearance ?: return
        if (tokens.isEmpty()) return
        evaluate("window.__affiliateoAppearance", JSONObject(tokens).toString())
    }

    private fun pushRange() {
        // Never set, so the element's own filter stays in charge. Distinct
        // from a set-to-null range, which means all-time and must be sent.
        if (!isDrivingRange) return
        val json = range?.let { JSONObject().put("from", it.from).put("to", it.to).toString() } ?: "null"
        evaluate("window.__affiliateoRange", json)
    }

    /**
     * Call a named entry point in the element with one JSON string argument.
     *
     * The page takes JSON strings rather than objects on both channels, so
     * the value crosses as one opaque argument and the page does its own
     * parsing and validation. [JSONObject.quote] is what stops an argument
     * ending the injected statement early; it escapes the line separators
     * that are legal in JSON and not in JavaScript, which a plain quote-and-
     * backslash pass would leave raw.
     */
    private fun evaluate(entryPoint: String, argument: String) {
        val literal = JSONObject.quote(argument)
        webView.evaluateJavascript("$entryPoint && $entryPoint($literal);", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // The identity element captures live media without a preceding tap.
        web.settings.mediaPlaybackRequiresUserGesture = false
        // The gated elements open a confirm window via window.open.
        web.settings.setSupportMultipleWindows(true)
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.setBackgroundColor(0) // transparent, like the web element's default
    }

    // WebViews hold native resources (renderer, GL surfaces) that garbage
    // collection never reclaims promptly. Detach ends this view's life in
    // every real embedding, so tear both down here rather than leaking them.
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        synchronized(registry) { registry.remove(this) }
        // A pending refresh would wake up against a destroyed WebView.
        cancelRefresh()
        // Also invalidates any secret still in flight from the host.
        fetchGeneration++
        popup?.let {
            removeView(it)
            it.destroy()
        }
        popup = null
        removeView(webView)
        webView.destroy()
    }

    /**
     * This element's page confirmed the emailed sign-in code. The confirm
     * left a session behind on the Affiliateo side, so every OTHER gated
     * element on screen is one reload away from being recognised silently
     * instead of asking for its own code. Reload them.
     *
     * Loop-safe by construction: the page only signals a TYPED confirm,
     * never a silent recognition, so a reload here can never echo back.
     * Runs on the main thread (the bridge posts over).
     */
    private fun notifyElevated() {
        didElevate = true
        val peers = synchronized(registry) { registry.toList() }
        for (other in peers) {
            if (other !== this && !other.didElevate && other.origin == origin) {
                other.webView.reload()
            }
        }
    }

    /**
     * This element was recognised from an existing session rather than a typed
     * code. Nothing to broadcast (announcing it is what would loop siblings
     * into reloading each other), but it is open, so record that and spare it
     * a pointless reload when a sibling confirms.
     */
    private fun notifyRecognised() {
        didElevate = true
    }

    /**
     * The gate came back. Forgetting the elevation is what lets the next
     * confirm elsewhere on screen reach this view again, since a sibling's
     * confirm only reloads views that are not already elevated.
     */
    private fun notifyLocked() {
        didElevate = false
        onLocked?.invoke()
    }

    private fun notifyContentHeight(height: Int) {
        if (height <= 0 || height == lastContentHeight) return
        lastContentHeight = height
        onContentHeight?.invoke(height)
    }

    /**
     * The element finished its first layout, on this load or on a reload.
     *
     * Re-push before announcing. A reloaded document comes up on whatever was
     * baked into its session and knows nothing of what has been set since, so
     * a host that hides its placeholder here would otherwise uncover an
     * element in the wrong theme for a frame.
     */
    private fun notifyReady() {
        pushAppearance()
        pushRange()
        onReady?.invoke()
    }

    /**
     * The `window.AffiliateoAndroid` object the element pages see. The
     * callbacks carry no data worth trusting, and acting on them can only
     * reload our own element views or resize this one, so a hostile page
     * calling them gains nothing beyond a redundant refresh.
     */
    private inner class ElementBridge {
        @JavascriptInterface
        fun elevated(@Suppress("UNUSED_PARAMETER") component: String?) {
            // JavascriptInterface methods arrive on a WebView worker thread.
            post { notifyElevated() }
        }

        @JavascriptInterface
        fun recognized(@Suppress("UNUSED_PARAMETER") component: String?) {
            post { notifyRecognised() }
        }

        @JavascriptInterface
        fun locked(@Suppress("UNUSED_PARAMETER") component: String?) {
            post { notifyLocked() }
        }

        @JavascriptInterface
        fun ready(@Suppress("UNUSED_PARAMETER") component: String?) {
            post { notifyReady() }
        }

        // Double rather than Int: the bridge converts a JS number to either,
        // but a double never has to round-trip through a narrowing conversion
        // the WebView might refuse.
        @JavascriptInterface
        fun resize(@Suppress("UNUSED_PARAMETER") component: String?, height: Double) {
            val dp = height.toInt()
            post { notifyContentHeight(dp) }
        }
    }

    private fun isCompletion(url: String): Boolean =
        url.contains("/embed/done") || url.contains("affiliateo_status")

    private fun statusOf(uri: Uri): String? = uri.getQueryParameter("affiliateo_status")

    /** True when [uri] is exactly the origin this view was configured to load. */
    private fun isElementsOrigin(uri: Uri?): Boolean {
        uri ?: return false
        val expected = Uri.parse(origin)
        return uri.scheme == expected.scheme &&
            uri.host == expected.host &&
            uri.port == expected.port
    }

    /**
     * Camera, and only the camera, and only for [origin], and only once the
     * host app itself holds the runtime permission. Everything else is denied:
     * an embed session must never be able to switch on the microphone, and a
     * page that is not ours must never inherit the app's camera grant.
     */
    private fun respondToPermissionRequest(request: PermissionRequest?) {
        request ?: return
        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val appHoldsCamera = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (wantsCamera && isElementsOrigin(request.origin) && appHoldsCamera) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            request.deny()
        }
    }

    /** Open [uri] in the system browser (terms/privacy links from the popup). */
    private fun openExternally(ctx: Context, uri: Uri) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // No browser installed; nothing sensible to do.
        }
    }

    private inner class ElementClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            if (isCompletion(uri.toString())) onComplete?.invoke(statusOf(uri))
            return false // let the WebView load it
        }
    }

    private inner class ElementChrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
            respondToPermissionRequest(request)
        }

        // window.open from the withdraw/identity elements. Host the
        // confirm + hosted-portal window in a WebView layered on top, so it can
        // run its own navigation and hand back.
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val ctx = view?.context ?: return false
            val child = WebView(ctx)
            popupSawCompletion = false
            configure(child)
            child.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                    val uri = req?.url ?: return false
                    if (isCompletion(uri.toString())) {
                        popupSawCompletion = true
                        onComplete?.invoke(statusOf(uri))
                    }
                    return false
                }
            }
            child.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    respondToPermissionRequest(request)
                }

                // target=_blank links inside the confirm window (terms,
                // privacy). A third in-app layer would bury the flow, so
                // attach a throwaway WebView just to learn the URL, then hand
                // it to the system browser and refuse the window.
                override fun onCreateWindow(
                    v: WebView?,
                    dialog: Boolean,
                    userGesture: Boolean,
                    msg: Message?,
                ): Boolean {
                    val innerCtx = v?.context ?: return false
                    val transport = msg?.obj as? WebView.WebViewTransport ?: return false
                    val probe = WebView(innerCtx)
                    probe.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            pv: WebView?,
                            req: WebResourceRequest?,
                        ): Boolean {
                            req?.url?.let { openExternally(innerCtx, it) }
                            // Destroying from inside its own callback is unsafe,
                            // and post() on the never-attached probe would never
                            // run — so post on the attached element view instead.
                            this@AffiliateoElementView.post { probe.destroy() }
                            return true
                        }
                    }
                    transport.webView = probe
                    msg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    val finishedFlow = popupSawCompletion
                    popupSawCompletion = false
                    removeView(child)
                    // Called from inside the closing WebView's own machinery,
                    // so destroy it a beat later, from the attached parent.
                    this@AffiliateoElementView.post { child.destroy() }
                    popup = null
                    // Reload only after a finished money/identity flow, so the
                    // element reflects it. A step-up confirm window closing
                    // must leave the element untouched — it hears the token on
                    // its own channel and updates itself (see popupSawCompletion).
                    if (finishedFlow) webView.reload()
                }
            }
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            popup = child

            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = child
            resultMsg?.sendToTarget()
            return true
        }
    }

    private companion object {
        /**
         * Every live element view, so a sign-in confirm typed into one gated
         * element can unlock the others on the same screen. Weak keys: a view
         * that skipped detach (never attached) still leaves with its host.
         */
        val registry: MutableSet<AffiliateoElementView> =
            Collections.newSetFromMap(WeakHashMap())

        /**
         * Re-mint this long before a session expires, so the new secret is
         * live before the old one dies mid-request.
         */
        const val REFRESH_LEAD_MS = 120_000L

        /** Sessions run an hour; used when a secret's expiry cannot be read. */
        const val FALLBACK_TTL_MS = 3_600_000L

        /** Never re-mint sooner than this after getting a secret. */
        const val MIN_REFRESH_DELAY_MS = 30_000L

        /**
         * A mint that never answers is retried after these gaps, then left
         * alone: the element shows its own expired state rather than being
         * torn down under the person's finger.
         */
        val RETRY_DELAYS_MS = longArrayOf(5_000L, 25_000L)
    }
}
