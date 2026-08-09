package iompar.mpts.ie

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
        // reach it without MainActivity ever having run. Both calls return immediately: the
        // config is filled in on IO (callers of Api suspend until it lands) and Api.init only
        // records a cache path. Nothing here touches the disk on the main thread.
        BackendConfigHolder.init(this)
        Api.init(this)
        PaletteCache.init(this)

        // Debug builds only. Sentry collects crash reports, device metadata and IP addresses,
        // which for a published app means a Play Data safety declaration covering an SDK we get
        // no value from in release — and the privacy policy states there are no analytics or
        // tracking SDKs. Reintroduce deliberately, with the declarations, if it's ever wanted.
        if (BuildConfig.DEBUG) {
            SentryAndroid.init(this) { options ->
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
            // Starts the breadcrumb trail at a known point. Inside the gate purely for locality
            // with the init above — it is a no-op in release either way, since Sentry is never
            // initialised there.
            Telemetry.trackAppOpen()
        }
    }
}
