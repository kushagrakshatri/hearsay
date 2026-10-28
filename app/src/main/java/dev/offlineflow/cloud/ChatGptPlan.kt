package dev.offlineflow.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.offlineflow.BuildConfig
import dev.offlineflow.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/**
 * "Sign in with ChatGPT" (OpenAI's open-source ChatGPT plan usage flow): cleanup and commands run on
 * the user's own ChatGPT plan through the Responses API, with no API key. Speech stays on-device
 * (this flow doesn't accept audio). Off until the user signs in; compiled out with -PnoCloud.
 *
 * Docs: developers.openai.com/siwc/token-sharing-open-source. Public OAuth client with PKCE and
 * dynamic registration, loopback redirect on 127.0.0.1. Blocking calls; run off the main thread.
 */
object ChatGptPlan {
    private const val TAG = "ChatGptPlan"
    private const val AUTH = "https://auth.openai.com"
    private const val AUTHORIZE = "$AUTH/api/accounts/authorize"
    private const val TOKEN = "$AUTH/api/accounts/oauth/token"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    private const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
    private const val APP_NAME = "Hearsay"
    private const val CALLBACK_PATH = "/auth/callback"
    private const val FILE = "chatgpt_plan.json"
    const val DEFAULT_MODEL = "gpt-6-luna"
    const val USAGE_URL = "https://chatgpt.com/settings/usage"

    private val random = SecureRandom()
    private val lock = Any()

    /** Saved sign-in (one ChatGPT account per phone). */
    data class Account(
        val email: String, val subject: String, val clientId: String, val idToken: String,
        val accessToken: String, val refreshToken: String, val expiresAt: Long, val scopes: List<String>,
    )

    class PlanException(message: String) : Exception(message)

    // ---------------------------------------------------------------- state

    fun account(c: Context): Account? = synchronized(lock) { read(c) }

    /** Signed in with plan usage granted, and the user has it switched on for cleanup. */
    fun active(c: Context): Boolean = BuildConfig.CLOUD && Prefs.chatGptCleanup(c) && account(c)?.let { it.accessToken.isNotEmpty() } == true

    /** Stable opaque id for this install, chosen before the first sign-in. */
    private fun hostId(c: Context): String {
        val p = c.getSharedPreferences("hearsay", Context.MODE_PRIVATE)
        p.getString("chatgpt_host_id", null)?.let { return it }
        return "urn:uuid:${UUID.randomUUID()}".also { p.edit().putString("chatgpt_host_id", it).apply() }
    }

    /** The issued client id survives sign-out so a later sign-in reuses the registration. */
    private fun savedClientId(c: Context): String? =
        c.getSharedPreferences("hearsay", Context.MODE_PRIVATE).getString("chatgpt_client_id", null)

    private fun saveClientId(c: Context, id: String) =
        c.getSharedPreferences("hearsay", Context.MODE_PRIVATE).edit().putString("chatgpt_client_id", id).apply()

    // ---------------------------------------------------------------- sign in

    /**
     * Opens the browser for sign-in and consent, waits for the loopback callback (up to 5 minutes),
     * exchanges the code, validates the ID token, and saves the account. Returns the account.
     */
    fun signIn(context: Context): Account {
        val c = context.applicationContext
        val server = openListener()
        server.use {
            val redirect = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
            val state = token(24); val nonce = token(24); val verifier = token(48)
            val existing = savedClientId(c)
            val old = account(c)
            val params = linkedMapOf(
                "client_id" to (existing ?: "dynamic_agent_client"),
                "ext_agent_host_id" to hostId(c),
                "response_type" to "code",
                "redirect_uri" to redirect,
                "scope" to SCOPES,
                "resource" to RESOURCE,
                "state" to state,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to Pkce.challenge(verifier),
            )
            if (existing == null) params["agent_name_hint"] = APP_NAME
            old?.let { params["id_token_hint"] = it.idToken; params["login_hint"] = it.email }
            val url = AUTHORIZE + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            server.soTimeout = 5 * 60_000
            val query = awaitCallback(server) ?: throw PlanException("Sign-in timed out")
            if (query["state"] != state) throw PlanException("Sign-in failed (state mismatch)")
            query["error"]?.let {
                throw PlanException(if (it == "access_denied") "ChatGPT plan use wasn't allowed" else "Sign-in failed: $it")
            }
            val code = query["code"] ?: throw PlanException("Sign-in failed (no code)")
            val clientId = when {
                existing == null -> query["client_id"]?.takeIf { it != "dynamic_agent_client" }
                    ?: throw PlanException("Registration didn't finish. Try again.")
                query["client_id"] != null && query["client_id"] != existing -> throw PlanException("Sign-in returned a different app registration")
                else -> existing
            }
            val tokens = postForm(TOKEN, mapOf(
                "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
                "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to RESOURCE,
            ))
            val claims = IdToken.verify(tokens.getString("id_token"), clientId, nonce)
            val scopes = scopesOf(tokens)
            if (PLAN_SCOPE !in scopes) throw PlanException("ChatGPT plan use wasn't granted")
            saveClientId(c, clientId)
            val account = Account(
                email = claims.optString("email"), subject = claims.getString("sub"), clientId = clientId,
                idToken = tokens.getString("id_token"), accessToken = tokens.getString("access_token"),
                refreshToken = tokens.optString("refresh_token"),
                expiresAt = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000, scopes = scopes,
            )
            synchronized(lock) { write(c, account) }
            pickModel(c, account)
            return account
        }
    }

    private fun openListener(): ServerSocket {
        val lo = InetAddress.getByName("127.0.0.1")
        return runCatching { ServerSocket(1455, 4, lo) }.getOrElse { ServerSocket(0, 4, lo) }
    }

    /** Accepts loopback requests until one hits the callback path; answers with a small page. */
    private fun awaitCallback(server: ServerSocket): Map<String, String>? {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return null
            socket.use { s ->
                val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                val target = line.split(" ").getOrNull(1) ?: ""
                val ok = target.startsWith(CALLBACK_PATH)
                val body = if (ok) PAGE else "Not found"
                s.getOutputStream().write(
                    ("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray()
                )
                if (ok) return parseQuery(target.substringAfter('?', ""))
            }
        }
        return null
    }

    private val PAGE = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Hearsay</title></head><body style="font-family:sans-serif;text-align:center;padding:48px 24px">
        <h2>You're signed in</h2><p>Hearsay will use your ChatGPT plan for cleanup and commands.</p>
        <p><a style="display:inline-block;padding:12px 20px;background:#000;color:#fff;border-radius:24px;text-decoration:none"
        href="intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=dev.offlineflow/.SettingsActivity;end">Back to Hearsay</a></p>
        </body></html>
    """.trimIndent()

    /** Uses gpt-6-luna when this account offers it, else the first listed model. */
    private fun pickModel(c: Context, a: Account) = runCatching {
        val conn = (URL("$RESOURCE/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${a.accessToken}")
        }
        val text = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        val arr = JSONObject(text).optJSONArray("models") ?: JSONObject(text).optJSONArray("data") ?: JSONArray()
        val slugs = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("visibility", "list") == "list" }
            .map { it.optString("slug").ifEmpty { it.optString("id") } }.filter { it.isNotEmpty() }
        val want = Prefs.chatGptModel(c)
        if (slugs.isNotEmpty() && want !in slugs) Prefs.setChatGptModel(c, if (DEFAULT_MODEL in slugs) DEFAULT_MODEL else slugs.first())
    }.onFailure { Log.w(TAG, "model list failed", it) }

    // ---------------------------------------------------------------- sign out

    /** Revokes the refresh token (best effort) and clears tokens; keeps the registration and host id. */
    fun signOut(context: Context): Boolean {
        val c = context.applicationContext
        val a = account(c) ?: return true
        val revoked = runCatching {
            val config = JSONObject(get("$AUTH/.well-known/openid-configuration"))
            val endpoint = config.optString("revocation_endpoint").ifEmpty { "$AUTH/oauth/revoke" }
            if (a.refreshToken.isNotEmpty()) postForm(endpoint, mapOf(
                "token" to a.refreshToken, "token_type_hint" to "refresh_token", "client_id" to a.clientId,
            ), allowEmpty = true)
            true
        }.getOrDefault(false)
        synchronized(lock) { File(c.filesDir, FILE).delete() }
        return revoked
    }

    // ---------------------------------------------------------------- inference

    /**
     * Runs [messages] (role, content) through the Responses API on the user's plan and returns the
     * text. System messages become `instructions`; reasoning effort "none" for lowest latency.
     */
    fun respond(context: Context, messages: List<Pair<String, String>>): String {
        val c = context.applicationContext
        var a = fresh(c)
        val model = Prefs.chatGptModel(c)
        for (attempt in 0..2) {
            val effort = if (attempt == 2) "low" else "none"
            try {
                return Responses.stream(a.accessToken, model, messages, effort)
            } catch (e: Responses.HttpError) {
                when {
                    e.code == 401 && attempt == 0 -> a = refresh(c, a)
                    e.code == 400 && e.body.contains("reasoning") && attempt < 2 -> continue
                    else -> throw PlanException(describe(e.body) ?: (e.message ?: "ChatGPT request failed"))
                }
            }
        }
        throw PlanException("ChatGPT request failed")
    }

    private fun describe(body: String): String? = when {
        "subscription_sharing_usage_limit_exceeded" in body -> "ChatGPT plan limit reached"
        "subscription_sharing_usage_unavailable" in body -> "ChatGPT plan usage unavailable"
        else -> null
    }

    /** Access token with at least a minute left, refreshing if needed. */
    private fun fresh(c: Context): Account {
        val a = account(c) ?: throw PlanException("Not signed in to ChatGPT")
        return if (a.expiresAt - System.currentTimeMillis() > 60_000) a else refresh(c, a)
    }

    /** Serialized so two callers never race a rotating refresh token. */
    private fun refresh(c: Context, stale: Account): Account = synchronized(lock) {
        val current = read(c) ?: throw PlanException("Not signed in to ChatGPT")
        if (current.accessToken != stale.accessToken && current.expiresAt - System.currentTimeMillis() > 60_000) return current
        if (current.refreshToken.isEmpty()) throw PlanException("Sign in to ChatGPT again")
        val t = try {
            postForm(TOKEN, mapOf(
                "grant_type" to "refresh_token", "client_id" to current.clientId,
                "refresh_token" to current.refreshToken, "resource" to RESOURCE,
            ))
        } catch (e: Responses.HttpError) {
            if (e.code in 400..499) { File(c.filesDir, FILE).delete(); throw PlanException("Sign in to ChatGPT again") }
            throw e
        }
        val next = current.copy(
            accessToken = t.getString("access_token"),
            refreshToken = t.optString("refresh_token").ifEmpty { current.refreshToken },
            idToken = t.optString("id_token").ifEmpty { current.idToken },
            expiresAt = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000,
            scopes = scopesOf(t).ifEmpty { current.scopes },
        )
        write(c, next)
        next
    }

    // ---------------------------------------------------------------- storage

    private fun read(c: Context): Account? = runCatching {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        Account(
            j.getString("email"), j.getString("subject"), j.getString("client_id"), j.getString("id_token"),
            j.getString("access_token"), j.optString("refresh_token"), j.getLong("expires_at"),
            j.optJSONArray("scopes")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
        )
    }.getOrNull()

    /** Atomic write into private app storage (owner-only, excluded from backup). */
    private fun write(c: Context, a: Account) {
        val j = JSONObject()
            .put("issuer", AUTH).put("email", a.email).put("subject", a.subject).put("client_id", a.clientId)
            .put("ext_agent_host_id", hostId(c)).put("id_token", a.idToken).put("access_token", a.accessToken)
            .put("refresh_token", a.refreshToken).put("expires_at", a.expiresAt).put("scopes", JSONArray(a.scopes))
            .put("saved_at", System.currentTimeMillis())
        val tmp = File(c.filesDir, "$FILE.tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(File(c.filesDir, FILE))) { tmp.delete(); error("couldn't save sign-in") }
    }

    // ---------------------------------------------------------------- http helpers

    private fun scopesOf(t: JSONObject) = t.optString("scope").split(' ', '+').filter { it.isNotBlank() }

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    internal fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000 }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Responses.HttpError(code, text)
            return text
        } finally { conn.disconnect() }
    }

    private fun postForm(url: String, form: Map<String, String>, allowEmpty: Boolean = false): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { out -> out.write(form.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Responses.HttpError(code, text)
            return if (text.isBlank() && allowEmpty) JSONObject() else JSONObject(text)
        } finally { conn.disconnect() }
    }

    /** ID token checks: RS256 signature against OpenAI's JWKS, issuer, audience, expiry, nonce. */
    private object IdToken {
        fun verify(jwt: String, clientId: String, nonce: String): JSONObject {
            val parts = jwt.split('.')
            if (parts.size != 3) throw PlanException("Bad ID token")
            val dec = Base64.getUrlDecoder()
            val header = JSONObject(String(dec.decode(parts[0])))
            val claims = JSONObject(String(dec.decode(parts[1])))
            if (header.optString("alg") != "RS256") throw PlanException("Unexpected ID token algorithm")

            val config = JSONObject(get("$AUTH/.well-known/openid-configuration"))
            val keys = JSONObject(get(config.getString("jwks_uri"))).getJSONArray("keys")
            val kid = header.optString("kid")
            val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }
                .firstOrNull { it.optString("kid") == kid && it.optString("kty") == "RSA" }
                ?: throw PlanException("ID token key not found")
            val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
                BigInteger(1, dec.decode(jwk.getString("n"))), BigInteger(1, dec.decode(jwk.getString("e"))),
            ))
            val ok = Signature.getInstance("SHA256withRSA").run {
                initVerify(key); update("${parts[0]}.${parts[1]}".toByteArray()); verify(dec.decode(parts[2]))
            }
            if (!ok) throw PlanException("ID token signature invalid")

            val issuer = config.optString("issuer", AUTH)
            if (claims.optString("iss").trimEnd('/') != issuer.trimEnd('/')) throw PlanException("ID token issuer mismatch")
            val aud = claims.opt("aud")
            val audOk = when (aud) {
                is String -> aud == clientId
                is JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
                else -> false
            }
            if (!audOk) throw PlanException("ID token audience mismatch")
            if (claims.optLong("exp") * 1000 < System.currentTimeMillis() - 60_000) throw PlanException("ID token expired")
            if (claims.optString("nonce") != nonce) throw PlanException("ID token nonce mismatch")
            if (claims.optString("sub").isEmpty()) throw PlanException("ID token has no subject")
            return claims
        }
    }
}

/** PKCE S256 (RFC 7636). */
object Pkce {
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
