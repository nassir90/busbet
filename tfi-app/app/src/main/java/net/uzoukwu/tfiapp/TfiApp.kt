package net.uzoukwu.tfiapp

import android.app.Application
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Application entry point. Initialises Sentry manually (rather than via manifest auto-init) so we
 * can attach a [beforeSend] filter. With no `sentry.dsn` in local.properties the SDK is a no-op.
 */
class TfiApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Must run before anything touches Api — widgets, workers and the on-device server all
        // reach it without MainActivity ever having run.
        BackendConfigHolder.init(this)

        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = if (BuildConfig.DEBUG) "debug" else "production"
            options.release = "tfi-app@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            // Errors only for now — no performance tracing (keeps quota + overhead down).
            options.tracesSampleRate = 0.0

            // Drop expected "user is offline" connectivity noise; keep everything else.
            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                when (event.throwable) {
                    is UnknownHostException, is SocketTimeoutException, is ConnectException -> null
                    else -> event
                }
            }
            options.setTag("app.flavor", if (BuildConfig.DEBUG) "debug" else "release")
            options.setDiagnosticLevel(SentryLevel.WARNING)
        }
    }
}
