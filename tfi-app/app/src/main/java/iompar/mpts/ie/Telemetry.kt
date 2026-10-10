package iompar.mpts.ie

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.io.IOException
import java.lang.reflect.Type

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

/** Collapse id-bearing path segments so /departures/8220DB001 groups with /departures/{id}. */
internal fun normalizeApiPath(path: String): String =
    path.split("/").joinToString("/") { seg ->
        if (seg.isNotEmpty() && seg.any { it.isDigit() }) "{id}" else seg
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
