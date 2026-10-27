package iompar.mpts.ie

import android.app.Application
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Type
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Central place that reports API failures to Sentry. Capture happens in [Api]'s OkHttp interceptor
 * (HTTP status + network) and Gson converter (schema), so individual call sites don't change.
 *
 * Each error is tagged and fingerprinted so identical failures collapse into one Sentry issue:
 * HTTP/network group by endpoint, schema groups by the model type that failed to parse.
 */
object Telemetry {

    fun captureHttpError(request: Request, code: Int) {
        val endpoint = normalizeApiPath(request.url.encodedPath)
        Sentry.withScope { scope ->
            scope.setTag("error.kind", "http")
            scope.setTag("http.status", code.toString())
            scope.setTag("http.method", request.method)
            scope.setTag("api.endpoint", endpoint)
            scope.setTag("api.host", request.url.host)
            scope.level = if (code >= 500) SentryLevel.ERROR else SentryLevel.WARNING
            scope.fingerprint = listOf("api-http", request.url.host, endpoint, code.toString())
            Sentry.captureException(ApiHttpException(code, "${request.method} $endpoint"))
        }
    }

    fun captureNetworkError(request: Request, e: IOException) {
        // Skip non-actionable noise: pure connectivity ("user is offline") and cancelled
        // requests (OkHttp aborts in-flight calls when a polling coroutine restarts or the
        // user navigates away — it throws IOException("Canceled")).
        if (e is UnknownHostException || e is SocketTimeoutException || e is ConnectException ||
            e is InterruptedIOException || e.message == "Canceled"
        ) return
        val endpoint = normalizeApiPath(request.url.encodedPath)
        Sentry.withScope { scope ->
            scope.setTag("error.kind", "network")
            scope.setTag("api.endpoint", endpoint)
            scope.setTag("api.host", request.url.host)
            scope.level = SentryLevel.WARNING
            scope.fingerprint = listOf("api-network", request.url.host, endpoint, e.javaClass.simpleName)
            val message = "${request.method} $endpoint failed: ${e.javaClass.simpleName}" +
                (e.message?.let { ": $it" } ?: "")
            Sentry.captureException(ApiNetworkException(message, e))
        }
    }

    fun captureSchemaError(type: Type, e: Throwable) {
        val model = type.toString().substringAfterLast('.').take(120)
        Sentry.withScope { scope ->
            scope.setTag("error.kind", "schema")
            scope.setTag("api.model", model)
            scope.level = SentryLevel.ERROR
            scope.fingerprint = listOf("api-schema", model, e.javaClass.simpleName)
            val message = "Failed to parse $model: ${e.javaClass.simpleName}" +
                (e.message?.let { ": $it" } ?: "")
            Sentry.captureException(ApiSchemaException(message, e))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle + navigation context (TFI-54)
    //
    // TFI-54 asked for "standard app telemetry": app opens, screens viewed, app version. Only
    // part of that is implementable here, and the reason is worth writing down because it will
    // come up again.
    //
    // The published privacy policy (www/iompar/privacy-policy.html) states the app "contains no
    // advertising and no analytics or tracking SDKs", which is exactly why [TfiApp] confines
    // Sentry to debug builds. Product analytics — counting real users' opens and screen views —
    // therefore cannot be added without first changing that policy and the Play Data safety
    // declaration. That's an owner decision, not an implementation detail, so nothing here
    // reports anything from a release build.
    //
    // What is safe, and what this is: breadcrumbs on the debug-only Sentry client, so that when
    // an error is captured during development the issue shows which screen the user was on and
    // how they got there. It is diagnostic context attached to errors, not an event stream —
    // breadcrumbs are only uploaded alongside a captured event.
    //
    // Both functions are safe to call unconditionally: in a release build Sentry is never
    // initialised, so the static facade resolves to the SDK's no-op hub and these calls do
    // nothing at all. Call sites therefore don't need their own BuildConfig.DEBUG check.
    //
    // The "app version" half of the ticket is already satisfied for these events: `options.release`
    // in [TfiApp] carries versionName + versionCode, so every issue is attributed to a build.
    //
    // Privacy rule for anything added below, whatever the build type: screen *names* only. Never
    // stop codes, route numbers, trip ids, coordinates or times of travel — those describe where
    // a person is and where they are going, and Sentry's PII defaults do not filter data we put
    // into a breadcrumb ourselves.

    /** Marks process start, so a breadcrumb trail begins at a known point rather than mid-session. */
    fun trackAppOpen() {
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                category = "app.lifecycle"
                message = "app opened"
                level = SentryLevel.INFO
            }
        )
    }

    /**
     * Records a screen change. [name] must be a static screen identifier (see `Screen.screenName`)
     * — never the screen's arguments.
     *
     * The tag matters as much as the breadcrumb: it puts the screen the user was on at the moment
     * of the failure onto the issue itself, so errors can be filtered and grouped by screen
     * without opening each event to read its trail.
     */
    fun trackScreen(name: String) {
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                type = "navigation"
                category = "navigation"
                message = name
                level = SentryLevel.INFO
                setData("screen", name)
            }
        )
        Sentry.setTag("app.screen", name)
    }

    /**
     * Starts Sentry. Called from [TfiApp] in debug builds only. Initialised manually rather than
     * via manifest auto-init so a [io.sentry.SentryOptions.beforeSend] filter can be attached.
     * With no `sentry.dsn` in local.properties the SDK is a no-op.
     */
    fun init(app: Application) {
    SentryAndroid.init(app) { options ->
        options.dsn = BuildConfig.SENTRY_DSN
        options.environment = "debug"
        options.release = "tfi-app@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        // Errors only for now — no performance tracing (keeps quota + overhead down).
        options.tracesSampleRate = 0.0

        // Belt and braces on top of the debug-only gate above: never attach the IP
        // address or device identifiers Sentry would send by default. This is false by
        // default; stated explicitly so a future SDK upgrade flipping the default can't
        // quietly contradict the privacy policy (TFI-54).
        options.isSendDefaultPii = false

        // Drop expected "user is offline" connectivity noise; keep everything else.
        options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
            when (event.throwable) {
                is UnknownHostException, is SocketTimeoutException, is ConnectException -> null
                else -> event
            }
        }
        options.setDiagnosticLevel(SentryLevel.WARNING)
    }
    }

    fun captureException(e: Throwable) {
        Sentry.captureException(e)
    }
}
