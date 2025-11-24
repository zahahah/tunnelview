package com.zahah.tunnelview.update

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.R

object UpdateDialogFactory {
    fun showCheckingDialog(
        context: Context,
        onCancel: (() -> Unit)? = null
    ): AlertDialog {
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 32)
            addView(progress)
            addView(TextView(context).apply {
                text = context.getString(R.string.git_update_checking)
                setPadding(0, 16, 0, 0)
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            })
        }
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.git_update_check_now)
            .setView(container)
            .setCancelable(true)
            .also { builder ->
                onCancel?.let { cancel ->
                    builder.setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        cancel()
                        dialog.dismiss()
                    }
                    builder.setOnCancelListener { cancel() }
                }
            }
            .show()
    }

    fun showNoUpdateDialog(context: Context, message: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.git_update_not_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    fun buildNoUpdateMessage(context: Context, prefs: Prefs, fallback: String? = null): String {
        val status = prefs.lastGitUpdateStatus?.takeIf { it.isNotBlank() }
        val statusAt = prefs.lastGitUpdateStatusAt
        val checkAt = prefs.lastGitUpdateCheckAtMillis
        val statusMatchesLastCheck = statusAt > 0L && checkAt > 0L && statusAt >= checkAt
        val detail = status
            ?.takeIf { statusMatchesLastCheck }
            ?.let { context.getString(R.string.git_update_status_detail, it) }
        return buildString {
            append(fallback ?: context.getString(R.string.git_update_not_available))
            if (detail != null) {
                append("\n\n")
                append(detail)
            }
        }
    }
}
