package com.nexus.agent.ui

import android.content.Context
import android.widget.Toast

/** Shows a short toast. Convenience for user-facing confirmations and errors. */
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}
