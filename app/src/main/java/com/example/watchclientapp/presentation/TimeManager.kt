package com.example.watchclientapp.presentation


import android.content.Context
import com.lyft.kronos.KronosClock
import com.lyft.kronos.AndroidClockFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton for providing NTP-synchronized time across the app.
 * Must be initialized before use (e.g. in Application.onCreate()).
 */
object NtpTimeProvider {
    private lateinit var kronosClock: KronosClock

    /**
     * Initialize the singleton. Call once from Application or first Service.
     * @param context Any application context
     */
    fun initialize(context: Context) {

        // Build a KronosClock with custom NTP hosts and sync settings
        kronosClock = AndroidClockFactory.createKronosClock(
            context = context,
            ntpHosts = listOf("time.google.com"),          // use Google's NTP server explicitly
            requestTimeoutMs = 5_000L,                       // 5 second timeout per request
            minWaitTimeBetweenSyncMs = TimeUnit.MINUTES.toMillis(15),  // at most once every 15 minutes
            cacheExpirationMs = TimeUnit.MINUTES.toMillis(15),         // cache valid for 15 minutes

        )

        // Kick off an initial sync in the background
        kronosClock.syncInBackground()
    }


    /**
     * Returns NTP-corrected time in milliseconds since Unix epoch.
     * Falls back to system time if not yet initialized or sync pending.
     */
    fun nowMs(): Long =
        if (::kronosClock.isInitialized) {
//            SocketManager.debug("kronos is getting initialized")
            kronosClock.getCurrentTimeMs()
        }
        else System.currentTimeMillis()

    /**
     * Returns NTP-corrected time in microseconds since Unix epoch.
     * Attempts to provide sub-millisecond precision by combining
     * Kronos's millisecond time with System.nanoTime() for the fractional part.
     * Falls back to system time if not yet initialized or sync pending.
     */
    fun nowUs(): Long {
        return if (::kronosClock.isInitialized) {
            val ntpTimeMs = kronosClock.getCurrentTimeMs()
            // Get the current system's high-resolution nanosecond time.
            val currentSystemNanoTime = System.nanoTime()
            // Get the current system's millisecond time (used as a reference point for nanoTime).
            val currentSystemMs = System.currentTimeMillis()

            // Calculate the nanosecond offset from the last full millisecond boundary
            // based on the system's internal clocks. This helps provide the fractional part.
            val subMillisecondNanos = (currentSystemNanoTime - (currentSystemMs * 1_000_000L)) % 1_000_000L
            val subMillisecondMicros = subMillisecondNanos / 1_000L

            // Combine the NTP-corrected milliseconds with the system's calculated sub-millisecond part.
            (ntpTimeMs * 1000L) + subMillisecondMicros
        } else {
            // Fallback to system time, attempting to get microsecond precision
            val systemTimeMs = System.currentTimeMillis()
            val currentSystemNanoTime = System.nanoTime()
            val subMillisecondNanos = (currentSystemNanoTime - (systemTimeMs * 1_000_000L)) % 1_000_000L
            val subMillisecondMicros = subMillisecondNanos / 1_000L
            (systemTimeMs * 1000L) + subMillisecondMicros
        }
    }
    /**
     * Force an immediate background sync with the NTP server.
     */
    fun forceSync() {
        if (::kronosClock.isInitialized) {
            kronosClock.syncInBackground()
        }
    }

}
