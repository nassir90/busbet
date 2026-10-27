package iompar.mpts.ie

import android.app.Application
import okhttp3.Request
import java.io.IOException
import java.lang.reflect.Type

/**
 * The F-Droid build carries no crash-reporting SDK at all, so every call is a no-op. Same surface
 * as the play flavour's Telemetry so shared code calls it without knowing which build it is in.
 */
object Telemetry {
    fun init(app: Application) {}
    fun captureException(e: Throwable) {}
    fun captureHttpError(request: Request, code: Int) {}
    fun captureNetworkError(request: Request, e: IOException) {}
    fun captureSchemaError(type: Type, e: Throwable) {}
    fun trackAppOpen() {}
    fun trackScreen(name: String) {}
}
