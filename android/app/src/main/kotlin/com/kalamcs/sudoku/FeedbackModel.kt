// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

internal enum class FeedbackType(val key: String, val label: String) {
    SUGGESTION("suggestion", "Suggestion"),
    BUG("bug_report", "Bug Report"),
    OTHER("other", "Other"),
}

internal const val MAX_FEEDBACK_LENGTH = 1000
internal const val MAX_CONTACT_LENGTH = 254
private const val FEEDBACK_WAIT_MS = 10_000L

internal data class FeedbackDraft(
    val type: FeedbackType = FeedbackType.SUGGESTION,
    val message: String = "",
    val wantsReply: Boolean = false,
    val email: String = "",
)

internal sealed interface FeedbackDelivery {
    data object Ready : FeedbackDelivery
    data object Sending : FeedbackDelivery
    data object Pending : FeedbackDelivery
    data object Sent : FeedbackDelivery
    data class Failed(val reason: String) : FeedbackDelivery
}

// Keep an outstanding write tied to the app process across activity relaunches.
internal object FeedbackModel {
    var draft = FeedbackDraft()
    private val deliveryState = MutableLiveData<FeedbackDelivery>(FeedbackDelivery.Ready)
    val delivery: LiveData<FeedbackDelivery> get() = deliveryState
    private val handler = Handler(Looper.getMainLooper())
    private var submission: Task<DocumentReference>? = null
    private val showPending = Runnable {
        if (submission?.isComplete == false) deliveryState.value = FeedbackDelivery.Pending
    }

    fun submit(context: Application) {
        if (submission != null || deliveryState.value == FeedbackDelivery.Sent) return
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            deliveryState.value = FeedbackDelivery.Failed("Feedback is unavailable in this build.")
            return
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            deliveryState.value = FeedbackDelivery.Failed("Connect to the internet and try again.")
            return
        }

        val entry = draft
        val payload = mutableMapOf(
            "type" to entry.type.key,
            "message" to entry.message.trim(),
            "appVersion" to BuildConfig.VERSION_NAME,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        if (entry.wantsReply) payload["email"] = entry.email.trim()

        val auth = FirebaseAuth.getInstance()
        val firestore = FirebaseFirestore.getInstance()
        val signedIn = if (auth.currentUser != null) Tasks.forResult(null) else auth.signInAnonymously()
        val task = signedIn.onSuccessTask { firestore.collection("feedback").add(payload) }
        submission = task
        deliveryState.value = FeedbackDelivery.Sending
        // Firestore can keep a write queued. Update the UI without starting another write.
        handler.postDelayed(showPending, FEEDBACK_WAIT_MS)
        task.addOnCompleteListener { result ->
            if (submission !== task) return@addOnCompleteListener
            handler.removeCallbacks(showPending)
            submission = null
            deliveryState.value = if (result.isSuccessful) FeedbackDelivery.Sent
            else FeedbackDelivery.Failed("Feedback could not be sent. Your draft is still here. Try again.")
        }
    }

    fun finish() {
        draft = FeedbackDraft()
        deliveryState.value = FeedbackDelivery.Ready
    }

}
