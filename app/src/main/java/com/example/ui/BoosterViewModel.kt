package com.example.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bridge.AwgOptimizeResult
import com.example.bridge.AwgProfile
import com.example.bridge.AwgValidationResult
import com.example.bridge.BenchmarkReport
import com.example.bridge.DefaultDohProviders
import com.example.bridge.DohEngineStats
import com.example.bridge.DohProvider
import com.example.bridge.HardwareTelemetry
import com.example.bridge.NativeBridge
import com.example.bridge.RoutingMode
import com.example.bridge.ShizukuStatus
import com.example.bridge.TunnelState
import com.example.bridge.TunnelStats
import com.example.data.ConfigRepository
import com.example.data.PersistedSettings
import com.example.system.CodmBoosterEngine
import com.example.vpn.AmneziaWgController
import com.example.vpn.CodmDohVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.UUID

data class BoosterUiState(
    val settings: PersistedSettings = PersistedSettings(),
    val hardware: HardwareTelemetry = HardwareTelemetry(),
    val tunnelStats: TunnelStats = TunnelStats(),
    val dohRunning: Boolean = false,
    val dohStats: DohEngineStats = DohEngineStats(),
    val shizukuStatus: ShizukuStatus = ShizukuStatus(),
    val hasDndAccess: Boolean = false,
    val installedCodmPackage: String? = null,
    val rttHistory: List<Double> = emptyList(),
    val isBenchmarking: Boolean = false,
    val benchmarkReport: BenchmarkReport? = null,
    val lastOptimizeResult: AwgOptimizeResult? = null,
    val editorName: String = "",
    val editorRawConfig: String = "",
    val editorProfileId: String? = null,
    val editorValidation: AwgValidationResult? = null,
    val statusMessage: String? = null,
    val boostLogs: List<String> = emptyList(),
    val shockwaveKey: Int = 0,
    val isCppLoaded: Boolean = false,
    val isRustLoaded: Boolean = false
) {
    val activeProfile: AwgProfile?
        get() = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
            ?: settings.profiles.firstOrNull()

    val allDohProviders: List<DohProvider>
        get() = DefaultDohProviders.presets + settings.customDohProviders

    val isTunnelActive: Boolean
        get() = when (settings.routingMode) {
            RoutingMode.AMNEZIA_WG -> tunnelStats.state == TunnelState.CONNECTED
            RoutingMode.DOH_TURBO -> dohRunning
        }

    val isTunnelConnecting: Boolean
        get() = settings.routingMode == RoutingMode.AMNEZIA_WG &&
            tunnelStats.state == TunnelState.CONNECTING
}

class BoosterViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = ConfigRepository(appContext)
    private val awgController = AmneziaWgController(appContext)
    private val boosterEngine = CodmBoosterEngine(appContext)

    private val _hardware = MutableStateFlow(HardwareTelemetry())
    private val _shizukuStatus = MutableStateFlow(boosterEngine.getShizukuStatus())
    private val _hasDndAccess = MutableStateFlow(boosterEngine.hasDndAccess())
    private val _installedCodm = MutableStateFlow(boosterEngine.detectInstalledCodmPackage())
    private val _rttHistory = MutableStateFlow<List<Double>>(emptyList())
    private val _isBenchmarking = MutableStateFlow(false)
    private val _benchmarkReport = MutableStateFlow<BenchmarkReport?>(null)
    private val _lastOptimizeResult = MutableStateFlow<AwgOptimizeResult?>(null)
    private val _editorName = MutableStateFlow("")
    private val _editorRawConfig = MutableStateFlow("")
    private val _editorProfileId = MutableStateFlow<String?>(null)
    private val _editorValidation = MutableStateFlow<AwgValidationResult?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _boostLogs = MutableStateFlow<List<String>>(emptyList())
    private val _shockwaveKey = MutableStateFlow(0)

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        _shizukuStatus.value = boosterEngine.getShizukuStatus()
    }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        _shizukuStatus.value = boosterEngine.getShizukuStatus()
    }
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == CodmBoosterEngine.SHIZUKU_PERMISSION_REQ_CODE) {
                _shizukuStatus.value = boosterEngine.getShizukuStatus()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    _statusMessage.value = "دسترسی Shizuku Pro با موفقیت صادر شد."
                }
            }
        }

    private val baseFlow = combine(
        repository.settingsFlow,
        _hardware,
        awgController.stats,
        CodmDohVpnService.isRunning,
        CodmDohVpnService.dohStats
    ) { settings, hardware, tunnelStats, dohRunning, dohStats ->
        Triple(settings, hardware, Triple(tunnelStats, dohRunning, dohStats))
    }

    private val auxFlow = combine(
        _shizukuStatus,
        _hasDndAccess,
        _installedCodm,
        _rttHistory,
        _isBenchmarking
    ) { shizuku, dnd, codmPkg, rttHist, benchmarking ->
        listOf(shizuku, dnd, codmPkg, rttHist, benchmarking)
    }

    private val editorFlow = combine(
        _benchmarkReport,
        _lastOptimizeResult,
        _editorName,
        _editorRawConfig,
        _editorProfileId
    ) { bench, opt, name, raw, id ->
        listOf(bench, opt, name, raw, id)
    }

    private val feedbackFlow = combine(
        _editorValidation,
        _statusMessage,
        _boostLogs,
        _shockwaveKey
    ) { valRes, msg, logs, wave ->
        listOf(valRes, msg, logs, wave)
    }

    val uiState: StateFlow<BoosterUiState> = combine(
        baseFlow,
        auxFlow,
        editorFlow,
        feedbackFlow
    ) { base, aux, editor, feedback ->
        val settings = base.first
        val hardware = base.second
        val (tunnelStats, dohRunning, dohStats) = base.third

        @Suppress("UNCHECKED_CAST")
        BoosterUiState(
            settings = settings,
            hardware = hardware,
            tunnelStats = tunnelStats,
            dohRunning = dohRunning,
            dohStats = dohStats,
            shizukuStatus = aux[0] as ShizukuStatus,
            hasDndAccess = aux[1] as Boolean,
            installedCodmPackage = aux[2] as String?,
            rttHistory = aux[3] as List<Double>,
            isBenchmarking = aux[4] as Boolean,
            benchmarkReport = editor[0] as BenchmarkReport?,
            lastOptimizeResult = editor[1] as AwgOptimizeResult?,
            editorName = editor[2] as String,
            editorRawConfig = editor[3] as String,
            editorProfileId = editor[4] as String?,
            editorValidation = feedback[0] as AwgValidationResult?,
            statusMessage = feedback[1] as String?,
            boostLogs = feedback[2] as List<String>,
            shockwaveKey = feedback[3] as Int,
            isCppLoaded = NativeBridge.isCppLoaded,
            isRustLoaded = NativeBridge.isRustLoaded
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = BoosterUiState(
            isCppLoaded = NativeBridge.isCppLoaded,
            isRustLoaded = NativeBridge.isRustLoaded
        )
    )

    init {
        NativeBridge.initCppEngine()
        registerShizukuListeners()
        startTelemetryPolling()
    }

    private fun registerShizukuListeners() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            Shizuku.addBinderDeadListener(shizukuDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    private fun startTelemetryPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                var snap = NativeBridge.collectHardwareTelemetry()
                if (snap.memTotalMb <= 0.0) {
                    val (totMb, availMb) = boosterEngine.readAndroidMemoryFallback()
                    val usage = if (totMb > 0.0) ((totMb - availMb) / totMb) * 100.0 else 0.0
                    snap = snap.copy(
                        memTotalMb = totMb,
                        memAvailableMb = availMb,
                        memUsagePercent = usage
                    )
                }
                _hardware.value = snap

                val sampleRtt = when {
                    CodmDohVpnService.isRunning.value && CodmDohVpnService.dohStats.value.lastRttMs > 0.0 ->
                        CodmDohVpnService.dohStats.value.lastRttMs
                    snap.avgNetRttMs > 0.0 -> snap.avgNetRttMs
                    else -> null
                }
                if (sampleRtt != null) {
                    _rttHistory.value = (_rttHistory.value + sampleRtt).takeLast(24)
                }
                delay(1000L)
            }
        }
    }

    fun refreshSystemStatus() {
        _shizukuStatus.value = boosterEngine.getShizukuStatus()
        _hasDndAccess.value = boosterEngine.hasDndAccess()
        _installedCodm.value = boosterEngine.detectInstalledCodmPackage()
    }

    fun prepareVpnIntent(): Intent? = VpnService.prepare(appContext)

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            val current = uiState.value.settings.routingMode
            if (current != mode) {
                if (current == RoutingMode.DOH_TURBO && CodmDohVpnService.isRunning.value) {
                    CodmDohVpnService.stop(appContext)
                } else if (current == RoutingMode.AMNEZIA_WG &&
                    awgController.stats.value.state != TunnelState.DISCONNECTED
                ) {
                    awgController.stopTunnel()
                }
                repository.setRoutingMode(mode)
            }
        }
    }

    fun toggleCodmSplitTunnel(enabled: Boolean) {
        viewModelScope.launch { repository.setCodmSplitTunnel(enabled) }
    }

    fun toggleShizukuPro(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val st = boosterEngine.getShizukuStatus()
                _shizukuStatus.value = st
                if (st.binderAlive && !st.permissionGranted) {
                    boosterEngine.requestShizukuPermission()
                } else if (!st.binderAlive) {
                    _statusMessage.value = "سرویس Shizuku فعال نیست؛ ابتدا Shizuku را اجرا کنید."
                }
            }
            repository.setShizukuProMode(enabled)
        }
    }

    fun requestShizukuPermission() {
        boosterEngine.requestShizukuPermission()
    }

    fun toggleDndAuto(enabled: Boolean) {
        viewModelScope.launch { repository.setDndAuto(enabled) }
    }

    fun createDndSettingsIntent(): Intent = boosterEngine.createDndSettingsIntent()

    fun setTargetFps(fps: Int) {
        viewModelScope.launch {
            repository.setTargetFps(fps)
            NativeBridge.updateAdpfTargetFps(fps)
        }
    }

    fun startOrStopActiveTunnel() {
        viewModelScope.launch {
            val state = uiState.value
            when (state.settings.routingMode) {
                RoutingMode.DOH_TURBO -> {
                    if (state.dohRunning) {
                        CodmDohVpnService.stop(appContext)
                        _statusMessage.value = "موتور DoH Turbo متوقف شد."
                    } else {
                        CodmDohVpnService.start(appContext, state.settings.selectedDohUrl)
                        _shockwaveKey.value += 1
                        _statusMessage.value = "موتور DoH RFC 8484 روی پورت DNS فعال شد."
                    }
                }
                RoutingMode.AMNEZIA_WG -> {
                    if (state.tunnelStats.state == TunnelState.CONNECTED ||
                        state.tunnelStats.state == TunnelState.CONNECTING
                    ) {
                        awgController.stopTunnel()
                        _statusMessage.value = "تونل AmneziaWG قطع شد."
                    } else {
                        val profile = state.activeProfile
                        if (profile == null) {
                            _statusMessage.value =
                                "ابتدا در تب «کانفیگ و DoH» یک فایل .conf امینزیا وارد کنید."
                            return@launch
                        }
                        val validation = NativeBridge.validateAwgConfig(profile.rawConfig)
                        if (!validation.valid) {
                            _statusMessage.value =
                                "خطا در کانفیگ: ${validation.errors.firstOrNull() ?: "نامعتبر"}"
                            return@launch
                        }
                        val res = awgController.startTunnel(
                            tunnelName = profile.name,
                            rawConfigText = profile.rawConfig,
                            enforceCodmSplitTunnel = state.settings.codmSplitTunnelEnabled
                        )
                        res.onSuccess {
                            _shockwaveKey.value += 1
                            _statusMessage.value = "تونل مخفی AmneziaWG (${profile.name}) متصل شد."
                        }.onFailure { err ->
                            _statusMessage.value = "خطای اتصال AmneziaWG: ${err.message}"
                        }
                    }
                }
            }
        }
    }

    fun engageBoostAndLaunchCodm(launchGame: Boolean) {
        viewModelScope.launch {
            val state = uiState.value
            _shockwaveKey.value += 1
            val res = boosterEngine.engageBoostAndLaunchCodm(
                targetFps = state.settings.targetFps,
                enableDnd = state.settings.dndAutoEnabled,
                useShizukuPro = state.settings.shizukuProModeEnabled,
                launchGame = launchGame
            )
            _boostLogs.value = res.logs
            _statusMessage.value = when {
                launchGame && res.codmLaunched ->
                    "بوست سخت‌افزاری فعال شد و Call of Duty: Mobile اجرا گردید."
                launchGame && !res.codmLaunched ->
                    "بوست فعال شد، اما CODM روی دستگاه یافت نشد."
                else ->
                    "بوست سخت‌افزاری (ADPF + حافظه) با موفقیت اعمال شد."
            }
        }
    }

    fun disengageBoost() {
        viewModelScope.launch {
            boosterEngine.disengageBoost(uiState.value.settings.shizukuProModeEnabled)
            _statusMessage.value = "تنظیمات بوست و انیمیشن سیستم به حالت استاندارد بازگشت."
        }
    }

    // --- Config & DoH Vault Actions ---

    fun importConfFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = repository.readConfFromUri(uri)
            res.onSuccess { (fileName, content) ->
                val validation = NativeBridge.validateAwgConfig(content)
                _editorProfileId.value = null
                _editorName.value = fileName
                _editorRawConfig.value = content
                _editorValidation.value = validation

                if (validation.valid) {
                    val newProfile = AwgProfile(
                        id = UUID.randomUUID().toString(),
                        name = fileName,
                        rawConfig = content
                    )
                    repository.saveProfile(newProfile, setActive = true)
                    _editorProfileId.value = newProfile.id
                    _statusMessage.value = "کانفیگ «$fileName» با موفقیت وارد و تایید شد."
                } else {
                    _statusMessage.value =
                        "فایل وارد شد اما دارای خطای ساختاری است؛ در ویرایشگر بررسی کنید."
                }
            }.onFailure { err ->
                _statusMessage.value = "خطا در خواندن فایل کانفیگ: ${err.message}"
            }
        }
    }

    fun loadProfileIntoEditor(profile: AwgProfile) {
        _editorProfileId.value = profile.id
        _editorName.value = profile.name
        _editorRawConfig.value = profile.rawConfig
        validateEditorAsync(profile.rawConfig)
    }

    fun prepareNewEditor(withSkeleton: Boolean) {
        _editorProfileId.value = null
        _editorName.value = if (withSkeleton) "AWG 2.0 CODM Stealth" else ""
        val template = if (withSkeleton) {
            """
            [Interface]
            PrivateKey = REPLACE_WITH_32_BYTE_BASE64_PRIVATE_KEY=
            Address = 10.8.0.2/32
            DNS = 1.1.1.1
            MTU = 1320
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 42
            S2 = 64
            S3 = 32
            S4 = 16
            H1 = 100000-200000
            H2 = 200001-300000
            H3 = 300001-400000
            H4 = 400001-500000
            I1 = <b 0xc0a80101><t><r 16>

            [Peer]
            PublicKey = REPLACE_WITH_32_BYTE_BASE64_SERVER_KEY=
            Endpoint = YOUR_SERVER_IP:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
            """.trimIndent()
        } else ""
        _editorRawConfig.value = template
        validateEditorAsync(template)
    }

    fun updateEditorName(name: String) {
        _editorName.value = name
    }

    fun updateEditorRawConfig(raw: String) {
        _editorRawConfig.value = raw
        validateEditorAsync(raw)
    }

    private fun validateEditorAsync(raw: String) {
        if (raw.isBlank()) {
            _editorValidation.value = null
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _editorValidation.value = NativeBridge.validateAwgConfig(raw)
        }
    }

    fun saveEditorProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val raw = _editorRawConfig.value
            val name = _editorName.value.trim().ifBlank { "CODM Profile" }
            val validation = NativeBridge.validateAwgConfig(raw)
            _editorValidation.value = validation
            if (!validation.valid) {
                _statusMessage.value =
                    "ذخیره نشد: ${validation.errors.firstOrNull() ?: "کانفیگ نامعتبر"}"
                return@launch
            }
            val id = _editorProfileId.value ?: UUID.randomUUID().toString()
            val profile = AwgProfile(
                id = id,
                name = name,
                rawConfig = raw,
                splitTunnelCodm = uiState.value.settings.codmSplitTunnelEnabled
            )
            repository.saveProfile(profile, setActive = true)
            _editorProfileId.value = id
            _statusMessage.value = "پروفایل «$name» با موفقیت در Vault ذخیره شد."
        }
    }

    fun optimizeEditorOrActiveForCodm() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = uiState.value
            val targetRaw = _editorRawConfig.value.takeIf { it.isNotBlank() }
                ?: state.activeProfile?.rawConfig
            if (targetRaw.isNullOrBlank()) {
                _statusMessage.value = "هیچ کانفیگی برای بهینه‌سازی انتخاب نشده است."
                return@launch
            }

            val opt = NativeBridge.optimizeAwgConfigForCodm(
                configText = targetRaw,
                enforceSplitTunnel = state.settings.codmSplitTunnelEnabled
            )
            _lastOptimizeResult.value = opt
            _editorRawConfig.value = opt.optimizedConfig
            _editorValidation.value = opt.validation

            if (opt.success) {
                val targetId = _editorProfileId.value ?: state.activeProfile?.id
                    ?: UUID.randomUUID().toString()
                val targetName = _editorName.value.takeIf { it.isNotBlank() }
                    ?: state.activeProfile?.name ?: "CODM Stealth"
                val updated = AwgProfile(
                    id = targetId,
                    name = targetName,
                    rawConfig = opt.optimizedConfig,
                    isOptimizedForCodm = true,
                    splitTunnelCodm = state.settings.codmSplitTunnelEnabled
                )
                repository.saveProfile(updated, setActive = true)
                _editorProfileId.value = targetId
                _statusMessage.value =
                    "بهینه‌سازی انجام شد: MTU=${opt.appliedMtu}, Keepalive=${opt.appliedKeepalive}s"
            } else {
                _statusMessage.value =
                    "خطای ساختاری پس از بهینه‌سازی: ${opt.validation.errors.firstOrNull().orEmpty()}"
            }
        }
    }

    fun selectProfile(id: String) {
        viewModelScope.launch {
            repository.setActiveProfile(id)
            uiState.value.settings.profiles.firstOrNull { it.id == id }?.let {
                loadProfileIntoEditor(it)
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
            if (_editorProfileId.value == id) {
                _editorProfileId.value = null
                _editorName.value = ""
                _editorRawConfig.value = ""
                _editorValidation.value = null
            }
        }
    }

    fun selectDohUrl(url: String) {
        viewModelScope.launch {
            repository.setSelectedDohUrl(url)
            CodmDohVpnService.updateUrl(appContext, url)
            _statusMessage.value = "سرور DoH فعال تغییر یافت."
        }
    }

    fun addCustomDoh(name: String, url: String) {
        val clean = url.trim()
        if (!clean.startsWith("https://")) {
            _statusMessage.value = "آدرس DoH باید با https:// شروع شود."
            return
        }
        viewModelScope.launch {
            repository.addCustomDohProvider(name, clean)
            CodmDohVpnService.updateUrl(appContext, clean)
            _statusMessage.value = "سرور DoH سفارشی اضافه و فعال شد."
        }
    }

    fun removeCustomDoh(id: String) {
        viewModelScope.launch { repository.removeCustomDohProvider(id) }
    }

    fun runConcurrentBenchmarks() {
        if (_isBenchmarking.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBenchmarking.value = true
            val state = uiState.value
            val urls = state.allDohProviders.map { it.url }.distinct()
            val endpoint = state.editorValidation?.summary?.endpoint
                ?: state.activeProfile?.let { NativeBridge.validateAwgConfig(it.rawConfig).summary.endpoint }
                ?: ""

            val report = NativeBridge.benchmarkDohAndEndpoint(urls, endpoint)
            _benchmarkReport.value = report
            _isBenchmarking.value = false

            report.bestDohUrl?.let { best ->
                _statusMessage.value = "بنچمارک کامل شد؛ سریع‌ترین سرور: $best"
            } ?: run {
                _statusMessage.value = "بنچمارک انجام شد؛ پاسخی از سرورها دریافت نشد."
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeBinderDeadListener(shizukuDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        NativeBridge.shutdownCppEngine()
        super.onCleared()
    }
}
