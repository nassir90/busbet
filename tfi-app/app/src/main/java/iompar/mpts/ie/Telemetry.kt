package iompar.mpts.ie

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Type
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Synthetic exception so non-2xx responses get a type + stack for Sentry grouping. */
class ApiHttpException(val statusCode: Int, endpoint: String) :
    RuntimeException("HTTP $statusCode on $endpoint")

/**
 * Wraps a raw network [IOException] with a readable message so the Sentry issue title says what
 * call failed (e.g. "GET /departures/{id} failed: SocketException") instead of the bare cause text.
 * The original exception is kept as the cause, preserving its type and stack for debugging.
 */
class ApiNetworkException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Wraps a deserialization failure with a readable message naming the model that failed to parse,
 * so the Sentry issue reads "Failed to parse DeparturesResponse: ..." rather than a bare
 * JsonSyntaxException. The original exception is kept as the cause.
 */
class ApiSchemaException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Central place that reports API failures to Sentry. Capture happens in [Api]'s OkHttp interceptor
 * (HTTP status + network) and Gson converter (schema), so individual call sites don't change.
 *
 * Each error is tagged and fingerprinted so identical failures collapse into one Sentry issue:
 * HTTP/network group by endpoint, schema groups by the model type that failed to parse.
 */
object Telemetry {

    fun captureHttpError(request: Request, code: Int) {
        val endpoint = normalizePath(request.url.encodedPath)
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
        val endpoint = normalizePath(request.url.encodedPath)
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

    /** Collapse id-bearing path segments so /departures/8220DB001 groups with /departures/{id}. */
    private fun normalizePath(path: String): String =
        path.split("/").joinToString("/") { seg ->
            if (seg.isNotEmpty() && seg.any { it.isDigit() }) "{id}" else seg
        }
}

/**
 * Request header listing status codes that are a normal outcome for a call, so they aren't
 * reported as errors. Comma-separated. Stripped before the request leaves the device.
 *
 * Some endpoints answer a perfectly ordinary question with a 404: this trip has no live vehicle
 * right now, this trip has no shape in the feed, this deployment predates the batch endpoint.
 * Every one of those is caught and handled at the call site, but the interceptor reported them
 * all as errors anyway — which is what filled Sentry with ANDROID-F and ANDROID-K.
 * [Telemetry.captureNetworkError] already filters expected connectivity noise this way; this is
 * the same idea for the HTTP path.
 */
const val EXPECTED_STATUS_HEADER = "X-Tfi-Expected-Status"

/** Reports network failures and non-2xx responses for every request through [Api]'s client. */
class SentryErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val expected = original.header(EXPECTED_STATUS_HEADER)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()

        // Purely a client-side marker; the server has no use for it.
        val request = if (expected.isEmpty()) original
        else original.newBuilder().removeHeader(EXPECTED_STATUS_HEADER).build()

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            Telemetry.captureNetworkError(request, e)
            throw e
        }
        if (!response.isSuccessful && response.code !in expected) {
            Telemetry.captureHttpError(request, response.code)
        }
        return response
    }
}

/** Wraps a converter factory so response-body deserialization failures are reported as schema errors. */
class SentryConverterFactory(private val delegate: Converter.Factory) : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        val inner = delegate.responseBodyConverter(type, annotations, retrofit) ?: return null
        return Converter<ResponseBody, Any?> { body ->
            try {
                inner.convert(body)
            } catch (e: Throwable) {
                Telemetry.captureSchemaError(type, e)
                throw e
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? =
        delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)

    override fun stringConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, String>? = delegate.stringConverter(type, annotations, retrofit)
}
