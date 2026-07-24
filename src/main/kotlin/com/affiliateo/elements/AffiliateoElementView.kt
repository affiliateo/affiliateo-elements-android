package com.affiliateo.elements

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Message
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
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

    private val webView = WebView(context)
    private var popup: WebView? = null

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
        // The gated elements announce a typed sign-in confirm on this bridge
        // so their siblings can unlock without their own email code. Main
        // WebView only: the popup never hosts an element page, so it has no
        // business holding the bridge.
        webView.addJavascriptInterface(ElevationBridge(), "AffiliateoAndroid")
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        synchronized(registry) { registry.add(this) }
    }

    /** Load (or reload) an element for a freshly minted client secret. */
    fun load(component: AffiliateoComponent, clientSecret: String) {
        val base = origin.trimEnd('/')
        val secret = Uri.encode(clientSecret)
        webView.loadUrl("$base/embed/${component.slug}/$secret")
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
     * The `window.AffiliateoAndroid` object the element pages see. The
     * callback carries no data worth trusting, and acting on it can only
     * reload our own element views, so a hostile page calling it gains
     * nothing beyond a redundant refresh.
     */
    private inner class ElevationBridge {
        @JavascriptInterface
        fun elevated(@Suppress("UNUSED_PARAMETER") component: String?) {
            // JavascriptInterface methods arrive on a WebView worker thread.
            post { notifyElevated() }
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

        // window.open from the balance/withdraw/identity elements. Host the
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
    }
}
