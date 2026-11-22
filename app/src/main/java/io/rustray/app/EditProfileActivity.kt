package io.rustray.app

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class EditProfileActivity : AppCompatActivity() {

    private lateinit var store: ProfileStore
    private var editingId: String? = null
    private var importedConfig: String? = null
    private var rrBlobBase64: String = ""
    private var rrLockServer: Boolean = false
    private var rrLockPayload: Boolean = false
    private var rrExpiryEpochMs: Long = 0L
    private var rrNotesHtml: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var scroll: ScrollView
    private lateinit var tilName: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var ddProtocol: MaterialAutoCompleteTextView
    private lateinit var tilServer: TextInputLayout
    private lateinit var etServer: TextInputEditText
    private lateinit var tilPort: TextInputLayout
    private lateinit var etPort: TextInputEditText
    private lateinit var tilSshUsername: TextInputLayout
    private lateinit var etSshUsername: TextInputEditText
    private lateinit var tilUuid: TextInputLayout
    private lateinit var etUuid: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var tilFlow: TextInputLayout
    private lateinit var ddFlow: MaterialAutoCompleteTextView
    private lateinit var tilVmessSecurity: TextInputLayout
    private lateinit var ddVmessSecurity: MaterialAutoCompleteTextView
    private lateinit var tilAlterId: TextInputLayout
    private lateinit var etAlterId: TextInputEditText
    private lateinit var tilSshTransport: TextInputLayout
    private lateinit var ddSshTransport: MaterialAutoCompleteTextView
    private lateinit var tilSshProxy: TextInputLayout
    private lateinit var etSshProxy: TextInputEditText
    private lateinit var tilSshPayload: TextInputLayout
    private lateinit var etSshPayload: TextInputEditText
    private lateinit var tilSshUdpgwPort: TextInputLayout
    private lateinit var etSshUdpgwPort: TextInputEditText
    private lateinit var tilSshPoolSize: TextInputLayout
    private lateinit var etSshPoolSize: TextInputEditText
    private lateinit var swSshEnhanced: MaterialSwitch
    private lateinit var tvSshEnhancedDesc: TextView
    private lateinit var tilNetwork: TextInputLayout
    private lateinit var ddNetwork: MaterialAutoCompleteTextView
    private lateinit var tilPath: TextInputLayout
    private lateinit var etPath: TextInputEditText
    private lateinit var tilHost: TextInputLayout
    private lateinit var etHost: TextInputEditText
    private lateinit var tilServiceName: TextInputLayout
    private lateinit var etServiceName: TextInputEditText
    private lateinit var tvSecurityHeader: TextView
    private lateinit var tilSecurity: TextInputLayout
    private lateinit var ddSecurity: MaterialAutoCompleteTextView
    private lateinit var tilSni: TextInputLayout
    private lateinit var etSni: TextInputEditText
    private lateinit var tilFingerprint: TextInputLayout
    private lateinit var ddFingerprint: MaterialAutoCompleteTextView
    private lateinit var tilAlpn: TextInputLayout
    private lateinit var etAlpn: TextInputEditText
    private lateinit var tilPublicKey: TextInputLayout
    private lateinit var etPublicKey: TextInputEditText
    private lateinit var tilShortId: TextInputLayout
    private lateinit var etShortId: TextInputEditText
    private lateinit var tilSpiderX: TextInputLayout
    private lateinit var etSpiderX: TextInputEditText

    private val flowOptions = listOf("none", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
    private val fingerprintOptions = listOf("chrome", "random", "firefox", "safari", "ios", "android", "edge")
    private val vmessSecurities = listOf("auto", "aes-128-gcm", "chacha20-poly1305")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)
        store = ProfileStore(this)
        bindViews()
        applyInsets()

        editingId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val existing = editingId?.let { store.find(it) }
        importedConfig = existing?.importedConfig
        rrBlobBase64 = existing?.rrBlobBase64.orEmpty()
        rrLockServer = existing?.rrLockServer == true
        rrLockPayload = existing?.rrLockPayload == true
        rrExpiryEpochMs = existing?.rrExpiryEpochMs ?: 0L
        rrNotesHtml = existing?.rrNotesHtml.orEmpty()

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                save()
                true
            } else {
                false
            }
        }

        setupDropdown(ddProtocol, Protocol.entries.map { it.label }) { onProtocolChanged() }
        setupDropdown(ddSshTransport, SshTransport.entries.map { it.label }) { updateVisibility() }
        setupDropdown(ddNetwork, Network.entries.map { it.label }) { normalizeSelections() }
        setupDropdown(ddSecurity, Security.entries.map { it.label }) { normalizeSelections() }
        setupDropdown(ddFlow, flowOptions) { updateVisibility() }
        setupDropdown(ddVmessSecurity, vmessSecurities) { updateVisibility() }
        setupDropdown(ddFingerprint, fingerprintOptions) { updateVisibility() }

        if (existing != null) {
            toolbar.title = getString(R.string.title_edit_profile)
            populate(ConfigBuilder.hydrateFromImportedConfig(existing))
        } else {
            toolbar.title = getString(R.string.title_new_profile)
            populateDefaults()
        }
        updateVisibility()
        applyRrLocks()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbarEdit)
        scroll = findViewById(R.id.scrollEdit)
        tilName = findViewById(R.id.tilName)
        etName = findViewById(R.id.etName)
        ddProtocol = findViewById(R.id.ddProtocol)
        tilServer = findViewById(R.id.tilServer)
        etServer = findViewById(R.id.etServer)
        tilPort = findViewById(R.id.tilPort)
        etPort = findViewById(R.id.etPort)
        tilSshUsername = findViewById(R.id.tilSshUsername)
        etSshUsername = findViewById(R.id.etSshUsername)
        tilUuid = findViewById(R.id.tilUuid)
        etUuid = findViewById(R.id.etUuid)
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)
        tilFlow = findViewById(R.id.tilFlow)
        ddFlow = findViewById(R.id.ddFlow)
        tilVmessSecurity = findViewById(R.id.tilVmessSecurity)
        ddVmessSecurity = findViewById(R.id.ddVmessSecurity)
        tilAlterId = findViewById(R.id.tilAlterId)
        etAlterId = findViewById(R.id.etAlterId)
        tilSshTransport = findViewById(R.id.tilSshTransport)
        ddSshTransport = findViewById(R.id.ddSshTransport)
        tilSshProxy = findViewById(R.id.tilSshProxy)
        etSshProxy = findViewById(R.id.etSshProxy)
        tilSshPayload = findViewById(R.id.tilSshPayload)
        etSshPayload = findViewById(R.id.etSshPayload)
        tilSshUdpgwPort = findViewById(R.id.tilSshUdpgwPort)
        etSshUdpgwPort = findViewById(R.id.etSshUdpgwPort)
        tilSshPoolSize = findViewById(R.id.tilSshPoolSize)
        etSshPoolSize = findViewById(R.id.etSshPoolSize)
        swSshEnhanced = findViewById(R.id.swSshEnhanced)
        tvSshEnhancedDesc = findViewById(R.id.tvSshEnhancedDesc)
        tilNetwork = findViewById(R.id.tilNetwork)
        ddNetwork = findViewById(R.id.ddNetwork)
        tilPath = findViewById(R.id.tilPath)
        etPath = findViewById(R.id.etPath)
        tilHost = findViewById(R.id.tilHost)
        etHost = findViewById(R.id.etHost)
        tilServiceName = findViewById(R.id.tilServiceName)
        etServiceName = findViewById(R.id.etServiceName)
        tvSecurityHeader = findViewById(R.id.tvSecurityHeader)
        tilSecurity = findViewById(R.id.tilSecurity)
        ddSecurity = findViewById(R.id.ddSecurity)
        tilSni = findViewById(R.id.tilSni)
        etSni = findViewById(R.id.etSni)
        tilFingerprint = findViewById(R.id.tilFingerprint)
        ddFingerprint = findViewById(R.id.ddFingerprint)
        tilAlpn = findViewById(R.id.tilAlpn)
        etAlpn = findViewById(R.id.etAlpn)
        tilPublicKey = findViewById(R.id.tilPublicKey)
        etPublicKey = findViewById(R.id.etPublicKey)
        tilShortId = findViewById(R.id.tilShortId)
        etShortId = findViewById(R.id.etShortId)
        tilSpiderX = findViewById(R.id.tilSpiderX)
        etSpiderX = findViewById(R.id.etSpiderX)
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

    private fun setupDropdown(
        view: MaterialAutoCompleteTextView,
        values: List<String>,
        onChanged: () -> Unit,
    ) {
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, values))
        view.setOnItemClickListener { _, _, _, _ -> onChanged() }
        view.setOnClickListener { view.showDropDown() }
        view.setOnFocusChangeListener { _, focused -> if (focused) view.showDropDown() }
    }

    private fun populateDefaults() {
        ddProtocol.setText(Protocol.VLESS.label, false)
        ddSshTransport.setText(SshTransport.DIRECT.label, false)
        ddNetwork.setText(Network.TCP.label, false)
        ddSecurity.setText(Security.TLS.label, false)
        ddFlow.setText("none", false)
        ddVmessSecurity.setText("auto", false)
        ddFingerprint.setText("chrome", false)
        etPort.setText("443")
        etAlterId.setText("0")
        etPath.setText("/")
        etSpiderX.setText("/")
        etSshUdpgwPort.setText("7300")
        etSshPoolSize.setText("1")
        swSshEnhanced.isChecked = false
    }

    private fun populate(profile: Profile) {
        etName.setText(profile.name)
        ddProtocol.setText(profile.protocol.label, false)
        etServer.setText(profile.server)
        etPort.setText(profile.port.toString())
        etSshUsername.setText(profile.sshUsername)
        etUuid.setText(
            if (profile.protocol == Protocol.VLESS || profile.protocol == Protocol.VMESS) {
                profile.credential
            } else {
                ""
            },
        )
        etPassword.setText(
            if (profile.protocol == Protocol.TROJAN || profile.protocol == Protocol.SSH) {
                profile.credential
            } else {
                ""
            },
        )
        ddFlow.setText(profile.flow.ifBlank { "none" }, false)
        ddVmessSecurity.setText(profile.vmessSecurity.ifBlank { "auto" }, false)
        etAlterId.setText(profile.alterId.toString())
        ddSshTransport.setText(profile.sshTransport.label, false)
        etSshProxy.setText(profile.sshProxy)
        etSshPayload.setText(profile.sshPayload)
        etSshUdpgwPort.setText(profile.sshUdpgwPort.toString())
        etSshPoolSize.setText(profile.sshPoolSize.toString())
        swSshEnhanced.isChecked = profile.sshEnhanced
        ddNetwork.setText(profile.network.label, false)
        etPath.setText(profile.path.ifBlank { "/" })
        etHost.setText(profile.host)
        etServiceName.setText(profile.serviceName)
        ddSecurity.setText(profile.security.label, false)
        etSni.setText(profile.sni)
        ddFingerprint.setText(profile.fingerprint.ifBlank { "chrome" }, false)
        etAlpn.setText(profile.alpn)
        etPublicKey.setText(profile.publicKey)
        etShortId.setText(profile.shortId)
        etSpiderX.setText(profile.spiderX.ifBlank { "/" })
    }


    private fun applyRrLocks() {
        val masked = "••••••••"
        if (rrLockServer) {
            ddProtocol.isEnabled = false
            etServer.setText(masked)
            etServer.isEnabled = false
            etPort.setText("•••")
            etPort.isEnabled = false
            etSshUsername.setText(masked)
            etSshUsername.isEnabled = false
            etUuid.setText(masked)
            etUuid.isEnabled = false
            etPassword.setText(masked)
            etPassword.isEnabled = false
        }
        if (rrLockPayload) {
            ddSshTransport.isEnabled = false
            etSshProxy.setText(masked)
            etSshProxy.isEnabled = false
            etSshPayload.setText(masked)
            etSshPayload.isEnabled = false
            ddNetwork.isEnabled = false
            etPath.setText(masked)
            etPath.isEnabled = false
            etHost.setText(masked)
            etHost.isEnabled = false
            etServiceName.setText(masked)
            etServiceName.isEnabled = false
            ddSecurity.isEnabled = false
            etSni.setText(masked)
            etSni.isEnabled = false
            etPublicKey.setText(masked)
            etPublicKey.isEnabled = false
            etShortId.setText(masked)
            etShortId.isEnabled = false
            etSpiderX.setText(masked)
            etSpiderX.isEnabled = false
        }
    }

    private fun selectedProtocol(): Protocol =
        Protocol.entries.firstOrNull { it.label == ddProtocol.text.toString() } ?: Protocol.VLESS

    private fun selectedSshTransport(): SshTransport =
        SshTransport.entries.firstOrNull { it.label == ddSshTransport.text.toString() } ?: SshTransport.DIRECT

    private fun selectedNetwork(): Network =
        Network.entries.firstOrNull { it.label == ddNetwork.text.toString() } ?: Network.TCP

    private fun selectedSecurity(): Security =
        Security.entries.firstOrNull { it.label == ddSecurity.text.toString() } ?: Security.TLS

    private fun onProtocolChanged() {
        val protocol = selectedProtocol()
        if (editingId == null) {
            val currentPort = etPort.text?.toString()?.trim()
            if (protocol == Protocol.SSH && currentPort == "443") {
                etPort.setText("22")
            } else if (protocol != Protocol.SSH && currentPort == "22") {
                etPort.setText("443")
            }
        }
        normalizeSelections()
    }

    private fun normalizeSelections() {
        if (selectedProtocol() != Protocol.SSH) {
            val security = selectedSecurity()
            val network = selectedNetwork()
            if (security == Security.REALITY && (network == Network.WS || network == Network.HTTPUPGRADE)) {
                ddSecurity.setText(Security.TLS.label, false)
                toast(getString(R.string.toast_reality_switched, network.label))
            }
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        val protocol = selectedProtocol()
        val isSsh = protocol == Protocol.SSH

        if (isSsh) {
            val sshMode = selectedSshTransport()
            val needsProxy = sshMode == SshTransport.PAYLOAD ||
                sshMode == SshTransport.PROXY ||
                sshMode == SshTransport.SSL_PAYLOAD ||
                sshMode == SshTransport.WS ||
                sshMode == SshTransport.SSL_WS
            val needsPayload = needsProxy
            val needsWs = sshMode == SshTransport.WS || sshMode == SshTransport.SSL_WS
            val needsSni = sshMode == SshTransport.SSL ||
                sshMode == SshTransport.SSL_PAYLOAD ||
                sshMode == SshTransport.SSL_WS

            tilSshUsername.visibility = View.VISIBLE
            tilPassword.visibility = View.VISIBLE
            tilUuid.visibility = View.GONE
            tilFlow.visibility = View.GONE
            tilVmessSecurity.visibility = View.GONE
            tilAlterId.visibility = View.GONE

            tilSshTransport.visibility = View.VISIBLE
            tilSshProxy.visibility = if (needsProxy) View.VISIBLE else View.GONE
            tilSshPayload.visibility = if (needsPayload) View.VISIBLE else View.GONE
            tilSshUdpgwPort.visibility = View.VISIBLE
            tilSshPoolSize.visibility = View.VISIBLE
            swSshEnhanced.visibility = View.VISIBLE
            tvSshEnhancedDesc.visibility = View.VISIBLE

            tilNetwork.visibility = View.GONE
            tilPath.visibility = if (needsWs) View.VISIBLE else View.GONE
            tilHost.visibility = if (needsWs) View.VISIBLE else View.GONE
            tilServiceName.visibility = View.GONE

            tvSecurityHeader.visibility = if (needsSni) View.VISIBLE else View.GONE
            tilSecurity.visibility = View.GONE
            tilSni.visibility = if (needsSni) View.VISIBLE else View.GONE
            tilFingerprint.visibility = View.GONE
            tilAlpn.visibility = View.GONE
            tilPublicKey.visibility = View.GONE
            tilShortId.visibility = View.GONE
            tilSpiderX.visibility = View.GONE
            return
        }

        val network = selectedNetwork()
        val security = selectedSecurity()
        val tlsLike = security != Security.NONE
        val framed = network == Network.WS || network == Network.HTTPUPGRADE || network == Network.XHTTP

        tilSshUsername.visibility = View.GONE
        tilSshTransport.visibility = View.GONE
        tilSshProxy.visibility = View.GONE
        tilSshPayload.visibility = View.GONE
        tilSshUdpgwPort.visibility = View.GONE
        tilSshPoolSize.visibility = View.GONE
        swSshEnhanced.visibility = View.GONE
        tvSshEnhancedDesc.visibility = View.GONE

        tilUuid.visibility = if (protocol == Protocol.TROJAN) View.GONE else View.VISIBLE
        tilPassword.visibility = if (protocol == Protocol.TROJAN) View.VISIBLE else View.GONE
        tilFlow.visibility = if (protocol == Protocol.VLESS) View.VISIBLE else View.GONE
        tilVmessSecurity.visibility = if (protocol == Protocol.VMESS) View.VISIBLE else View.GONE
        tilAlterId.visibility = if (protocol == Protocol.VMESS) View.VISIBLE else View.GONE

        tilNetwork.visibility = View.VISIBLE
        tilPath.visibility = if (framed) View.VISIBLE else View.GONE
        tilHost.visibility = if (framed) View.VISIBLE else View.GONE
        tilServiceName.visibility = if (network == Network.GRPC) View.VISIBLE else View.GONE

        tvSecurityHeader.visibility = View.VISIBLE
        tilSecurity.visibility = View.VISIBLE
        tilSni.visibility = if (tlsLike) View.VISIBLE else View.GONE
        tilFingerprint.visibility = if (tlsLike) View.VISIBLE else View.GONE
        tilAlpn.visibility = if (security == Security.TLS) View.VISIBLE else View.GONE

        val reality = security == Security.REALITY
        tilPublicKey.visibility = if (reality) View.VISIBLE else View.GONE
        tilShortId.visibility = if (reality) View.VISIBLE else View.GONE
        tilSpiderX.visibility = if (reality) View.VISIBLE else View.GONE
    }

    private fun save() {
        clearErrors()
        val protocol = selectedProtocol()
        val network = selectedNetwork()
        val security = selectedSecurity()
        val sshTransport = selectedSshTransport()
        val name = etName.text?.toString()?.trim().orEmpty()
        val server = etServer.text?.toString()?.trim().orEmpty()
        val port = etPort.text?.toString()?.trim()?.toIntOrNull()
        val sshUsername = etSshUsername.text?.toString()?.trim().orEmpty()
        val credential = if (protocol == Protocol.TROJAN || protocol == Protocol.SSH) {
            etPassword.text?.toString().orEmpty()
        } else {
            etUuid.text?.toString()?.trim().orEmpty()
        }
        val flow = if (protocol == Protocol.VLESS) {
            ddFlow.text.toString().takeUnless { it == "none" }.orEmpty()
        } else {
            ""
        }
        val alterId = if (protocol == Protocol.VMESS) {
            etAlterId.text?.toString()?.trim()?.toIntOrNull()
        } else {
            0
        }
        val udpgwPort = etSshUdpgwPort.text?.toString()?.trim()?.toIntOrNull() ?: 7300
        val poolSize = etSshPoolSize.text?.toString()?.trim()?.toIntOrNull() ?: 2

        var valid = true
        if (!rrLockServer) {
            if (server.isBlank()) {
                showError(tilServer, getString(R.string.err_server_required))
                valid = false
            }
            if (port == null || port !in 1..65535) {
                showError(tilPort, getString(R.string.err_port_range))
                valid = false
            }
        }
        val sshProxy = etSshProxy.text?.toString()?.trim().orEmpty()
        if (protocol == Protocol.SSH) {
            if (!rrLockPayload && sshTransport == SshTransport.PROXY && sshProxy.isBlank()) {
                showError(tilSshProxy, getString(R.string.err_proxy_required))
                valid = false
            }
            if (!rrLockServer && sshUsername.isBlank()) {
                showError(tilSshUsername, getString(R.string.err_username_required))
                valid = false
            }
            if (!rrLockServer && credential.isBlank()) {
                showError(tilPassword, getString(R.string.err_password_required))
                valid = false
            }
            if (udpgwPort !in 1..65535) {
                showError(tilSshUdpgwPort, getString(R.string.err_udpgw_range))
                valid = false
            }
            if (poolSize !in 1..4) {
                showError(tilSshPoolSize, getString(R.string.err_pool_range))
                valid = false
            }
        } else {
            if (protocol == Protocol.VMESS && (alterId == null || alterId !in 0..65535)) {
                showError(tilAlterId, getString(R.string.err_alter_id_range))
                valid = false
            }
            if (!rrLockServer && credential.isBlank()) {
                if (protocol == Protocol.TROJAN) showError(tilPassword, getString(R.string.err_password_required))
                else showError(tilUuid, getString(R.string.err_uuid_required))
                valid = false
            }
            if (flow.isNotEmpty() && (network != Network.TCP || security == Security.NONE)) {
                showError(tilFlow, getString(R.string.err_vision_req))
                valid = false
            }
            if (security == Security.REALITY) {
                if (selectedNetwork() == Network.WS || selectedNetwork() == Network.HTTPUPGRADE) {
                    showError(tilSecurity, getString(R.string.err_reality_net, network.label))
                    valid = false
                }
                if (!rrLockPayload && etPublicKey.text?.toString()?.trim().isNullOrEmpty()) {
                    showError(tilPublicKey, getString(R.string.err_reality_pubkey))
                    valid = false
                }
            }
        }
        if (!valid || (!rrLockServer && port == null)) return

        val profile = Profile(
            id = editingId ?: UUID.randomUUID().toString(),
            name = name.ifBlank { if (rrLockServer) "RustRay Config" else server },
            protocol = protocol,
            server = if (rrLockServer) "" else server,
            port = if (rrLockServer) 0 else (port ?: 443),
            credential = if (rrLockServer) "" else credential,
            flow = flow,
            vmessSecurity = ddVmessSecurity.text.toString().ifBlank { "auto" },
            alterId = alterId ?: 0,
            security = security,
            sni = etSni.text?.toString()?.trim().orEmpty(),
            fingerprint = ddFingerprint.text.toString().ifBlank { "chrome" },
            alpn = etAlpn.text?.toString()?.trim().orEmpty(),
            publicKey = etPublicKey.text?.toString()?.trim().orEmpty(),
            shortId = etShortId.text?.toString()?.trim().orEmpty(),
            spiderX = etSpiderX.text?.toString()?.trim().orEmpty(),
            network = network,
            path = etPath.text?.toString()?.trim().orEmpty().ifBlank { "/" },
            host = etHost.text?.toString()?.trim().orEmpty(),
            serviceName = etServiceName.text?.toString()?.trim().orEmpty(),
            sshUsername = if (rrLockServer) "" else sshUsername,
            sshTransport = sshTransport,
            sshPayload = if (rrLockPayload) "" else etSshPayload.text?.toString().orEmpty(),
            sshProxy = if (rrLockPayload) "" else sshProxy,
            sshUdpgwPort = udpgwPort,
            sshPoolSize = poolSize,
            sshEnhanced = swSshEnhanced.isChecked,
            importedConfig = null,
            plaintext = protocol != Protocol.SSH && security == Security.NONE,
            rrBlobBase64 = rrBlobBase64,
            rrLockServer = rrLockServer,
            rrLockPayload = rrLockPayload,
            rrExpiryEpochMs = rrExpiryEpochMs,
            rrNotesHtml = rrNotesHtml,
        )
        store.upsert(profile)
        if (store.selectedId() == null) store.select(profile.id)
        setResult(RESULT_OK)
        finish()
    }

    private fun clearErrors() {
        listOf(
            tilName, tilServer, tilPort, tilSshUsername, tilUuid, tilPassword, tilFlow,
            tilVmessSecurity, tilAlterId, tilSshTransport, tilSshProxy, tilSshPayload, tilSshUdpgwPort,
            tilSshPoolSize, tilNetwork, tilPath, tilHost, tilServiceName,
            tilSecurity, tilSni, tilFingerprint, tilAlpn, tilPublicKey,
            tilShortId, tilSpiderX,
        ).forEach { it.error = null }
    }

    private fun showError(field: TextInputLayout, message: String) {
        if (field.error == null) field.error = message
        field.requestFocus()
        scroll.post { scroll.smoothScrollTo(0, field.top) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
