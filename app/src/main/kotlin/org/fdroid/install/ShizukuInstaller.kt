package org.fdroid.install

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import rikka.shizuku.Shizuku

/** Installs APKs through Shizuku's shell via `pm install`, bypassing PackageInstaller sessions. */
object ShizukuInstaller {

  private const val REQUEST_CODE = 4711

  private val log = KotlinLogging.logger {}

  fun isEnabled(context: Context): Boolean =
    context
      .getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
      .getString(RootInstaller.PREF_KEY_INSTALL_METHOD, "default") == "shizuku"

  private fun hasPermission(): Boolean =
    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

  /** Asks for Shizuku permission and waits (up to a minute) for the user's answer. */
  private suspend fun requestPermission(): Boolean {
    val result =
      withTimeoutOrNull(60_000) {
        suspendCancellableCoroutine<Boolean> { cont ->
          val listener =
            object : Shizuku.OnRequestPermissionResultListener {
              override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
              }
            }
          Shizuku.addRequestPermissionResultListener(listener)
          cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
          Shizuku.requestPermission(REQUEST_CODE)
        }
      }
    return result ?: false
  }

  /** Returns null on success, otherwise an error message. */
  suspend fun install(apk: File): String? =
    withContext(Dispatchers.IO) {
      try {
        if (!Shizuku.pingBinder()) return@withContext "Shizuku is not running"
        val info = "version=${Shizuku.getVersion()} uid=${Shizuku.getUid()}"
        if (!hasPermission()) {
          val granted = requestPermission()
          log.info { "Shizuku permission result: granted=$granted $info" }
          if (!granted) {
            return@withContext "Shizuku permission denied ($info, check=${Shizuku.checkSelfPermission()}, rationale=${Shizuku.shouldShowRequestPermissionRationale()})"
          }
        }
        val size = apk.length()
        // newProcess() is private in newer Shizuku API versions, so call it via reflection
        val method =
          Shizuku::class
            .java
            .getDeclaredMethod(
              "newProcess",
              Array<String>::class.java,
              Array<String>::class.java,
              String::class.java,
            )
        method.isAccessible = true
        val cmd = arrayOf("sh", "-c", "pm install -r -S $size")
        val process = method.invoke(null, cmd, null, null) as Process
        process.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
        val output = process.inputStream.bufferedReader().readText().trim()
        val error = process.errorStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        log.info { "Shizuku install of ${apk.name}: exit=$exit output=$output error=$error" }
        if (exit == 0 && output.contains("Success")) null
        else "Shizuku install failed ($info): $output $error".trim()
      } catch (e: Exception) {
        val cause = e.cause ?: e
        log.error(e) { "Shizuku install error: " }
        "Shizuku install error: ${cause::class.java.simpleName} ${cause.message}"
      }
    }
}
