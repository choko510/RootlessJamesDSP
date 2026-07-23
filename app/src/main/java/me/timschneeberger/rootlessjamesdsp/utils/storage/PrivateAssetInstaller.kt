package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import android.os.Trace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Installs the bundled file-library assets without making the UI wait on disk IO.
 *
 * The installer is process-scoped (owned by [MainApplication]) so requests from the first screen,
 * a file picker, and the repair action all join one job. Existing files are never replaced: this
 * preserves files edited or imported by the user while still allowing new files from an app update
 * to be added.
 */
class PrivateAssetInstaller(context: Context) {
    enum class State {
        Idle,
        Running,
        Ready,
        Failed,
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State.Idle)
    private var inFlight: Deferred<kotlin.Result<Unit>>? = null

    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun ensureInstalled(force: Boolean): kotlin.Result<Unit> {
        if (!force && _state.value == State.Ready && installedVersionMatches())
            return kotlin.Result.success(Unit)

        val job = mutex.withLock {
            inFlight?.takeUnless { it.isCompleted } ?: scope.async {
                install(force)
            }.also { inFlight = it }
        }

        return try {
            job.await()
        }
        finally {
            mutex.withLock {
                if (inFlight === job)
                    inFlight = null
            }
        }
    }

    private fun install(force: Boolean): kotlin.Result<Unit> {
        if (!force && installedVersionMatches()) {
            _state.value = State.Ready
            return kotlin.Result.success(Unit)
        }

        _state.value = State.Running
        Trace.beginSection("PrivateAssetInstaller.ensureInstalled")
        return try {
            val externalFilesDir = appContext.getExternalFilesDir(null)
                ?: throw IOException("External files directory is unavailable")

            ASSET_ROOTS.forEach { assetRoot ->
                walkAssetTree(assetRoot) { assetPath ->
                    copyIfMissing(assetPath, externalFilesDir)
                }
            }

            // Commit the version only after every file has been copied successfully. A failed or
            // interrupted installation is therefore retried on the next request.
            if (!preferences.edit().putInt(KEY_INSTALLED_VERSION, BuildConfig.VERSION_CODE).commit())
                throw IOException("Unable to persist private asset version")

            _state.value = State.Ready
            kotlin.Result.success(Unit)
        }
        catch (ex: Throwable) {
            _state.value = State.Failed
            kotlin.Result.failure(ex)
        }
        finally {
            Trace.endSection()
        }
    }

    private fun installedVersionMatches(): Boolean =
        preferences.getInt(KEY_INSTALLED_VERSION, INVALID_VERSION) == BuildConfig.VERSION_CODE

    private fun walkAssetTree(assetPath: String, callback: (String) -> Unit) {
        val children = appContext.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            callback(assetPath)
            return
        }

        children.forEach { child ->
            walkAssetTree("$assetPath/$child", callback)
        }
    }

    private fun copyIfMissing(assetPath: String, externalFilesDir: File) {
        val destination = File(externalFilesDir, assetPath)
        if (destination.exists())
            return

        destination.parentFile?.mkdirs()
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${UUID.randomUUID()}.tmp"
        )

        try {
            Trace.beginSection("PrivateAssetInstaller.copy")
            appContext.assets.open(assetPath).use { source ->
                temporary.outputStream().use { target ->
                    source.copyTo(target)
                    target.fd.sync()
                }
            }

            // A user or another process may have created the destination while the asset was
            // being copied. Never replace that file.
            if (destination.exists())
                return
            if (!temporary.renameTo(destination) && !destination.exists())
                throw IOException("Unable to atomically install $assetPath")
        }
        finally {
            Trace.endSection()
            temporary.delete()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "private_asset_installer"
        private const val KEY_INSTALLED_VERSION = "installed_version_code"
        private const val INVALID_VERSION = -1
        private val ASSET_ROOTS = listOf("Convolver", "DDC", "Liveprog")
    }
}
