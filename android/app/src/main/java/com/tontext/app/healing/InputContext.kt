package com.tontext.app.healing

import android.content.Context
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo

data class InputContext(
    val packageName: String,
    val inputType: Int,
    val hintText: String,
    val imeOptions: Int,
    val appLabel: String
) {
    val isPasswordField: Boolean
        get() {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

    val isEmailField: Boolean
        get() {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        }

    val isUrlField: Boolean
        get() {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return variation == InputType.TYPE_TEXT_VARIATION_URI
        }

    val isSearchField: Boolean
        get() {
            val imeAction = imeOptions and EditorInfo.IME_MASK_ACTION
            return imeAction == EditorInfo.IME_ACTION_SEARCH
        }

    val isNumberField: Boolean
        get() {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            return inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE
        }

    companion object {
        fun fromEditorInfo(editorInfo: EditorInfo?, context: Context): InputContext {
            val pkg = editorInfo?.packageName ?: ""
            val appLabel = try {
                if (pkg.isNotEmpty()) {
                    val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(appInfo).toString()
                } else ""
            } catch (_: PackageManager.NameNotFoundException) {
                ""
            }

            return InputContext(
                packageName = pkg,
                inputType = editorInfo?.inputType ?: 0,
                hintText = editorInfo?.hintText?.toString() ?: "",
                imeOptions = editorInfo?.imeOptions ?: 0,
                appLabel = appLabel
            )
        }
    }
}
