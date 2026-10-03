package com.steptrackerpro.health

import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Health Connect's own step counting on this phone, from the platform's side.
 *
 * From Android 14 with SDK extension 20, Health Connect counts the phone's
 * steps itself - from the same `TYPE_STEP_COUNTER` this package reads - once
 * any app holds `READ_STEPS`, and writes them about once a minute. Those
 * records came from the package `android` until June 2026, and from a
 * synthetic package name per device and per reading app since, of the form
 * `com.android.healthconnect.phone.<hash>`. [StepSourceCatalog] recognises
 * both by name; [currentOrigin] asks the platform for this phone's exact one,
 * so a name of another shape is recognised too.
 */
object DeviceDataSources {

    /** Health Connect counts this phone's steps itself: Android 14 with SDK extension 20 or later. */
    fun stepTrackingAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && extensionAtLeast(20)

    /**
     * The package name Health Connect gives this phone's own data, as this
     * app sees it, from `HealthConnectManager.getCurrentDeviceDataSource()`.
     * Null where the platform has no such call, or answers it with an error -
     * as it does for an app that holds no Health Connect read grant yet.
     * Reached by reflection: the call is newer than the SDK this package
     * compiles against.
     */
    suspend fun currentOrigin(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) currentOrigin34(context) else null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun currentOrigin34(context: Context): String? {
        val manager = runCatching {
            @Suppress("UNCHECKED_CAST")
            context.getSystemService(
                Class.forName("android.health.connect.HealthConnectManager") as Class<Any>
            )
        }.getOrNull() ?: return null
        val call = runCatching {
            manager.javaClass.getMethod(
                "getCurrentDeviceDataSource", Executor::class.java, OutcomeReceiver::class.java
            )
        }.getOrNull() ?: return null
        val source = suspendCancellableCoroutine<Any?> { continuation ->
            val receiver = object : OutcomeReceiver<Any?, Throwable> {
                override fun onResult(result: Any?) {
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onError(error: Throwable) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            runCatching { call.invoke(manager, Executor { it.run() }, receiver) }
                .onFailure { if (continuation.isActive) continuation.resume(null) }
        } ?: return null
        return runCatching {
            val origin = source.javaClass.getMethod("getDeviceDataOrigin").invoke(source)
            origin?.javaClass?.getMethod("getPackageName")?.invoke(origin) as? String
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun extensionAtLeast(version: Int): Boolean = runCatching {
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= version
    }.getOrDefault(false)
}
