package com.kalamcs.sudoku

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

internal enum class FeedbackType(val key: String, val label: String) {
    SUGGESTION("suggestion", "Suggestion"),
    BUG("bug_report", "Bug Report"),
    OTHER("other", "Other"),
}

internal const val MAX_FEEDBACK_LENGTH = 1000
internal const val MAX_CONTACT_LENGTH = 254

internal data class FeedbackDraft(
    val type: FeedbackType = FeedbackType.SUGGESTION,
    val message: String = "",
    val wantsReply: Boolean = false,
    val email: String = "",
)

internal sealed interface FeedbackDelivery {
    data object Ready : FeedbackDelivery
    data object Sending : FeedbackDelivery
    data object Sent : FeedbackDelivery
    data class Failed(val reason: String) : FeedbackDelivery
}

internal class FeedbackModel(application: Application) : AndroidViewModel(application) {
    var draft = FeedbackDraft()
    private val deliveryState = MutableLiveData<FeedbackDelivery>(FeedbackDelivery.Ready)
    val delivery: LiveData<FeedbackDelivery> get() = deliveryState

    fun submit() {
        if (deliveryState.value == FeedbackDelivery.Sending || deliveryState.value == FeedbackDelivery.Sent) return
        val context = getApplication<Application>()
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
        deliveryState.value = FeedbackDelivery.Sending
        val signedIn = if (auth.currentUser != null) Tasks.forResult(null) else auth.signInAnonymously()
        signedIn.onSuccessTask { firestore.collection("feedback").add(payload) }
            .addOnCompleteListener { result ->
                deliveryState.value = if (result.isSuccessful) FeedbackDelivery.Sent
                else FeedbackDelivery.Failed("Feedback could not be sent. Your draft is still here. Try again.")
            }
    }

    fun finish() {
        draft = FeedbackDraft()
        deliveryState.value = FeedbackDelivery.Ready
    }
}
