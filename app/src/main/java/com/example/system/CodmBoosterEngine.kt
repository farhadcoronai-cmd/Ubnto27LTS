package com.example.system

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.bridge.BoostExecutionResult
import com.example.bridge.NativeBridge
import com.example.bridge.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit

class CodmBoosterEngine(private val context: Context) {

    companion object {
        const val CODM_GLOBAL_PKG = "com.activision.callofduty.shooter"
        const val CODM_GARENA_PKG = "com.garena.game.codm"
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val SHIZUKU_PERMISSION_REQ_CODE = 1042
    }

    fun detectInstalledCodmPackage(): String? {
        val pm = context.packageManager
        return listOf(CODM_GLOBAL_PKG, CODM_GARENA_PKG).firstOrNull { pkg ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.isSuccess
        }
    }

    fun getShizukuStatus(): ShizukuStatus {
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PKG,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            }
        }.isSuccess

        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (binderAlive) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else false
        val uid = if (binderAlive) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1

        return ShizukuStatus(
            installed = installed,
            binderAlive = binderAlive,
            permissionGranted = granted,
            uid = uid
        )
    }

    fun requestShizukuPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            runCatching {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
                }
            }
        }
    }

    fun hasDndAccess(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }

    fun createDndSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun readAndroidMemoryFallback(): Pair<Double, Double> {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024.0 * 1024.0)
        val availMb = info.availMem / (1024.0 * 1024.0)
        return totalMb to availMb
    }

    suspend fun engageBoostAndLaunchCodm(
        targetFps: Int,
        enableDnd: Boolean,
        useShizukuPro: Boolean,
        launchGame: Boolean
    ): BoostExecutionResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()

        // 1. Start C++20 ADPF Performance Hint Session
        val mainTid = NativeBridge.getCurrentTid()
        val adpfStarted = if (mainTid > 0) {
            NativeBridge.startAdpfSession(targetFps, intArrayOf(mainTid))
        } else false
        logs += if (adpfStarted) {
            "ADPF active ($targetFps FPS scheduler hint)"
        } else {
            "ADPF unavailable on API < 33; frame ring-buffer active"
        }

        // 2. Hint JVM/ART GC reclaim before launching heavy game binary
        System.gc()
        logs += "Memory trim requested before game session"

        // 3. Do Not Disturb (DND) toggle via official NotificationManager
        var dndApplied = false
        if (enableDnd) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) {
                runCatching {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    dndApplied = true
                    logs += "DND Priority Mode enabled"
                }.onFailure {
                    logs += "DND toggle failed: ${it.message}"
                }
            } else {
                logs += "DND permission not granted (skipped)"
            }
        }

        // 4. Shizuku Pro OS-level tuning
        var shizukuApplied = false
        if (useShizukuPro) {
            val status = getShizukuStatus()
            if (status.binderAlive && status.permissionGranted) {
                val targetPkg = detectInstalledCodmPackage() ?: CODM_GLOBAL_PKG
                val cmds = listOf(
                    arrayOf("cmd", "game", "mode", "performance", targetPkg),
                    arrayOf(
                        "device_config",
                        "put",
                        "game_overlay",
                        targetPkg,
                        "mode=2,fps=$targetFps:mode=3,fps=60"
                    ),
                    arrayOf("settings", "put", "global", "window_animation_scale", "0.5"),
                    arrayOf("settings", "put", "global", "transition_animation_scale", "0.5"),
                    arrayOf("settings", "put", "global", "animator_duration_scale", "0.5")
                )
                var successCount = 0
                for (cmd in cmds) {
                    val res = execShizukuCommand(cmd)
                    if (res.first == 0) successCount++
                }
                shizukuApplied = successCount > 0
                logs += "Shizuku Pro applied $successCount/${cmds.size} OS tuning commands"
            } else {
                logs += "Shizuku binder/permission inactive; using Standard Mode"
            }
        }

        // 5. Launch CODM via PackageManager (<queries> declared in Manifest)
        var launched = false
        if (launchGame) {
            val codmPkg = detectInstalledCodmPackage()
            if (codmPkg != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(codmPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    runCatching {
                        context.startActivity(launchIntent)
                        launched = true
                        logs += "Launched $codmPkg"
                    }.onFailure {
                        logs += "Launch failed: ${it.message}"
                    }
                }
            } else {
                logs += "Call of Duty: Mobile not installed on device"
            }
        }

        BoostExecutionResult(
            adpfActive = adpfStarted,
            dndEnabled = dndApplied,
            shizukuApplied = shizukuApplied,
            codmLaunched = launched,
            logs = logs
        )
    }

    suspend fun disengageBoost(restoreShizuku: Boolean) = withContext(Dispatchers.IO) {
        NativeBridge.stopAdpfSession()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            runCatching {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
        if (restoreShizuku) {
            val status = getShizukuStatus()
            if (status.binderAlive && status.permissionGranted) {
                val targetPkg = detectInstalledCodmPackage() ?: CODM_GLOBAL_PKG
                execShizukuCommand(arrayOf("cmd", "game", "mode", "standard", targetPkg))
                execShizukuCommand(arrayOf("settings", "put", "global", "window_animation_scale", "1.0"))
                execShizukuCommand(arrayOf("settings", "put", "global", "transition_animation_scale", "1.0"))
                execShizukuCommand(arrayOf("settings", "put", "global", "animator_duration_scale", "1.0"))
            }
        }
    }

    private fun execShizukuCommand(cmd: Array<String>): Pair<Int, String> {
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = method.invoke(null, cmd, null, null) as ShizukuRemoteProcess
            val finished = process.waitForTimeout(2500L, TimeUnit.MILLISECONDS)
            val exitCode = if (finished) process.exitValue() else -1
            val output = if (finished) {
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            process.destroy()
            exitCode to output
        }.getOrElse {
            -1 to (it.message ?: "Shizuku command error")
        }
    }
}
