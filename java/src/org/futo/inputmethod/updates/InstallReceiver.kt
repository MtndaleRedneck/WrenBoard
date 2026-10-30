package org.futo.inputmethod.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch


class InstallReceiver : BroadcastReceiver() {
    private val TAG = "InstallReceiver"

    companion object {
        val onReceiveResult = MutableSharedFlow<String>(0)

        /** WrenBoard 1.2: the update attempt a result belongs to rides the installer's result intent. */
        const val EXTRA_ATTEMPT = "com.wrenboard.keyboard.UPDATE_ATTEMPT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val attempt = intent.getLongExtra(EXTRA_ATTEMPT, -1L)
        Log.i(TAG, "Received status $status for update attempt $attempt.")

        // WrenBoard 1.2: success, the person cancelling and a real failure are told apart, and a result from an
        // attempt that is no longer the current one (an older tap, a closed prompt, a previous app process) is ignored.
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val shown = org.futo.inputmethod.latin.uix.settings.UpdateStatus.attempts.installerResult(
                attempt, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            )
            Log.i(TAG, if (shown) "Update attempt $attempt: ${UpdateAttempts.outcomeFor(status)}"
                       else "Ignored a result for update attempt $attempt (current ${org.futo.inputmethod.latin.uix.settings.UpdateStatus.attempts.currentToken}).")
        }

        GlobalScope.launch {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val activityIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }

                    if (activityIntent == null) {
                        Log.w(TAG, "Received STATUS_PENDING_USER_ACTION and activity intent is null.")
                        return@launch
                    }
                    context.startActivity(activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> onReceiveResult.emit("Success!")
                PackageInstaller.STATUS_FAILURE -> onReceiveResult.emit("General failure")
                PackageInstaller.STATUS_FAILURE_ABORTED -> onReceiveResult.emit("The operation failed because it was actively aborted")
                PackageInstaller.STATUS_FAILURE_BLOCKED -> onReceiveResult.emit("The operation failed because it was blocked")
                PackageInstaller.STATUS_FAILURE_CONFLICT -> onReceiveResult.emit("The operation failed because it conflicts (or is inconsistent with) with another package already installed on the device")
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> onReceiveResult.emit("The operation failed because it is fundamentally incompatible with this device")
                PackageInstaller.STATUS_FAILURE_INVALID -> onReceiveResult.emit("The operation failed because one or more of the APKs was invalid")
                PackageInstaller.STATUS_FAILURE_STORAGE -> onReceiveResult.emit("The operation failed because of storage issues")
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    if(msg != null) {
                        onReceiveResult.emit(msg)
                    }
                }
            }
        }
    }
}
