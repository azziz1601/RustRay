package io.rustray.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import org.xrayrust.mobile.XrayTunInterface

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore

    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnPresetGaming: MaterialButton
    private lateinit var btnPresetDefault: MaterialButton
    private lateinit var etMtu: TextInputEditText
    private lateinit var ddRuntimeProfile: MaterialAutoCompleteTextView
    private lateinit var swIpv6: MaterialSwitch
    private lateinit var swRebind: MaterialSwitch
    private lateinit var swSniffing: MaterialSwitch
    private lateinit var swSniffingMetadata: MaterialSwitch
    private lateinit var swFakeIp: MaterialSwitch
    private lateinit var etDnsServers: TextInputEditText
    private lateinit var ddQueryStrategy: MaterialAutoCompleteTextView
    private lateinit var ddSplitMode: MaterialAutoCompleteTextView
    private lateinit var btnPickApps: MaterialButton

    private var selectedProfile: TunProfileOption = TunProfileOption.MOBILE_PLUS
    private var selectedQueryStrategy: String = "UseIPv4"
    private var selectedSplitMode: SplitTunnelMode = SplitTunnelMode.ALL
    private val selectedApps = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = SettingsStore(this)

        bindViews()
        applyInsets()
        setupDropdowns()
        loadIntoUi(store.load())

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                saveSettings()
                true
            } else {
                false
            }
        }

        btnPresetGaming.setOnClickListener {
            loadIntoUi(AppSettings.gamingPreset(selectedApps.toSet(), selectedSplitMode))
            Toast.makeText(this, "Preset Game diterapkan (tekan centang untuk simpan)", Toast.LENGTH_SHORT).show()
        }
        btnPresetDefault.setOnClickListener {
            loadIntoUi(AppSettings())
            Toast.makeText(this, "Reset ke default (tekan centang untuk simpan)", Toast.LENGTH_SHORT).show()
        }
        swSniffing.setOnCheckedChangeListener { _, checked ->
            swSniffingMetadata.isEnabled = checked
            if (!checked) swSniffingMetadata.isChecked = false
        }
        btnPickApps.setOnClickListener { showAppPicker() }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbarSettings)
        btnPresetGaming = findViewById(R.id.btnPresetGaming)
        btnPresetDefault = findViewById(R.id.btnPresetDefault)
        etMtu = findViewById(R.id.etMtu)
        ddRuntimeProfile = findViewById(R.id.ddRuntimeProfile)
        swIpv6 = findViewById(R.id.swIpv6)
        swRebind = findViewById(R.id.swRebind)
        swSniffing = findViewById(R.id.swSniffing)
        swSniffingMetadata = findViewById(R.id.swSniffingMetadata)
        swFakeIp = findViewById(R.id.swFakeIp)
        etDnsServers = findViewById(R.id.etDnsServers)
        ddQueryStrategy = findViewById(R.id.ddQueryStrategy)
        ddSplitMode = findViewById(R.id.ddSplitMode)
        btnPickApps = findViewById(R.id.btnPickApps)
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

    private fun setupDropdowns() {
        val profiles = TunProfileOption.entries
        ddRuntimeProfile.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, profiles.map { it.label }),
        )
        ddRuntimeProfile.setOnItemClickListener { _, _, position, _ ->
            selectedProfile = profiles[position]
        }

        val strategies = AppSettings.QUERY_STRATEGIES
        ddQueryStrategy.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, strategies),
        )
        ddQueryStrategy.setOnItemClickListener { _, _, position, _ ->
            selectedQueryStrategy = strategies[position]
        }

        val splitModes = SplitTunnelMode.entries
        ddSplitMode.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, splitModes.map { it.label }),
        )
        ddSplitMode.setOnItemClickListener { _, _, position, _ ->
            selectedSplitMode = splitModes[position]
            updateSplitButton()
        }
    }

    private fun loadIntoUi(settings: AppSettings) {
        etMtu.setText(settings.tunMtu.toString())
        selectedProfile = settings.runtimeProfile
        ddRuntimeProfile.setText(selectedProfile.label, false)
        swIpv6.isChecked = settings.ipv6Route
        swRebind.isChecked = settings.networkRebind
        swSniffing.isChecked = settings.sniffingEnabled
        swSniffingMetadata.isEnabled = settings.sniffingEnabled
        swSniffingMetadata.isChecked = settings.sniffingEnabled && settings.sniffingMetadataOnly
        swFakeIp.isChecked = settings.fakeIpEnabled
        etDnsServers.setText(settings.dnsServers)
        selectedQueryStrategy = settings.dnsQueryStrategy
        ddQueryStrategy.setText(selectedQueryStrategy, false)
        selectedSplitMode = settings.splitTunnelMode
        ddSplitMode.setText(selectedSplitMode.label, false)
        selectedApps.clear()
        selectedApps.addAll(settings.splitTunnelApps)
        updateSplitButton()
    }

    private fun updateSplitButton() {
        btnPickApps.isEnabled = selectedSplitMode != SplitTunnelMode.ALL
        btnPickApps.text = "Pilih Aplikasi (${selectedApps.size} terpilih)"
    }

    private fun showAppPicker() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
                pkg to label
            }
            .distinctBy { it.first }
            .sortedWith(
                compareByDescending<Pair<String, String>> { it.first in selectedApps }
                    .thenBy { it.second.lowercase() },
            )

        if (resolved.isEmpty()) {
            Toast.makeText(this, "Tidak ada aplikasi yang dapat dipilih", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = resolved.map { "${it.second}\n${it.first}" }.toTypedArray()
        val checked = BooleanArray(resolved.size) { idx -> resolved[idx].first in selectedApps }

        AlertDialog.Builder(this)
            .setTitle("Pilih Aplikasi")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton("Batal", null)
            .setNeutralButton("Kosongkan") { _, _ ->
                selectedApps.clear()
                updateSplitButton()
            }
            .setPositiveButton("Terapkan") { _, _ ->
                selectedApps.clear()
                for (index in resolved.indices) {
                    if (checked[index]) selectedApps.add(resolved[index].first)
                }
                updateSplitButton()
            }
            .show()
    }

    private fun saveSettings() {
        val mtu = etMtu.text?.toString()?.trim()?.toIntOrNull()
        if (mtu == null || mtu !in XrayTunInterface.MIN_MTU..XrayTunInterface.MAX_MTU) {
            Toast.makeText(
                this,
                "MTU harus antara ${XrayTunInterface.MIN_MTU} sampai ${XrayTunInterface.MAX_MTU}",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val dnsRaw = etDnsServers.text?.toString()?.trim().orEmpty()
        val fakeIp = swFakeIp.isChecked
        if (!fakeIp && dnsRaw.isBlank()) {
            Toast.makeText(this, "Isi minimal 1 DNS server jika FakeIP dimatikan", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedSplitMode != SplitTunnelMode.ALL && selectedApps.isEmpty()) {
            Toast.makeText(
                this,
                "Belum ada aplikasi terpilih; mode Split Tunneling tetap disimpan tetapi akan memakai semua aplikasi sampai ada yang dipilih",
                Toast.LENGTH_LONG,
            ).show()
        }

        val updated = AppSettings(
            tunMtu = mtu,
            runtimeProfile = selectedProfile,
            ipv6Route = swIpv6.isChecked,
            networkRebind = swRebind.isChecked,
            sniffingEnabled = swSniffing.isChecked,
            sniffingMetadataOnly = swSniffing.isChecked && swSniffingMetadata.isChecked,
            fakeIpEnabled = fakeIp,
            dnsServers = dnsRaw.ifBlank { AppSettings.DEFAULT_DNS_SERVERS },
            dnsQueryStrategy = selectedQueryStrategy,
            splitTunnelMode = selectedSplitMode,
            splitTunnelApps = selectedApps.toSet(),
        )
        store.save(updated)

        val active = VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING
        val note = if (active) {
            "Pengaturan disimpan. Disconnect lalu Connect ulang agar berlaku."
        } else {
            "Pengaturan disimpan."
        }
        Toast.makeText(this, note, Toast.LENGTH_SHORT).show()
        finish()
    }
}
