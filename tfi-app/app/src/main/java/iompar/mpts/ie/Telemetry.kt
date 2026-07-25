package iompar.mpts.ie

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

    /** Collapse id-bearing path segments so /departures/8220DB001 groups with /departures/{id}. */
    private fun normalizePath(path: String): String =
        path.split("/").joinToString("/") { seg ->
            if (seg.isNotEmpty() && seg.any { it.isDigit() }) "{id}" else seg
        }
}

/** Reports network failures and non-2xx responses for every request through [Api]'s client. */
class SentryErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            Telemetry.captureNetworkError(request, e)
            throw e
        }
        if (!response.isSuccessful) Telemetry.captureHttpError(request, response.code)
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
