package iompar.mpts.ie

import android.app.Application

/**
 * Application entry point. Crash reporting is debug-only, and absent entirely from the fdroid
 * flavour; see [Telemetry.init].
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
            Telemetry.init(this)
            // Starts the breadcrumb trail at a known point. Inside the gate purely for locality
            // with the init above — it is a no-op in release either way, since Sentry is never
            // initialised there.
            Telemetry.trackAppOpen()
        }
    }
}
