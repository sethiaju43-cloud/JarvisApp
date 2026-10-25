package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.os.StatFs
import androidx.core.content.ContextCompat
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Model ko internal storage (filesDir/models) mein download karta hai: resume, progress, cancel */
object ModelDownloader {

    class State(
        @Volatile var downloaded: Long,
        @Volatile var total: Long,
        @Volatile var status: String,   // running | done | error | cancelled
        @Volatile var error: String
    )

    private val states = ConcurrentHashMap<String, State>()

    @Volatile
    var activeId: String? = null

    @Volatile
    private var cancelFlag = false

    fun modelsDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "models").apply { mkdirs() }

    fun fileFor(ctx: Context, m: LocalModel) = File(modelsDir(ctx), m.fileName)
    fun partFor(ctx: Context, m: LocalModel) = File(modelsDir(ctx), m.fileName + ".part")

    fun isInstalled(ctx: Context, m: LocalModel): Boolean {
        val f = fileFor(ctx, m)
        return f.exists() && f.length() > 0
    }

    fun partialBytes(ctx: Context, m: LocalModel): Long {
        val p = partFor(ctx, m)
        return if (p.exists()) p.length() else 0L
    }

    fun state(id: String): State? = states[id]

    fun cancel() {
        cancelFlag = true
    }

    fun delete(ctx: Context, m: LocalModel) {
        if (LocalLlm.loadedModelId() == m.id) LocalLlm.unload("model hata diya")
        fileFor(ctx, m).delete()
        partFor(ctx, m).delete()
        states.remove(m.id)
    }

    fun totalInstalledMb(ctx: Context): Long {
        var sum = 0L
        modelsDir(ctx).listFiles()?.forEach { sum += it.length() }
        return sum / (1024 * 1024)
    }

    /** null = shuru ho gaya, warna error message */
    fun start(ctx: Context, m: LocalModel): String? {
        if (activeId != null) return "Ek download pehle se chal raha hai. Use khatam ya cancel karo."
        if (!NetworkMonitor.isOnline(ctx)) return "Internet nahi hai. Download ke liye WiFi chahiye."
        val snap = DeviceInfo.snapshot(ctx)
        val need = if (m.sizeMb > 0) m.sizeMb - (partialBytes(ctx, m) / (1024 * 1024)).toInt() + 300 else 500
        if (snap.freeStorageMb < need) {
            return "Storage kam hai: " + snap.freeStorageMb + " MB khali, ~" + need + " MB chahiye."
        }
        activeId = m.id
        cancelFlag = false
        states[m.id] = State(partialBytes(ctx, m), if (m.sizeMb > 0) m.sizeMb * 1024L * 1024L else 0L, "running", "")
        try {
            ContextCompat.startForegroundService(
                ctx.applicationContext,
                Intent(ctx.applicationContext, ModelDownloadService::class.java).putExtra("id", m.id)
            )
        } catch (e: Exception) {
            activeId = null
            states[m.id]?.status = "error"
            return "Download service shuru nahi hui: " + (e.message ?: "")
        }
        return null
    }

    /** Peeche ke thread par chalta hai (service se). onProgress ~1 second mein ek baar. */
    fun run(ctx: Context, m: LocalModel, onProgress: (State) -> Unit) {
        val st = states.getOrPut(m.id) { State(0, 0, "running", "") }
        st.status = "running"
        st.error = ""
        try {
            val part = partFor(ctx, m)
            val dest = fileFor(ctx, m)
            var attempt = 0
            while (attempt < 2) {
                attempt++
                var have = if (part.exists()) part.length() else 0L
                val rb = Request.Builder().url(m.url)
                val tok = ApiKeyStore.getSecret(ctx, "hf_token")
                if (tok.isNotBlank() && m.url.contains("huggingface.co")) {
                    rb.header("Authorization", "Bearer $tok")
                }
                if (have > 0) rb.header("Range", "bytes=$have-")
                var retry = false
                Net.download.newCall(rb.build()).execute().use { resp ->
                    if (resp.code == 416) {
                        part.delete()
                        retry = true
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        st.status = "error"
                        st.error = when (resp.code) {
                            401, 403 -> "Ye model gated hai. HuggingFace par license accept karo aur Models screen mein token daalo."
                            404 -> "File nahi mili (URL galat ya hata di gayi)."
                            else -> "Server error " + resp.code
                        }
                        return
                    }
                    val append = resp.code == 206
                    if (!append) have = 0L
                    val body = resp.body
                    if (body == null) {
                        st.status = "error"
                        st.error = "Khali jawab aaya."
                        return
                    }
                    val len = body.contentLength()
                    val total = if (len > 0) (if (append) have + len else len) else st.total
                    st.total = total
                    if (len > 0) {
                        val free = StatFs(modelsDir(ctx).path).availableBytes
                        if (free < len + 200L * 1024 * 1024) {
                            st.status = "error"
                            st.error = "Storage kam pad gaya. Jagah khali karke dobara dabao."
                            return
                        }
                    }
                    var done = have
                    st.downloaded = done
                    var lastTick = 0L
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        val inp = body.byteStream()
                        while (true) {
                            if (cancelFlag) {
                                st.status = "cancelled"
                                return
                            }
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            st.downloaded = done
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 1000) {
                                lastTick = now
                                onProgress(st)
                            }
                        }
                        out.flush()
                    }
                    if (total > 0 && part.length() != total) {
                        st.status = "error"
                        st.error = "Download adhoora raha. Dobara dabao, wahin se resume hoga."
                        return
                    }
                    if (dest.exists()) dest.delete()
                    if (!part.renameTo(dest)) {
                        st.status = "error"
                        st.error = "File ka naam badalne mein dikkat."
                        return
                    }
                    st.status = "done"
                }
                if (!retry) break
            }
        } catch (e: Exception) {
            st.status = "error"
            st.error = "Internet ruk gaya (" + OnlineParsers.redact(e.message ?: "") + "). Dobara dabao, wahin se resume hoga."
        } finally {
            activeId = null
            onProgress(st)
        }
    }

    /** Server par file ka size local se alag ho to "update mila" */
    fun checkUpdate(ctx: Context, m: LocalModel): String {
        if (!NetworkMonitor.isOnline(ctx)) return "Internet nahi hai"
        if (!isInstalled(ctx, m)) return "Model installed nahi hai"
        return try {
            val rb = Request.Builder().url(m.url).head()
            val tok = ApiKeyStore.getSecret(ctx, "hf_token")
            if (tok.isNotBlank() && m.url.contains("huggingface.co")) rb.header("Authorization", "Bearer $tok")
            Net.api.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) "Check nahi ho paya (code " + r.code + ")"
                else {
                    val remote = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    val local = fileFor(ctx, m).length()
                    when {
                        remote <= 0 -> "Server ne size nahi bataya, check nahi ho paya"
                        remote != local -> "Naya version mila (size badla). 'Update' dabao."
                        else -> "Aapka model latest hai"
                    }
                }
            }
        } catch (e: Exception) {
            "Check nahi ho paya: " + OnlineParsers.redact(e.message ?: "")
        }
    }
}
