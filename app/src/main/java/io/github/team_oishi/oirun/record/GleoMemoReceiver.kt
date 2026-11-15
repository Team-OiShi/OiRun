package io.github.team_oishi.oirun.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.team_oishi.oirun.OiRunApp
import io.github.team_oishi.oirun.course.shortTitle
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Gleo's ADD_COURSE_MEMO (assets/schema.json): "오이런에 메모해줘, 여기 화장실 있음". The memo
 * goes where the driver started asking, not where the car is once Gleo has understood them.
 * Gleo waits up to 7 s for the answer it then speaks.
 */
class GleoMemoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val calledAt = SystemClock.elapsedRealtime()
        val recorder = (context.applicationContext as OiRunApp).recorder
        val (text, title) = memoParams(intent.getStringExtra("ACTION_PARAM"))
        // A memo Gleo had to ask for again still belongs where the first request began.
        val startedAt = asked?.takeIf { calledAt - it <= ASKING_MILLIS }
            ?: recorder.listenStart(calledAt)
            ?: calledAt
        if (text.isEmpty()) {
            asked = startedAt
            respond(context, "asking", "The memo text is required.")
            return
        }
        asked = null
        val added = recorder.addMemo(text, title, startedAt)
        Log.i(TAG, "Gleo memo ${if (added) "added" else "refused"}, ${(calledAt - startedAt) / 1000.0} s after the request began")
        if (added) {
            respond(context, "success", "The memo was added where the request began.")
            DriveService.notifyMemo(context, title.ifBlank { shortTitle(text) }, text)
        } else {
            respond(context, "fail", "Memos can only be added while driving.")
        }
    }

    private fun respond(context: Context, status: String, message: String) {
        val response = JSONObject()
            .put("functionName", ACTION)
            .put("status", status)
            .put("message", message)
        context.sendBroadcast(
            Intent(RESPONSE)
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString())
        )
    }

    companion object {
        const val ACTION = "ADD_COURSE_MEMO"
        private const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        /** Gleo asks again right away; an older ask was abandoned. */
        private const val ASKING_MILLIS = 60_000L

        private var asked: Long? = null

        /**
         * The memo and its title, either possibly blank. The parameters are a JSON object; the
         * guide's sample wraps one in a JSON string.
         */
        private fun memoParams(param: String?): Pair<String, String> {
            if (param.isNullOrBlank()) return "" to ""
            return runCatching {
                val value = JSONTokener(param).nextValue()
                val json = if (value is String) JSONObject(value) else value as JSONObject
                json.optString("memo").trim() to json.optString("title").trim()
            }.getOrDefault("" to "")
        }
    }
}

private const val TAG = "OiRun"
