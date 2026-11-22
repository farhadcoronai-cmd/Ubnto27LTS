package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bridge.AwgParameters
import com.example.bridge.AwgProfile
import com.example.bridge.AwgValidationResult
import com.example.bridge.DohBenchmarkItem
import com.example.ui.BoosterUiState
import com.example.ui.theme.TabularMetricSmall

@Composable
fun ConfigVaultScreen(
    uiState: BoosterUiState,
    onImportConfUri: (android.net.Uri) -> Unit,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (AwgProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onNewProfile: (Boolean) -> Unit,
    onEditorNameChange: (String) -> Unit,
    onEditorRawChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onOptimizeCodm: () -> Unit,
    onSelectDohUrl: (String) -> Unit,
    onAddCustomDoh: (String, String) -> Unit,
    onRemoveCustomDoh: (String) -> Unit,
    onRunBenchmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by remember { mutableIntStateOf(0) }

    val openConfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportConfUri(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "مخزن کانفیگ و موتور DoH",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "اعتبارسنجی بومی Rust برای AmneziaWG 1.5/2.0 و بنچمارک HTTP/2 RFC 8484",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("vault_section_switcher")
        ) {
            SegmentedButton(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = { Text("AmneziaWG (.conf)") }
            )
            SegmentedButton(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = { Text("سرورهای DoH و بنچمارک") }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedSection == 0) {
            AwgVaultTab(
                uiState = uiState,
                onLaunchFilePicker = { openConfLauncher.launch(arrayOf("*/*")) },
                onSelectProfile = onSelectProfile,
                onEditProfile = onEditProfile,
                onDeleteProfile = onDeleteProfile,
                onNewProfile = onNewProfile,
                onEditorNameChange = onEditorNameChange,
                onEditorRawChange = onEditorRawChange,
                onSaveProfile = onSaveProfile,
                onOptimizeCodm = onOptimizeCodm
            )
        } else {
            DohVaultTab(
                uiState = uiState,
                onSelectDohUrl = onSelectDohUrl,
                onAddCustomDoh = onAddCustomDoh,
                onRemoveCustomDoh = onRemoveCustomDoh,
                onRunBenchmarks = onRunBenchmarks
            )
        }
    }
}

@Composable
private fun AwgVaultTab(
    uiState: BoosterUiState,
    onLaunchFilePicker: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (AwgProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onNewProfile: (Boolean) -> Unit,
    onEditorNameChange: (String) -> Unit,
    onEditorRawChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onOptimizeCodm: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onLaunchFilePicker,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("import_conf_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("افزودن فایل .conf")
                }

                OutlinedButton(
                    onClick = { onNewProfile(true) },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("insert_awg_skeleton_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("قالب AWG 2.0")
                }
            }
        }

        if (uiState.settings.profiles.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "هنوز پروفایل AmneziaWG اضافه نشده است",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "فایل .conf یا .awg خود را ایمپورت کنید یا متن کانفیگ را در ویرایشگر زیر وارد نمایید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(uiState.settings.profiles, key = { it.id }) { profile ->
                val isActive = uiState.activeProfile?.id == profile.id
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProfile(profile.id) }
                        .testTag("profile_card_${profile.id}"),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (profile.isOptimizedForCodm) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "CODM TUNED",
                                        style = TabularMetricSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Text(
                                text = if (isActive) "پروفایل فعال تونل" else "برای فعال‌سازی لمس کنید",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row {
                            IconButton(
                                onClick = { onEditProfile(profile) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "ویرایش پروفایل",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onDeleteProfile(profile.id) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "حذف پروفایل",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // Raw Config Editor + Live Rust Validator
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ویرایشگر کانفیگ و بازرس AmneziaWG",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        uiState.editorValidation?.let { valRes ->
                            Text(
                                text = valRes.summary.awgVersionHint,
                                style = TabularMetricSmall.copy(fontSize = 10.sp),
                                color = if (valRes.valid) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.editorName,
                        onValueChange = onEditorNameChange,
                        label = { Text("نام پروفایل") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_name_input")
                    )

                    OutlinedTextField(
                        value = uiState.editorRawConfig,
                        onValueChange = onEditorRawChange,
                        label = { Text("متن کانفیگ (.conf / wg-quick)") },
                        minLines = 7,
                        maxLines = 14,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("raw_config_editor")
                    )

                    // One-Tap Gaming Optimizer + Save Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOptimizeCodm,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("optimize_codm_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoFixHigh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("بهینه‌ساز CODM")
                        }

                        OutlinedButton(
                            onClick = onSaveProfile,
                            modifier = Modifier
                                .weight(0.85f)
                                .minimumInteractiveComponentSize()
                                .testTag("save_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ذخیره")
                        }
                    }

                    // Live Validation Result & Anti-DPI Parameter Chips
                    uiState.editorValidation?.let { validation ->
                        ValidationSummaryCard(validation = validation)
                    }

                    uiState.lastOptimizeResult?.let { opt ->
                        AnimatedVisibility(visible = opt.changes.isNotEmpty()) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "تغییرات بهینه‌ساز گیمینگ (MTU=${opt.appliedMtu}, KA=${opt.appliedKeepalive}s):",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    opt.changes.forEach { change ->
                                        Text(
                                            text = "• $change",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValidationSummaryCard(validation: AwgValidationResult) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (validation.valid) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (validation.valid) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (validation.valid) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (validation.valid)
                        "ساختار کانفیگ معتبر است (${validation.summary.awgVersionHint})"
                    else
                        "خطای اعتبارسنجی کانفیگ (${validation.errors.size} مورد)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            validation.errors.forEach { err ->
                Text(
                    text = "• $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            validation.warnings.forEach { warn ->
                Text(
                    text = "! $warn",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (validation.summary.isAmneziaWg) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "پارامترهای Anti-DPI شناسایی‌شده:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val p: AwgParameters = validation.summary.awgParams
                    p.jc?.let { ParamChip("Jc=$it") }
                    if (p.jmin != null && p.jmax != null) ParamChip("J=${p.jmin}..${p.jmax}")
                    p.s1?.let { ParamChip("S1=$it") }
                    p.s2?.let { ParamChip("S2=$it") }
                    p.s3?.let { ParamChip("S3=$it") }
                    p.s4?.let { ParamChip("S4=$it") }
                    p.h1?.let { ParamChip("H1=${it.raw}") }
                    p.h2?.let { ParamChip("H2=${it.raw}") }
                    p.h3?.let { ParamChip("H3=${it.raw}") }
                    p.h4?.let { ParamChip("H4=${it.raw}") }
                    val cpsCount = listOf(p.i1, p.i2, p.i3, p.i4, p.i5).count { !it.isNullOrBlank() }
                    if (cpsCount > 0) ParamChip("CPS I1-I5 ($cpsCount)")
                }
            }
        }
    }
}

@Composable
private fun ParamChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            style = TabularMetricSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun DohVaultTab(
    uiState: BoosterUiState,
    onSelectDohUrl: (String) -> Unit,
    onAddCustomDoh: (String, String) -> Unit,
    onRemoveCustomDoh: (String) -> Unit,
    onRunBenchmarks: () -> Unit
) {
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("https://") }

    val benchMap: Map<String, DohBenchmarkItem> = remember(uiState.benchmarkReport) {
        uiState.benchmarkReport?.dohResults?.associateBy { it.url }.orEmpty()
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Live DoH Cache Hit-Rate Card
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "نرخ اصابت کش DNS (0ms Hit)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "حافظه LRU: ${uiState.dohStats.cacheEntries}/512 رکورد • کل: ${uiState.dohStats.totalQueries}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${uiState.dohStats.cacheHitRatePercent}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        // Concurrent Benchmarker Button + Endpoint Result
        item {
            Button(
                onClick = onRunBenchmarks,
                enabled = !uiState.isBenchmarking,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("run_doh_benchmark_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (uiState.isBenchmarking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("در حال تست هم‌زمان با Rust HTTP/2...")
                } else {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("بنچمارک زنده سرورهای DoH و Endpoint")
                }
            }
        }

        uiState.benchmarkReport?.endpointResult?.let { ep ->
            item {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Endpoint: ${ep.endpoint}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = ep.probeMethod,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = if (ep.reachable) "${ep.avgRttMs.toInt()} ms (J ${ep.jitterMs.toInt()})"
                            else "UNREACHABLE",
                            style = TabularMetricSmall,
                            color = if (ep.reachable) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        items(uiState.allDohProviders, key = { it.id }) { provider ->
            val selected = uiState.settings.selectedDohUrl == provider.url
            val bench = benchMap[provider.url]
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectDohUrl(provider.url) }
                    .testTag("doh_provider_${provider.id}"),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = provider.badge,
                                style = TabularMetricSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            text = provider.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bench != null) {
                            Text(
                                text = if (bench.reachable) "${bench.avgRttMs.toInt()} ms" else "OFF",
                                style = TabularMetricSmall,
                                color = if (bench.reachable) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                        if (provider.isCustom) {
                            IconButton(
                                onClick = { onRemoveCustomDoh(provider.id) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "حذف DoH سفارشی",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add Custom DoH Resolver Form
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "افزودن سرور DoH سفارشی (RFC 8484)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("نام دلخواه") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_doh_name_input")
                    )

                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("آدرس HTTPS (مثال: https://1.1.1.1/dns-query)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_doh_url_input")
                    )

                    Button(
                        onClick = {
                            onAddCustomDoh(customName, customUrl)
                            customName = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("add_custom_doh_button")
                    ) {
                        Text("افزودن و فعال‌سازی")
                    }
                }
            }
        }
    }
}
