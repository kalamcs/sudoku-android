// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.doAfterTextChanged

internal class FeedbackScreen(private val context: Context, private val ui: Ui) {
    fun form(
        page: LinearLayout,
        draft: FeedbackDraft,
        delivery: FeedbackDelivery,
        back: () -> Unit,
        keepDraft: (FeedbackDraft) -> Unit,
        submit: () -> Unit,
    ) {
        var current = draft
        val sending = delivery == FeedbackDelivery.Sending
        fun update(next: FeedbackDraft) {
            current = next
            keepDraft(next)
        }

        page.isFocusableInTouchMode = true
        page.requestFocus()
        ui.add(page, ui.pageHeading("Send Feedback", back))
        if (delivery == FeedbackDelivery.Pending) {
            ui.add(page, ui.heading("Waiting for confirmation", 18), 16)
            ui.add(page, ui.text(
                "Check your internet connection. You can go back to Sudoku and reopen this screen to check delivery.",
                15,
            ).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, 6)
            ui.add(page, ui.button("Network settings") {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Check your connection in your device settings.", Toast.LENGTH_SHORT).show()
                }
            }, 14)
            return
        }
        val intro = ui.row().apply {
            addView(ImageView(context).apply {
                setImageDrawable(ui.icon(R.drawable.ic_feedback, 37))
                imageTintList = ColorStateList.valueOf(ui.palette.accentInk)
                background = ui.shape(ui.palette.selectedCell, 12)
                setPadding(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(53), ui.dp(53)))
            ui.equal(this, ui.column().apply {
                ui.add(this, ui.heading("Help us make Sudoku better!", 18))
                ui.add(this, ui.text("Share a suggestion, report an issue, or ask a question.", 14), 3)
            }, 10)
        }
        ui.add(page, intro, 12)

        ui.add(page, ui.heading("Feedback Type", 16), 16)
        val types = ui.segments(FeedbackType.entries, draft.type, FeedbackType::label, enabled = !sending) {
            update(current.copy(type = it))
        }
        ui.add(page, types, 5)
        val messageLabel = ui.heading("Your Feedback *", 16)
        ui.add(page, messageLabel, 14)
        val messageField = FrameLayout(context).apply {
            background = ui.dashboardSurface(11, .025f)
        }
        val message = field("Tell us what you think…", draft.message, MAX_FEEDBACK_LENGTH,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            id = View.generateViewId()
            gravity = Gravity.START or Gravity.TOP
            setPadding(ui.dp(11), ui.dp(10), ui.dp(11), ui.dp(29))
            isEnabled = !sending
        }
        messageLabel.labelFor = message.id
        val counter = ui.text("${draft.message.length} / $MAX_FEEDBACK_LENGTH", 12, ui.palette.muted).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        messageField.addView(message, FrameLayout.LayoutParams(MATCH_PARENT, ui.dp(113)))
        messageField.addView(counter, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
            bottomMargin = ui.dp(6)
            rightMargin = ui.dp(10)
        })
        ui.add(page, messageField, 5)
        message.doAfterTextChanged {
            val value = it?.toString().orEmpty()
            update(current.copy(message = value))
            counter.text = "${value.length} / $MAX_FEEDBACK_LENGTH"
        }

        val email = field("Your email address", draft.email, MAX_CONTACT_LENGTH,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply {
            minimumHeight = ui.dp(46)
            background = ui.dashboardSurface(11, .025f)
            setPadding(ui.dp(11), ui.dp(6), ui.dp(11), ui.dp(6))
            isEnabled = draft.wantsReply && !sending
            setCompoundDrawablesRelative(ui.icon(R.drawable.ic_feedback, 19), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(ui.palette.muted)
            compoundDrawablePadding = ui.dp(7)
        }
        val contact = AppCompatCheckBox(context).apply {
            text = "I’d like to be contacted about this feedback"
            textSize = 14f
            setTextColor(ui.palette.ink)
            buttonTintList = ColorStateList.valueOf(ui.palette.accentInk)
            minimumHeight = ui.dp(48)
            isChecked = draft.wantsReply
            isEnabled = !sending
            setOnCheckedChangeListener { _, checked ->
                email.isEnabled = checked
                email.error = null
                update(current.copy(wantsReply = checked))
            }
        }
        email.doAfterTextChanged { update(current.copy(email = it?.toString().orEmpty())) }
        ui.add(page, contact, 4)
        ui.add(page, email)
        ui.add(page, ui.text("We’ll only use your email to follow up on this feedback.", 12, ui.palette.muted), 5)
        when (delivery) {
            is FeedbackDelivery.Failed -> ui.add(page, ui.text(delivery.reason, 14, ui.palette.error).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, 10)
            else -> Unit
        }
        ui.add(page, primaryButton(if (sending) "Sending…" else "Send Feedback", R.drawable.ic_send) {
            when {
                current.message.isBlank() -> {
                    message.error = "Enter your feedback."
                    message.requestFocus()
                }
                current.wantsReply && !Patterns.EMAIL_ADDRESS.matcher(current.email.trim()).matches() -> {
                    email.error = "Enter a valid email address."
                    email.requestFocus()
                }
                else -> submit()
            }
        }.apply { isEnabled = !sending }, 17)
    }

    fun sent(page: LinearLayout, done: () -> Unit) {
        val body = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(86), 0, 0)
        }
        val check = ImageView(context).apply {
            background = ui.shape(ui.palette.successSurface, 55)
            setPadding(ui.dp(23), ui.dp(23), ui.dp(23), ui.dp(23))
            setImageDrawable(ui.icon(R.drawable.ic_check, 48))
            imageTintList = ColorStateList.valueOf(ui.palette.successInk)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        body.addView(check, LinearLayout.LayoutParams(ui.dp(92), ui.dp(92)))
        ui.add(body, ui.heading("Feedback Sent!", 25).apply { gravity = Gravity.CENTER }, 14)
        ui.add(body, ui.text("Thank you for helping improve Sudoku.", 17).apply { gravity = Gravity.CENTER }, 8)
        ui.add(body, primaryButton("Done", onClick = done), 35)
        body.addView(HomeWaves(context, ui.palette), LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(54)).apply {
            topMargin = ui.dp(38)
        })
        ui.add(page, body)
    }

    private fun field(hintText: String, value: String, limit: Int, type: Int) = AppCompatEditText(context).apply {
        background = null
        textSize = 15f
        setTextColor(ui.palette.ink)
        setHintTextColor(ui.palette.muted)
        hint = hintText
        inputType = type
        filters = arrayOf(InputFilter.LengthFilter(limit))
        setText(value)
        ui.tintTextInput(this)
    }

    private fun primaryButton(label: String, icon: Int = 0, onClick: () -> Unit) =
        ui.button(label, primary = true, icon = icon, onClick = onClick).apply {
            textSize = 16f
            letterSpacing = 0f
            background = ui.dashboardPress(ui.shape(ui.palette.accent, 14))
        }
}
