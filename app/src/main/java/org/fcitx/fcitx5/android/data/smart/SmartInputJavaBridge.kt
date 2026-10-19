/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

/** Kotlin coroutine adapter kept package-local to the in-process Java facade. */
object SmartInputJavaBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        scope.launch { for (operation in operations) operation() }
    }
    private fun submit(operation: suspend () -> Unit) {
        operations.trySend(operation).getOrThrow()
    }

    @JvmStatic
    fun getConfig(callback: SmartInputApi.Callback<Map<String, String>>) {
        submit { run(callback) { SmartInputControl.getConfig() } }
    }

    @JvmStatic
    fun configure(
        patch: Map<String, String>,
        callback: SmartInputApi.Callback<Map<String, String>>
    ) {
        val snapshot = LinkedHashMap(patch)
        submit { run(callback) { SmartInputControl.configure(snapshot.toMap()) } }
    }

    @JvmStatic
    fun replaceCandidates(
        suggestions: List<SmartInputApi.Candidate>,
        corrections: List<SmartInputApi.Candidate>,
        ttlMs: Long,
        callback: SmartInputApi.Callback<Map<String, String>>
    ) {
        val suggestionSnapshot = suggestions.toList().map {
            SmartInputContract.Candidate(it.key, it.text, it.weight)
        }
        val correctionSnapshot = corrections.toList().map {
            SmartInputContract.Candidate(it.key, it.text, it.weight)
        }
        submit {
            run(callback) {
                SmartInputControl.replaceCandidates(
                    suggestionSnapshot.toList(), correctionSnapshot.toList(), ttlMs
                )
            }
        }
    }

    @JvmStatic
    fun clearCandidates(callback: SmartInputApi.Callback<Map<String, String>>) {
        submit { run(callback) { SmartInputControl.clearCandidates() } }
    }

    @JvmStatic
    fun resetConfig(callback: SmartInputApi.Callback<Map<String, String>>) {
        submit { run(callback) { SmartInputControl.resetConfig() } }
    }

    private suspend fun run(
        callback: SmartInputApi.Callback<Map<String, String>>,
        operation: suspend () -> Map<String, String>
    ) {
        try {
            val result = operation()
            withContext(Dispatchers.Main.immediate) {
                try {
                    callback.onSuccess(result)
                } catch (_: Throwable) {
                    // A callback failure must not be converted into a second callback.
                }
            }
        } catch (error: CancellationException) {
            // Cancellation is control flow and must never be reported as an API error.
            throw error
        } catch (error: Throwable) {
            withContext(Dispatchers.Main.immediate) {
                try {
                    callback.onError(error)
                } catch (_: Throwable) {
                    // Callback failures are intentionally terminal and are not retried.
                }
            }
        }
    }
}
