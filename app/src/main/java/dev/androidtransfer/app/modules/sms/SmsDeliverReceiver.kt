package dev.androidtransfer.app.modules.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Required for the default-SMS-app role: while this app briefly holds that
 * role during an SMS import (see SmsModule), the platform stops
 * auto-writing incoming messages to the provider and delegates that job to
 * us, so any SMS arriving in that window has to be inserted here or it's
 * lost. Messages are grouped by sender to reassemble multi-part texts.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val address = messages[0].originatingAddress
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val date = messages[0].timestampMillis

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, 0)
        }
        runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }
    }
}
