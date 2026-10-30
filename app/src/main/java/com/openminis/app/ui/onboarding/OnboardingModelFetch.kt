package com.openminis.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-onboarding-model-fetch-fallback] The onboarding model picker's own,
 * bounded model fetch. iOS parity (OnboardingModelFetch.withTimeout,
 * 7d17d29c4).
 *
 * The picker used to show a spinner for as long as its list was empty. The
 * fetch that fills the list runs through the repository's reconciler, which
 * logs a failure and tells no one, so a provider whose /models endpoint timed
 * out, errored or does not exist left the page spinning forever, with Skip as
 * the only way forward.
 *
 * Pure (no Android types) so the timeout and error collection are unit-tested
 * with virtual time.
 */
internal object OnboardingModelFetch {

    /** Page-level budget for all providers together (iOS: 20 s). */
    const val TIMEOUT_MS = 20_000L

    data class Result(
        /** Error messages the providers reported, in completion order. */
        val errors: List<String>,
        /** The budget ran out before every provider answered. */
        val timedOut: Boolean,
    )

    /**
     * Run [fetch] for every target in parallel within [timeoutMs]. [fetch]
     * returns an error message, or null when it had nothing to report; a
     * thrown exception counts as its message. On timeout the fetches still
     * running are cancelled.
     */
    suspend fun <T> run(
        targets: List<T>,
        timeoutMs: Long = TIMEOUT_MS,
        fetch: suspend (T) -> String?,
    ): Result {
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val finished = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                targets.map { target ->
                    async {
                        try {
                            fetch(target)?.let { errors += it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors += e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        }
                    }
                }.awaitAll()
            }
            true
        }
        return Result(errors = synchronized(errors) { errors.toList() }, timedOut = finished == null)
    }
}

// ─── Merged from OnboardingModelSelectionScreen.kt ──────────────────────────────────────────────────

/**
 * Onboarding step 2: pick 1-3 models from configured providers
 * and create a "Default Models" group. Mirrors iOS OnboardingModelSelectionView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingModelSelectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    /** [T-onboarding-model-fetch-fallback] Open Add Custom Model for this provider. */
    onAddCustomModel: (instanceId: String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    var searchText by remember { mutableStateOf("") }

    val enabledInstances = config.instances.filter { it.isEnabled }
    val enabledInstanceIds = enabledInstances.map { it.id }.toSet()
    val allEntries = config.modelEntries.filter {
        it.providerInstanceId in enabledInstanceIds && !it.isHidden
    }

    // [T-onboarding-model-fetch-fallback] State of the page's own bounded
    // fetch, which runs only while the list is empty. Saveable so a trip to
    // Add Custom Model and back keeps the failure section instead of
    // restarting a 20 s spinner.
    var fetching by remember { mutableStateOf(false) }
    var fetchAttempted by rememberSaveable { mutableStateOf(false) }
    // Non-null = the fetch finished and the page is still empty; the text is
    // the provider's own error, or "" when there is nothing beyond the headline.
    var failureDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val timeoutText = stringResource(
        R.string.onboarding_models_fetch_timeout,
        (OnboardingModelFetch.TIMEOUT_MS / 1000).toInt(),
    )

    fun visibleEntryCount(instanceIds: Set<String>): Int =
        providerRepository.config.value.modelEntries.count { it.providerInstanceId in instanceIds && !it.isHidden }

    fun runBoundedFetch() {
        if (fetching) return // Retry while in flight is a no-op.
        val current = providerRepository.config.value
        val targets = current.instances.filter { inst ->
            inst.isEnabled && current.modelEntries.none { it.providerInstanceId == inst.id && !it.isHidden }
        }
        fetchAttempted = true
        failureDetail = null
        if (targets.isEmpty()) {
            // No enabled provider at all: nothing to wait for.
            if (visibleEntryCount(enabledInstanceIds) == 0) failureDetail = ""
            return
        }
        fetching = true
        scope.launch {
            try {
                val result = OnboardingModelFetch.run(targets) { inst ->
                    var vendorError: String? = null
                    withContext(Dispatchers.IO) {
                        providerRepository.refreshModels(inst, onVendorError = { vendorError = it })
                    }
                    vendorError
                }
                // Failure is a property of the EMPTY page, not of a provider: a
                // partial result simply lists what arrived.
                val ids = providerRepository.config.value.instances.filter { it.isEnabled }.map { it.id }.toSet()
                if (visibleEntryCount(ids) == 0) {
                    failureDetail = if (result.timedOut) timeoutText else result.errors.distinct().joinToString("\n")
                }
            } finally {
                fetching = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (fetchAttempted) return@LaunchedEffect
        if (allEntries.isEmpty()) {
            runBoundedFetch()
            return@LaunchedEffect
        }
        // Normal path: refresh model lists for every enabled instance when the
        // screen appears, so the user sees the live provider catalog (not just
        // the built-in placeholder list seeded by addInstance). Mirrors iOS
        // fetchModelsWithFallback chain.
        for (instance in enabledInstances) {
            // [T-provider-refresh-outlives-screen] (GH#265) Repository scope, so
            // tapping Continue quickly does not cancel the fetch half-way.
            providerRepository.triggerAsyncModelReconcile(instance.id)
        }
    }
    val filteredEntries = if (searchText.isBlank()) allEntries else {
        val q = searchText.lowercase()
        allEntries.filter {
            it.model.displayName.lowercase().contains(q) || it.model.id.lowercase().contains(q)
        }
    }
    val grouped = filteredEntries.groupBy { it.providerInstanceId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_select_models_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    MinisTextButton(onClick = onBack) {
                        Text(stringResource(R.string.common_skip))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_select_models_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text(stringResource(R.string.onboarding_filter_models)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(50),
            )

            Spacer(Modifier.height(8.dp))

            if (allEntries.isEmpty() && !fetching && failureDetail != null) {
                ModelFetchFailure(
                    detail = failureDetail.orEmpty(),
                    instances = enabledInstances.map { it.id to it.label },
                    onRetry = { runBoundedFetch() },
                    onAddCustomModel = onAddCustomModel,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (allEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.onboarding_loading_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((instanceId, entries) in grouped) {
                        val instance = config.instances.find { it.id == instanceId }
                        item(key = "header_$instanceId") {
                            Text(
                                instance?.label ?: instanceId,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            val isSelected = entry.id in selected
                            val selectionIndex = selected.indexOf(entry.id)
                            val canSelect = selected.size < 3 || isSelected

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) selected.remove(entry.id)
                                        else if (selected.size < 3) selected.add(entry.id)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Text(
                                            "${selectionIndex + 1}",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.model.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        entry.model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            MinisButton(
                onClick = {
                    if (selected.isNotEmpty()) {
                        val group = ModelGroup(name = "Default Models")
                        group.memberEntryIds.addAll(selected)
                        providerRepository.addGroup(group)
                        if (config.defaultPrimaryGroupId == null) {
                            providerRepository.defaultPrimaryGroupId = group.id
                        }
                    }
                    onBack()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Text(stringResource(R.string.onboarding_done_count, selected.size))
            }
        }
    }
}

/**
 * [T-onboarding-model-fetch-fallback] Shown when the bounded fetch finished
 * and the page is still empty: a headline, the provider's own error under it
 * (omitted when it would only repeat the headline), Retry, and Add Model
 * Manually. A custom model belongs to exactly one provider, so with several
 * enabled providers the button opens a menu to pick one; with none (every
 * provider disabled) it hides and Skip remains.
 */
@Composable
private fun ModelFetchFailure(
    detail: String,
    instances: List<Pair<String, String>>,
    onRetry: () -> Unit,
    onAddCustomModel: (instanceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            val headline = stringResource(R.string.onboarding_models_fetch_failed)
            Text(
                headline,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            if (detail.isNotBlank() && detail != headline) {
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                )
            }
            Spacer(Modifier.height(16.dp))
            MinisButton(onClick = onRetry) {
                Text(stringResource(R.string.onboarding_models_retry))
            }
            if (instances.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Box {
                    MinisTextButton(onClick = {
                        if (instances.size == 1) onAddCustomModel(instances.first().first) else pickerOpen = true
                    }) {
                        Text(stringResource(R.string.onboarding_models_add_manually))
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        for ((id, label) in instances) {
                            DropdownMenuItem(
                                text = { Text(label.ifBlank { id }) },
                                onClick = {
                                    pickerOpen = false
                                    onAddCustomModel(id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Merged from OnboardingScreen.kt ──────────────────────────────────────────────────

/**
 * Multi-step onboarding flow shown on first launch.
 * Step 1: Welcome + add at least one provider API key
 * Step 2: Select up to 3 models to form the default model group
 */
@Composable
fun OnboardingScreen(
    providerRepository: ProviderRepository,
    onComplete: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }

    when (step) {
        0 -> WelcomeStep(onNext = { step = 1 })
        1 -> ApiKeyStep(providerRepository = providerRepository, onNext = { step = 2 }, onSkip = onComplete)
        2 -> ModelSelectionStep(providerRepository = providerRepository, onComplete = onComplete)
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
            MinisButton(onClick = onNext, modifier = Modifier.fillMaxWidth(0.6f)) {
                Text(stringResource(R.string.onboarding_get_started))
            }
        }
    }
}

@Composable
private fun ApiKeyStep(
    providerRepository: ProviderRepository,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(ProviderType.anthropic) }
    var apiKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_provider_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_provider_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            // Provider type selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (type in listOf(ProviderType.anthropic, ProviderType.gemini, ProviderType.openAI, ProviderType.openRouter)) {
                    val isSelected = type == selectedType
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedType = type; apiKey = ""; saved = false },
                    ) {
                        Text(
                            type.displayName,
                            modifier = Modifier.padding(8.dp),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text(stringResource(R.string.onboarding_provider_api_key_label, selectedType.displayName)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            MinisButton(
                onClick = {
                    if (apiKey.isNotBlank()) {
                        val instance = ProviderInstance(
                            id = "${selectedType.name}_onboarding",
                            label = selectedType.displayName,
                            providerType = selectedType,
                            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                        )
                        providerRepository.addInstance(instance)
                        providerRepository.saveApiKey(instance.id, apiKey.trim())
                        // Add built-in models
                        for (model in selectedType.builtInModels) {
                            providerRepository.addEntry(
                                ModelEntry(providerInstanceId = instance.id, baseModel = model)
                            )
                        }
                        saved = true
                    }
                },
                enabled = apiKey.isNotBlank() && !saved,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (saved) R.string.onboarding_saved else R.string.onboarding_save_api_key))
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MinisTextButton(onClick = onSkip) {
                    Text(stringResource(R.string.common_skip))
                }
                MinisButton(onClick = onNext, enabled = saved) {
                    Text(stringResource(R.string.common_next))
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionStep(
    providerRepository: ProviderRepository,
    onComplete: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val selected = remember { mutableStateListOf<String>() } // entry UUIDs
    var searchText by remember { mutableStateOf("") }

    val enabledInstanceIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val allEntries = config.modelEntries.filter {
        it.providerInstanceId in enabledInstanceIds && !it.isHidden
    }
    val filteredEntries = if (searchText.isBlank()) allEntries else {
        val q = searchText.lowercase()
        allEntries.filter { it.model.displayName.lowercase().contains(q) || it.model.id.lowercase().contains(q) }
    }

    // Group by instance
    val grouped = filteredEntries.groupBy { it.providerInstanceId }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.onboarding_select_models_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.onboarding_select_models_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text(stringResource(R.string.onboarding_search_models)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(50),
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((instanceId, entries) in grouped) {
                    val instance = config.instances.find { it.id == instanceId }
                    item {
                        Text(
                            instance?.label ?: instanceId,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(entries, key = { it.id }) { entry ->
                        val isSelected = entry.id in selected
                        val selectionIndex = selected.indexOf(entry.id)
                        val canSelect = selected.size < 3 || isSelected

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canSelect) {
                                    if (isSelected) selected.remove(entry.id)
                                    else if (selected.size < 3) selected.add(entry.id)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${selectionIndex + 1}",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.model.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    entry.model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MinisTextButton(onClick = onComplete) {
                    Text(stringResource(R.string.common_skip))
                }
                MinisButton(
                    onClick = {
                        // Create default model group from selections
                        if (selected.isNotEmpty()) {
                            val group = ModelGroup(name = "Default Models")
                            group.memberEntryIds.addAll(selected)
                            providerRepository.addGroup(group)
                            if (config.defaultPrimaryGroupId == null) {
                                providerRepository.defaultPrimaryGroupId = group.id
                            }
                        }
                        onComplete()
                    },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.onboarding_done_count, selected.size))
                }
            }
        }
    }
}
