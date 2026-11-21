package io.rustray.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.DatePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.text.method.LinkMovementMethod
import android.widget.CheckBox
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import org.xrayrust.mobile.XrayProfileFormat
import org.xrayrust.mobile.XrayProfileImporter
import java.net.URI
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private lateinit var store: ProfileStore
    private lateinit var adapter: ProfileAdapter

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileServer: TextView
    private lateinit var tvError: TextView
    private lateinit var tvUpRate: TextView
    private lateinit var tvUpTotal: TextView
    private lateinit var tvDownRate: TextView
    private lateinit var tvDownTotal: TextView
    private lateinit var tvConnections: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var fabAdd: FloatingActionButton

    private var selected: Profile? = null
    private var cachedProfiles: List<Profile> = emptyList()
    private var pendingStartId: String? = null
    private var notificationAsked = false
    private var lastClipboardPrompt: String? = null

    private var lastUpTotal = -1L
    private var lastDownTotal = -1L
    private var lastSampleAt = 0L
    private var lastPhase = VpnPhase.STOPPED

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000)
        }
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val id = pendingStartId
        if (result.resultCode == RESULT_OK && id != null) {
            requestNotificationThenStart(id)
        } else {
            pendingStartId = null
            toast("VPN permission was not granted")
        }
    }

    private val openRrLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importRrFromUri(uri)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startPendingVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedLang = SettingsStore(this).load().language.tag
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedLang))
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ProfileStore(this)

        bindViews()
        applyInsets()

        adapter = ProfileAdapter(
            onSelect = { profile -> selectProfile(profile) },
            onEdit = { profile -> openEditor(profile) },
            onShare = { profile -> showExportRrDialog(profile) },
            onDelete = { profile -> confirmDelete(profile) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> {
                    showImportChooser()
                    true
                }

                R.id.action_logs -> {
                    showLogs()
                    true
                }

                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }

        fabAdd.setOnClickListener { openEditor(null) }
        btnConnect.setOnClickListener { onConnectClicked() }
        btnImport.setOnClickListener { showImportChooser() }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        tvProfileName = findViewById(R.id.tvProfileName)
        tvProfileServer = findViewById(R.id.tvProfileServer)
        tvError = findViewById(R.id.tvError)
        tvUpRate = findViewById(R.id.tvUpRate)
        tvUpTotal = findViewById(R.id.tvUpTotal)
        tvDownRate = findViewById(R.id.tvDownRate)
        tvDownTotal = findViewById(R.id.tvDownTotal)
        tvConnections = findViewById(R.id.tvConnections)
        btnConnect = findViewById(R.id.btnConnect)
        btnImport = findViewById(R.id.btnImport)
        recycler = findViewById(R.id.recyclerProfiles)
        tvEmpty = findViewById(R.id.tvEmpty)
        fabAdd = findViewById(R.id.fabAdd)
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        refreshUi()
        maybeHandleAutoConnect(intent)
        maybeHandleIncomingRr(intent)
        maybeOfferClipboardImport()
    }

    private fun refreshProfiles() {
        val profiles = store.load()
        cachedProfiles = profiles
        var selectedId = store.selectedId()
        val isVpnActive = VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING
        val activeId = VpnState.profileId?.takeIf { id -> isVpnActive && profiles.any { it.id == id } }
        if (activeId != null) {
            selectedId = activeId
            if (store.selectedId() != activeId) {
                store.select(activeId)
            }
        }
        if (selectedId != null && profiles.none { it.id == selectedId }) {
            selectedId = null
        }
        if (selectedId == null && profiles.isNotEmpty()) {
            selectedId = profiles.first().id
            store.select(selectedId)
        }
        selected = profiles.firstOrNull { it.id == selectedId }
        adapter.submit(profiles, selectedId)
        tvEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        RustRayWidgetProvider.refreshAll(this)
        TileSync.requestUpdates(this)
    }

    private fun selectProfile(profile: Profile) {
        if (VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING) {
            toast(getString(R.string.toast_disconnect_before_switch))
            refreshProfiles()
            return
        }
        store.select(profile.id)
        refreshProfiles()
        refreshUi()
    }

    private fun openEditor(profile: Profile?) {
        startActivity(
            Intent(this, EditProfileActivity::class.java).apply {
                profile?.let { putExtra(EditProfileActivity.EXTRA_PROFILE_ID, it.id) }
            },
        )
    }

    private fun confirmDelete(profile: Profile) {
        if (AppVpnService.instance != null &&
            (VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING) &&
            VpnState.profileId == profile.id
        ) {
            toast(getString(R.string.toast_disconnect_before_delete))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(profile.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(profile.id)
                refreshProfiles()
                refreshUi()
            }
            .show()
    }

    private fun onConnectClicked() {
        when (VpnState.phase) {
            VpnPhase.CONNECTED, VpnPhase.CONNECTING -> {
                startService(Intent(this, AppVpnService::class.java).setAction(AppVpnService.ACTION_STOP))
            }

            VpnPhase.STOPPING -> Unit

            else -> {
                val profile = selected
                if (profile == null) {
                    toast(getString(R.string.toast_add_profile_first))
                    return
                }
                if (profile.isExpired()) {
                    toast(getString(R.string.err_config_expired))
                    return
                }
                beginConnect(profile)
            }
        }
    }

    private fun beginConnect(profile: Profile) {
        pendingStartId = profile.id
        val consent = VpnService.prepare(this)
        if (consent != null) {
            consentLauncher.launch(consent)
        } else {
            requestNotificationThenStart(profile.id)
        }
    }

    private fun requestNotificationThenStart(id: String) {
        pendingStartId = id
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationAsked
        ) {
            notificationAsked = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPendingVpn()
        }
    }

    private fun startPendingVpn() {
        val id = pendingStartId
        pendingStartId = null
        if (id == null) return
        val intent = Intent(this, AppVpnService::class.java)
            .setAction(AppVpnService.ACTION_CONNECT)
            .putExtra(AppVpnService.EXTRA_PROFILE_ID, id)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshUi() {
        val phase = VpnState.phase
        val color = ContextCompat.getColor(
            this,
            when (phase) {
                VpnPhase.CONNECTED -> R.color.primary
                VpnPhase.CONNECTING, VpnPhase.STOPPING -> R.color.warning
                VpnPhase.ERROR -> R.color.error
                VpnPhase.STOPPED -> R.color.text_dim
            },
        )
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        tvStatus.text = when (phase) {
            VpnPhase.STOPPED -> getString(R.string.status_disconnected)
            VpnPhase.CONNECTING -> getString(R.string.status_connecting)
            VpnPhase.CONNECTED -> getString(R.string.status_connected)
            VpnPhase.STOPPING -> getString(R.string.status_disconnecting)
            VpnPhase.ERROR -> getString(R.string.status_failed)
        }
        tvStatus.setTextColor(color)

        val current = cachedProfiles.firstOrNull { it.id == VpnState.profileId } ?: selected
        val name = when (phase) {
            VpnPhase.STOPPED -> selected?.name
            else -> VpnState.profileName.ifBlank { selected?.name }
        }
        tvProfileName.text = name ?: getString(R.string.no_profile_selected)
        tvProfileServer.text = when {
            phase == VpnPhase.STOPPED -> current?.let { "${it.protocol.label} · ${it.endpoint()}" }
                ?: getString(R.string.add_or_import_hint)

            VpnState.server.isNotBlank() -> {
                val label = current?.protocol?.label
                if (label != null) "$label · ${VpnState.server}" else VpnState.server
            }

            else -> current?.let { "${it.protocol.label} · ${it.endpoint()}" }
                ?: getString(R.string.add_or_import_hint)
        }

        val error = VpnState.error
        if (phase == VpnPhase.ERROR && !error.isNullOrBlank()) {
            tvError.text = error
            tvError.visibility = View.VISIBLE
        } else {
            tvError.visibility = View.GONE
        }

        tvDuration.text = if (phase == VpnPhase.CONNECTED && VpnState.startedAtElapsed > 0) {
            formatDuration(SystemClock.elapsedRealtime() - VpnState.startedAtElapsed)
        } else {
            "00:00:00"
        }

        val running = phase == VpnPhase.CONNECTED
        val active = running || phase == VpnPhase.CONNECTING
        btnConnect.text = when {
            phase == VpnPhase.STOPPING -> getString(R.string.status_disconnecting)
            running || phase == VpnPhase.CONNECTING -> getString(R.string.disconnect)
            else -> getString(R.string.connect)
        }
        btnConnect.isEnabled = phase != VpnPhase.STOPPING
        btnConnect.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (active) R.color.error else R.color.primary),
        )
        btnConnect.setTextColor(
            ContextCompat.getColor(this, if (active) android.R.color.white else R.color.on_primary),
        )

        if (phase != lastPhase) {
            if (running) {
                lastUpTotal = -1L
                lastDownTotal = -1L
                lastSampleAt = 0L
            }
            lastPhase = phase
        }

        if (!running) {
            resetTraffic()
            return
        }

        val snapshot = runCatching { AppVpnService.instance?.snapshot() }.getOrNull()
        val stats = snapshot?.tunStats
        if (stats == null) {
            resetTraffic()
            return
        }

        val up = stats.uplinkBytes
        val down = stats.downlinkBytes
        val now = SystemClock.elapsedRealtime()
        if (lastUpTotal < 0 || lastDownTotal < 0 || now - lastSampleAt > 5000) {
            tvUpRate.text = formatRate(0)
            tvDownRate.text = formatRate(0)
        } else if (lastSampleAt > 0) {
            val elapsedMs = (now - lastSampleAt).coerceAtLeast(1)
            val upRate = ((up - lastUpTotal).coerceAtLeast(0) * 1000) / elapsedMs
            val downRate = ((down - lastDownTotal).coerceAtLeast(0) * 1000) / elapsedMs
            tvUpRate.text = formatRate(upRate)
            tvDownRate.text = formatRate(downRate)
        }
        lastUpTotal = up
        lastDownTotal = down
        lastSampleAt = now

        tvUpTotal.text = formatBytes(up)
        tvDownTotal.text = formatBytes(down)
        tvConnections.text = getString(R.string.active_connections_fmt, snapshot?.activeConnections ?: 0)
    }

    private fun resetTraffic() {
        lastUpTotal = -1L
        lastDownTotal = -1L
        lastSampleAt = 0L
        tvUpRate.text = formatRate(0)
        tvDownRate.text = formatRate(0)
        tvUpTotal.text = formatBytes(0)
        tvDownTotal.text = formatBytes(0)
        tvConnections.text = getString(R.string.active_connections_zero)
    }

    private fun importFromClipboard() {
        val link = readClipboardLink()
        if (link == null) {
            toast(getString(R.string.toast_clipboard_no_link))
            return
        }
        lastClipboardPrompt = link
        importLink(link)
    }

    private fun maybeOfferClipboardImport() {
        if (VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING) return
        val link = readClipboardLink() ?: return
        if (link == lastClipboardPrompt) return
        lastClipboardPrompt = link
        val details = runCatching { previewOf(link) }.getOrNull()
            ?: "${link.substringBefore("://").uppercase()} share link"
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_import_title)
            .setMessage("$details\n\n${getString(R.string.dialog_import_supported)}")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.import_action) { _, _ -> importLink(link) }
            .show()
    }

    private fun readClipboardLink(): String? {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return null
        return Regex("(?:vless|vmess|trojan)://\\S+", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
    }

    private fun previewOf(link: String): String {
        val uri = URI(link)
        val host = uri.host?.removeSurrounding("[", "]")
            ?: throw IllegalArgumentException("Link has no host")
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val name = uri.rawFragment
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.ifBlank { null }
            ?: "$host:$port"
        val scheme = uri.scheme?.uppercase() ?: "LINK"
        val warning = if (scheme == "VLESS" && PlaintextVlessCompat.prepare(link).plaintext) {
            "\n\nNo TLS: this link is not encrypted."
        } else {
            ""
        }
        return "$name\n$scheme · $host:$port$warning"
    }

    private fun importLink(link: String) {
        try {
            val profile = buildImportedProfile(link)
            store.upsert(profile)
            val isVpnActive = VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING
            if (!isVpnActive || store.selectedId() == null) {
                store.select(profile.id)
            }
            refreshProfiles()
            refreshUi()
            val msg = if (isVpnActive) getString(R.string.toast_imported_active, profile.name) else getString(R.string.toast_imported, profile.name)
            toast(msg)
        } catch (error: Throwable) {
            val detail = error.message?.lineSequence()?.firstOrNull()?.take(200)
                ?: error.javaClass.simpleName
            toast(getString(R.string.toast_import_failed, detail))
        }
    }

    private fun buildImportedProfile(link: String): Profile {
        val format = when {
            link.startsWith("vless://", ignoreCase = true) -> XrayProfileFormat.Vless
            link.startsWith("vmess://", ignoreCase = true) -> XrayProfileFormat.Vmess
            link.startsWith("trojan://", ignoreCase = true) -> XrayProfileFormat.Trojan
            else -> throw IllegalArgumentException("Unsupported link scheme")
        }

        if (format == XrayProfileFormat.Vless) {
            val prepared = PlaintextVlessCompat.prepare(link)
            val imported = XrayProfileImporter.profile(prepared.importLink, format)
            val config = if (prepared.alias != null && prepared.target != null) {
                PlaintextVlessCompat.injectHosts(
                    configJson = imported.configJson,
                    alias = prepared.alias,
                    target = prepared.target,
                    originalHost = prepared.originalHost,
                )
            } else {
                imported.configJson
            }
            return ConfigBuilder.hydrateFromImportedConfig(
                Profile(
                    name = prepared.name.ifBlank { prepared.originalHost },
                    protocol = Protocol.VLESS,
                    server = prepared.originalHost,
                    port = prepared.port,
                    credential = "",
                    security = Security.fromWire(prepared.security),
                    network = Network.fromWire(prepared.network),
                    importedConfig = config,
                    plaintext = prepared.plaintext,
                ),
            )
        }

        val imported = XrayProfileImporter.profile(link, format)
        return ConfigBuilder.hydrateFromImportedConfig(
            Profile(
                name = imported.name.ifBlank { imported.serverAddress },
                protocol = if (format == XrayProfileFormat.Vmess) Protocol.VMESS else Protocol.TROJAN,
                server = imported.serverAddress,
                port = extractPort(link),
                credential = "",
                importedConfig = imported.configJson,
            ),
        )
    }

    private fun extractPort(link: String): Int {
        if (link.startsWith("vmess://", ignoreCase = true)) {
            val payload = link.substringAfter("://").substringBefore('#').trim()
            val decoders = listOf(
                Base64.DEFAULT or Base64.NO_WRAP or Base64.NO_PADDING,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            for (flags in decoders) {
                runCatching {
                    val decoded = String(Base64.decode(payload, flags), Charsets.UTF_8)
                    return JSONObject(decoded).optString("port").toIntOrNull() ?: 443
                }
            }
            return 443
        }
        val match = Regex("://(?:[^@/?#]*@)?(?:\\[[^\\]]+\\]|[^:/?#]+):(\\d{1,5})")
            .find(link)
        return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
    }

    private fun showLogs() {
        val density = resources.displayMetrics.density
        val text = TextView(this).apply {
            val sshStats = RustSshBridge.statsSummary()
            text = if (sshStats.isNotBlank()) "$sshStats\n\n${EventLog.dump()}" else EventLog.dump()
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setTextColor(ContextCompat.getColor(context, R.color.text))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(R.string.logs)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshProfiles()
        refreshUi()
        maybeHandleAutoConnect(intent)
        maybeHandleIncomingRr(intent)
    }

    private fun maybeHandleAutoConnect(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true) {
            intent.removeExtra(EXTRA_AUTO_CONNECT)
            if (VpnState.phase == VpnPhase.STOPPED || VpnState.phase == VpnPhase.ERROR) {
                onConnectClicked()
            }
        }
    }


    private fun showImportChooser() {
        val options = arrayOf(
            getString(R.string.import_from_clipboard),
            getString(R.string.import_rr_file),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.import_chooser_title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    importFromClipboard()
                } else {
                    openRrLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun maybeHandleIncomingRr(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data = null
            importRrFromUri(uri)
        }
    }

    private fun importRrFromUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Cannot read file")
            val displayName = resolveFileName(uri) ?: "config.rr"
            val result = RrConfigCodec.importFromBytes(bytes, displayName)
            store.upsert(result.profile)
            val isVpnActive = VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING
            if (!isVpnActive || store.selectedId() == null) {
                store.select(result.profile.id)
            }
            refreshProfiles()
            refreshUi()
            val msg = if (isVpnActive) {
                getString(R.string.toast_imported_active, result.profile.name)
            } else {
                getString(R.string.toast_imported, result.profile.name)
            }
            toast(msg)
            if (result.notesHtml.isNotBlank()) {
                showHtmlNotesDialog(result.profile.name, result.notesHtml)
            }
        } catch (error: Throwable) {
            val detail = error.message?.lineSequence()?.firstOrNull()?.take(200)
                ?: error.javaClass.simpleName
            toast(getString(R.string.toast_import_failed, detail))
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun showHtmlNotesDialog(title: String, rawNotes: String) {
        val html = if (rawNotes.contains('<') && rawNotes.contains('>')) {
            rawNotes
        } else {
            rawNotes.replace("\n", "<br>")
        }
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (12 * density).toInt()
        val tv = TextView(this).apply {
            text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            movementMethod = LinkMovementMethod.getInstance()
            setTextColor(ContextCompat.getColor(context, R.color.text))
            setLinkTextColor(ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(padH, padV, padH, padV)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(title.ifBlank { getString(R.string.notes_dialog_title) })
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showExportRrDialog(profile: Profile) {
        if (profile.isRrLocked) {
            toast(getString(R.string.export_locked_forbidden))
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_export_rr, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etExportName)
        val cbLockServer = view.findViewById<CheckBox>(R.id.cbLockServer)
        val cbLockPayload = view.findViewById<CheckBox>(R.id.cbLockPayload)
        val etExpiry = view.findViewById<TextInputEditText>(R.id.etExportExpiry)
        val btnClearExpiry = view.findViewById<View>(R.id.btnClearExpiry)
        val etNotes = view.findViewById<TextInputEditText>(R.id.etExportNotes)

        etName.setText(profile.name)
        var expiryEpochMs = 0L
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        etExpiry.setOnClickListener {
            val cal = Calendar.getInstance()
            if (expiryEpochMs > 0L) cal.timeInMillis = expiryEpochMs
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    val chosen = Calendar.getInstance().apply {
                        set(year, month, day, 23, 59, 59)
                        set(Calendar.MILLISECOND, 999)
                    }
                    expiryEpochMs = chosen.timeInMillis
                    etExpiry.setText(dateFmt.format(chosen.time))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).apply {
                datePicker.minDate = System.currentTimeMillis()
            }.show()
        }
        btnClearExpiry.setOnClickListener {
            expiryEpochMs = 0L
            etExpiry.setText("")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.export_rr_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.export_action) { _, _ ->
                try {
                    val rawName = etName.text?.toString()?.trim().orEmpty().ifBlank { profile.name }
                    val safeFileName = rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "config" }
                    val finalFileName = if (safeFileName.endsWith(".rr", ignoreCase = true)) safeFileName else "$safeFileName.rr"
                    val opts = RrExportOptions(
                        fileName = rawName,
                        lockServer = cbLockServer.isChecked,
                        lockPayload = cbLockPayload.isChecked,
                        expiryEpochMs = expiryEpochMs,
                        notesHtml = etNotes.text?.toString().orEmpty(),
                    )
                    val encrypted = RrConfigCodec.exportToBytes(profile, opts)
                    val outDir = File(cacheDir, "exports").apply { mkdirs() }
                    val outFile = File(outDir, finalFileName)
                    outFile.writeBytes(encrypted)
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.export_share_chooser)))
                } catch (error: Throwable) {
                    val detail = error.message?.lineSequence()?.firstOrNull()?.take(200)
                        ?: error.javaClass.simpleName
                    toast(getString(R.string.export_failed, detail))
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "auto_connect"
    }
}
