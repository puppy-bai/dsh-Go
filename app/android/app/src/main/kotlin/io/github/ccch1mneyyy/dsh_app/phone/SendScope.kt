package io.github.ccch1mneyyy.dsh_app.phone

import java.security.MessageDigest

/** One declared send in the current phone task. Only its fingerprint is retained after consent. */
class SendScope private constructor(val app: String, val recipient: String, val text: String) {
    internal val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(Json.write(listOf(app, recipient, text)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun parse(a: Args): SendScope? {
            if ("send" !in a.keys()) return null
            val raw = a.obj("send") ?: throw IllegalArgumentException("invalid-send-scope")
            require(raw.keys == setOf("app", "recipient", "text")) { "invalid-send-scope-fields" }
            val app = raw["app"] as? String ?: throw IllegalArgumentException("invalid-send-app")
            val recipient = raw["recipient"] as? String ?: throw IllegalArgumentException("invalid-send-recipient")
            val text = raw["text"] as? String ?: throw IllegalArgumentException("invalid-send-text")
            require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(app)) { "invalid-send-app" }
            require(recipient.isNotBlank() && recipient.length <= 200) { "invalid-send-recipient" }
            require(text.isNotBlank() && text.length <= 2000) { "invalid-send-text" }
            return SendScope(app, recipient, text)
        }
    }
}
