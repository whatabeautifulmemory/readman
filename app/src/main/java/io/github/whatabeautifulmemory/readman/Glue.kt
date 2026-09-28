// Android-side glue: settings persistence, image prep, contact insertion, and the work queue.
package io.github.whatabeautifulmemory.readman

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ln

// ---------------------------------------------------------------- settings

/** Thin SharedPreferences wrapper. Keys/endpoints/models are per provider so switching keeps them. */
class Settings(private val ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("readman", Context.MODE_PRIVATE)

    var providerId: String
        get() = p.getString("provider", PROVIDERS.first().id)!!
        set(v) = p.edit().putString("provider", v).apply()
    val provider: Provider get() = providerById(providerId)

    fun key(id: String) = p.getString("key.$id", "")!!
    fun setKey(id: String, v: String) = p.edit().putString("key.$id", v.trim()).apply()

    fun endpoint(id: String): String {
        val def = providerById(id)
        val o = if (def.endpointEditable) p.getString("endpoint.$id", "")!!.trim() else ""
        return o.ifBlank { def.baseURL }
    }
    fun setEndpoint(id: String, v: String) = p.edit().putString("endpoint.$id", v.trim()).apply()

    fun model(id: String) = p.getString("model.$id", "")!!.ifBlank { providerById(id).models.firstOrNull() ?: "" }
    fun setModel(id: String, v: String) = p.edit().putString("model.$id", v.trim()).apply()

    var systemPrompt: String
        get() = p.getString("prompt", DEFAULT_SYSTEM_PROMPT)!!
        set(v) = p.edit().putString("prompt", v).apply()

    var llmMapping: Boolean
        get() = p.getBoolean("llm_mapping", false)
        set(v) = p.edit().putBoolean("llm_mapping", v).apply()

    /** Viewfinder frame orientation — Japanese cards are often portrait (縦型). Sticky across sessions. */
    var cardPortrait: Boolean
        get() = p.getBoolean("card_portrait", false)
        set(v) = p.edit().putBoolean("card_portrait", v).apply()

    /**
     * "system" | "en" | "ko" | "ja". On Android 13+ the OS per-app language (App info → Language) is the
     * single source of truth, so the in-app picker and that screen never disagree; below 13 it is a
     * pref that attachBaseContext applies.
     */
    var language: String
        get() = if (Build.VERSION.SDK_INT >= 33)
            ctx.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language ?: "system"
        else p.getString("language", "system")!!
        set(v) {
            if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (v == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(v)
            else p.edit().putString("language", v).apply()
        }

    /** "system" | "light" | "dark" */
    var theme: String
        get() = p.getString("theme", "system")!!
        set(v) = p.edit().putString("theme", v).apply()

    fun template(field: String) = p.getString("tpl.$field", DEFAULT_TEMPLATES[field])!!
    fun setTemplate(field: String, v: String) = p.edit().putString("tpl.$field", v).apply()
    fun templates(): Map<String, String> = CONTACT_FIELDS.associateWith { template(it) }
}

/** A context whose resources speak [lang]. On 13+ the OS already did this for every context. */
fun Context.localized(lang: String): Context = if (lang == "system" || Build.VERSION.SDK_INT >= 33) this
    else createConfigurationContext(Configuration(resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(lang))) })

/** Localized text for anything analyze()/saveAll() can throw. */
fun Context.errorText(e: Throwable): String = when (e) {
    is LlmError -> when (e.code) {
        "save_failed" -> getString(R.string.err_save_failed, e.arg)
        "no_model" -> getString(R.string.err_no_model)
        "no_key" -> getString(R.string.err_no_key, e.arg)
        "http" -> getString(R.string.err_http, e.arg)
        "bad_response" -> getString(R.string.err_bad_response, e.arg)
        "no_json" -> getString(R.string.err_no_json, e.arg)
        "truncated" -> getString(R.string.err_truncated)
        "bad_key" -> getString(R.string.err_bad_key)
        else -> e.message ?: e.code
    }
    else -> e.message ?: e.toString()
}

// ---------------------------------------------------------------- images

/** Decodes with EXIF rotation applied, capped at [maxEdge] px on the long side. */
fun decodeScaled(ctx: Context, uri: Uri, maxEdge: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
        val (w, h) = info.size.width to info.size.height
        val scale = maxEdge.toFloat() / maxOf(w, h)
        if (scale < 1f) dec.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

/** Business card, 90×54 mm (ISO 7810 ID-1 is 1.586; either is close enough for a framing guide). */
const val CARD_ASPECT = 90f / 54f

/**
 * Crops a fresh capture to the viewfinder frame, in place. [frame] is in fractions of the preview
 * view; the preview shows the image FILL_CENTER-cropped to [viewAspect] (w/h), so the frame is mapped
 * through that visible region. Works whether or not CameraX already cropped the JPEG to the viewport.
 */
fun cropToFrame(file: File, frame: RectF, viewAspect: Float) {
    if (!viewAspect.isFinite() || viewAspect <= 0f) return
    val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
        // 48 MP phones: half-size is still ~12 MP, far above what the card region needs.
        if (info.size.width.toLong() * info.size.height > 16_000_000L) d.setTargetSampleSize(2)
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val w = src.width.toFloat(); val h = src.height.toFloat()
    // CameraController rotates the JPEG (EXIF) to the accelerometer orientation, while the preview and this
    // frame follow the display rotation. With rotation lock, or a phone tilted down at a desk (>25° stops
    // auto-rotate but not the accelerometer listener), the decoded bitmap is the view's TRANSPOSE. The JPEG
    // is always the view aspect or its transpose, so pick the nearer in log space (square view → upright);
    // the frame is centred on both axes, so swapping its axes is the same region for 90° and 270°.
    val transposed = abs(ln(w / h / viewAspect)) > abs(ln(w / h * viewAspect))
    val fr = if (transposed) RectF(frame.top, frame.left, frame.bottom, frame.right) else frame
    val va = if (transposed) 1f / viewAspect else viewAspect
    val (vw, vh) = if (w / h > va) h * va to h else w to w / va
    val vx = (w - vw) / 2; val vy = (h - vh) / 2
    val l = (vx + fr.left * vw).toInt().coerceIn(0, src.width - 2)
    val t = (vy + fr.top * vh).toInt().coerceIn(0, src.height - 2)
    val cw = (fr.width() * vw).toInt().coerceIn(1, src.width - l)
    val ch = (fr.height() * vh).toInt().coerceIn(1, src.height - t)
    val out = Bitmap.createBitmap(src, l, t, cw, ch)
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
}

fun jpegBase64(bmp: Bitmap, quality: Int = 85): String {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Base64.getEncoder().encodeToString(out.toByteArray())
}

// ---------------------------------------------------------------- contacts

private val PHONE_TYPES = mapOf(
    "phone_work" to Phone.TYPE_WORK, "phone_mobile" to Phone.TYPE_MOBILE, "phone_fax" to Phone.TYPE_FAX_WORK,
)

/** One raw contact (no account → device-local) with one Data row per non-blank field. */
fun insertContact(cr: ContentResolver, c: Map<String, String>) {
    val ops = arrayListOf<ContentProviderOperation>()
    ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
        .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build()
    fun row(mime: String, vararg kv: Pair<String, String>) {
        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, mime)
            .apply { kv.forEach { (k, v) -> withValue(k, v) } }.build()
    }
    fun v(k: String) = c[k]?.trim().orEmpty()

    v("display_name").takeIf { it.isNotEmpty() }?.let { row(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to it) }
    if (listOf("company", "department", "job_title").any { v(it).isNotEmpty() }) {
        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
            .withValue(Organization.TYPE, Organization.TYPE_WORK)
            .withValue(Organization.COMPANY, v("company").ifEmpty { null })
            .withValue(Organization.DEPARTMENT, v("department").ifEmpty { null })
            .withValue(Organization.TITLE, v("job_title").ifEmpty { null }).build()
    }
    PHONE_TYPES.forEach { (f, type) ->
        v(f).takeIf { it.isNotEmpty() }?.let {
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, it).withValue(Phone.TYPE, type).build()
        }
    }
    v("email").takeIf { it.isNotEmpty() }?.let {
        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, it).withValue(Email.TYPE, Email.TYPE_WORK).build()
    }
    v("address").takeIf { it.isNotEmpty() }?.let {
        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0).withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
            .withValue(StructuredPostal.FORMATTED_ADDRESS, it).withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK).build()
    }
    v("website").takeIf { it.isNotEmpty() }?.let { row(Website.CONTENT_ITEM_TYPE, Website.URL to it) }
    v("note").takeIf { it.isNotEmpty() }?.let { row(Note.CONTENT_ITEM_TYPE, Note.NOTE to it) }
    cr.applyBatch(ContactsContract.AUTHORITY, ops)
}

// ---------------------------------------------------------------- queue

/** NEW = captured/picked, waiting for the user to look at it and press 분석; nothing has been spent on it yet. */
enum class Status { NEW, PENDING, RUNNING, DONE, ERROR, SAVED }

class CardItem(val uri: Uri) {
    companion object { private var seq = 0 }
    val id = seq++
    var status by mutableStateOf(Status.NEW)
    /** Kept as the exception, not text, so a language switch re-localizes it at display time. */
    var error by mutableStateOf<Throwable?>(null)
    var thumb by mutableStateOf<Bitmap?>(null)
    var card by mutableStateOf<Map<String, String>>(emptyMap())
    /** Editable in the UI before saving; this is exactly what goes into the address book. */
    val contact = mutableStateMapOf<String, String>()
    /** True once the user touched a field — remap() then leaves this item alone. */
    var edited = false
    /** What the LLM decided in LLM-mapping mode, kept so switching modes back restores it. */
    var llmContact: Map<String, String>? = null
    var raw = ""
    val savable: Boolean get() = status == Status.DONE && contact.values.any { it.isNotBlank() }
    val summary: String get() = listOf(contact["display_name"], contact["company"]).filter { !it.isNullOrBlank() }.joinToString(" · ")
}

class AppVm(app: Application) : AndroidViewModel(app) {
    val items = mutableStateListOf<CardItem>()
    val settings = Settings(app)
    private val llm = LlmClient()
    // ponytail: fixed 2-wide; per-provider rate limits differ but this is the safe floor everywhere.
    private val slots = Semaphore(2)
    // Thumbnails decode before an LLM slot is free so the list fills in early — but bounded, or a
    // 50-photo pick starts 50 decoders (HEIC ones each hold a hardware codec).
    private val thumbs = Semaphore(3)
    var saving by mutableStateOf(false)
        private set
    /** Mirrors Settings.theme as state so the Activity re-themes the moment it changes. */
    var theme by mutableStateOf(settings.theme)
        private set
    fun chooseTheme(v: String) { settings.theme = v; theme = v }
    private val camDir = File(app.cacheDir, "cam")

    init {
        // Captured JPEGs are PII; anything older than a day is an orphan from a killed process.
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        camDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Queues only — a bad shot costs nothing until the user approves the batch with analyzeNew(). */
    fun add(uris: List<Uri>) = uris.forEach { u ->
        val item = CardItem(u).also { items += it }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { thumbs.withPermit { item.thumb = decodeScaled(getApplication(), u, 256) } }
                .onFailure { item.error = it; item.status = Status.ERROR }
        }
    }

    /** A capture goes through the frame crop first so nothing outside the card ever reaches a model. */
    fun addCapture(file: File, frame: RectF, viewAspect: Float, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { cropToFrame(file, frame, viewAspect) }
            val ctx = getApplication<Application>()
            add(listOf(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)))
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    val newCount: Int get() = items.count { it.status == Status.NEW }

    fun analyzeNew() = items.filter { it.status == Status.NEW }.forEach { analyze(it) }

    /** Deletes the camera capture behind an item (gallery picks are not ours to delete). */
    fun discard(uri: Uri) {
        if (uri.authority == "${getApplication<Application>().packageName}.fileprovider")
            uri.lastPathSegment?.let { File(camDir, it).delete() }
    }

    fun analyze(item: CardItem) {
        item.status = Status.PENDING; item.error = null
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                if (item.thumb == null) thumbs.withPermit { item.thumb = decodeScaled(ctx, item.uri, 256) }
                slots.withPermit {
                    item.status = Status.RUNNING
                    val s = settings; val p = s.provider
                    val b64 = jpegBase64(decodeScaled(ctx, item.uri, 1600))
                    val a = llm.analyze(p, s.endpoint(p.id), s.key(p.id), s.model(p.id),
                        s.systemPrompt, s.llmMapping, s.templates(), b64)
                    item.card = a.card; item.raw = a.raw
                    item.llmContact = if (a.llmDecided) a.contact else null
                    item.contact.clear(); item.contact.putAll(a.contact); item.edited = false
                    item.status = Status.DONE
                }
            } catch (e: Exception) {
                item.error = e; item.status = Status.ERROR
            }
        }
    }

    /**
     * Re-derives contacts when the mapping mode or a template changes — no new LLM call. Skips items
     * the user edited; an item analysed in template mode has no LLM contact to switch to, so it keeps
     * its template result until re-analysed.
     */
    fun remap() = items.filter { it.status == Status.DONE && !it.edited }.forEach {
        val m = if (settings.llmMapping) it.llmContact ?: return@forEach else contactFromTemplates(it.card, settings.templates())
        it.contact.clear(); it.contact.putAll(m)
    }

    fun remove(item: CardItem) { items -= item; discard(item.uri) }

    /** Returns how many were written. Items stay in the list, marked SAVED, so the user sees what landed. */
    @Synchronized fun saveAll(): Int {
        if (saving) return 0
        saving = true
        try {
            val cr = getApplication<Application>().contentResolver
            var n = 0
            items.filter { it.savable }.forEach {
                // A failed insert keeps the item DONE so the next 저장 tap simply retries it.
                try { insertContact(cr, it.contact); it.error = null; it.status = Status.SAVED; n++ }
                catch (e: Exception) { it.error = LlmError("save_failed", e.message ?: e.toString()) }
            }
            return n
        } finally { saving = false }
    }

    fun clearSaved() { items.filter { it.status == Status.SAVED }.forEach { discard(it.uri) }; items.removeAll { it.status == Status.SAVED } }
}
