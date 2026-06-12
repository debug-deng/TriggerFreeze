package com.example.triggerfreeze.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class AppInfo(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val iconBitmap: ImageBitmap? = null
) {
    companion object {
        fun loadIcon(drawable: Drawable, sizePx: Int = 64): ImageBitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            return bitmap.asImageBitmap()
        }
    }
}

data class TriggerLogEntry(
    val timestamp: Long,
    val type: Type,
    val triggerPackage: String?,
    val targetPackage: String,
    val success: Boolean,
    val detail: String
) {
    enum class Type { TRIGGER, FREEZE, UNFREEZE, ERROR }
}

data class FreezeRule(
    val triggerPackage: String,
    val frozenPackages: Set<String>,
    val isEnabled: Boolean = true
)

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean
        get() {
            val output = "$stdout\n$stderr"
            return exitCode == 0 &&
                !output.contains("Permission Denial", ignoreCase = true) &&
                !output.contains("not found", ignoreCase = true) &&
                !output.contains("Unknown command", ignoreCase = true)
        }
}
