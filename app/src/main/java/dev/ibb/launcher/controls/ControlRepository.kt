package dev.ibb.launcher.controls

import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.ControlLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Load/save for custom controller layouts.
 *
 * Layouts are single JSON files under `controls/`, one per layout, so they can be shared between
 * devices by copying a file. Built-in presets are never written to disk; a copy is materialised
 * the first time the user edits one.
 */
class ControlRepository(
    private val dir: File,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true },
) {

    private val _layouts = MutableStateFlow<List<ControlLayout>>(emptyList())
    val layouts: StateFlow<List<ControlLayout>> = _layouts.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val selected: ControlLayout
        get() = _layouts.value.firstOrNull { it.id == _selectedId.value } ?: _layouts.value.firstOrNull() ?: DefaultLayouts.classic()

    fun load() {
        dir.mkdirs()
        val custom = dir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString(ControlLayout.serializer(), file.readText())
                }.onFailure { IbbLog.w(TAG, "Skipping unreadable layout ${file.name}", it) }.getOrNull()
            }
        // Presets first (marked default), then user layouts newest first.
        _layouts.value = DefaultLayouts.all().filter { preset -> custom.none { it.id == preset.id } } + custom.sortedByDescending { it.updatedAt }
        _selectedId.value = _selectedId.value ?: _layouts.value.firstOrNull()?.id
    }

    fun select(id: String) {
        if (_layouts.value.any { it.id == id }) _selectedId.value = id
    }

    fun save(layout: ControlLayout): Boolean {
        val problems = ControlEngine.validate(layout)
        if (problems.isNotEmpty()) {
            IbbLog.w(TAG, "Rejected layout ${layout.name}: ${problems.joinToString()}")
            return false
        }
        return runCatching {
            dir.mkdirs()
            val file = File(dir, "${layout.id}.json")
            file.writeText(json.encodeToString(ControlLayout.serializer(), layout.copy(updatedAt = System.currentTimeMillis())))
            val existing = _layouts.value
            _layouts.value = if (existing.any { it.id == layout.id }) {
                existing.map { if (it.id == layout.id) layout else it }
            } else {
                existing + layout
            }
            true
        }.onFailure { IbbLog.e(TAG, "Could not save layout", it) }.getOrDefault(false)
    }

    /** Materialises a preset into an editable copy. */
    fun duplicate(layout: ControlLayout, newName: String): ControlLayout {
        val copy = layout.copy(
            id = "custom-${System.currentTimeMillis()}",
            name = newName,
            isDefault = false,
            controls = layout.controls.map { it.copy(id = java.util.UUID.randomUUID().toString()) },
        )
        save(copy)
        _selectedId.value = copy.id
        return copy
    }

    fun delete(id: String): Boolean {
        if (DefaultLayouts.all().any { it.id == id }) return false
        val removed = File(dir, "$id.json").delete()
        if (removed) {
            _layouts.value = _layouts.value.filterNot { it.id == id }
            if (_selectedId.value == id) _selectedId.value = _layouts.value.firstOrNull()?.id
        }
        return removed
    }

    fun export(layout: ControlLayout, destination: File): Boolean = runCatching {
        destination.writeText(json.encodeToString(ControlLayout.serializer(), layout))
        true
    }.getOrDefault(false)

    fun import(file: File): ControlLayout? = runCatching {
        val layout = json.decodeFromString(ControlLayout.serializer(), file.readText())
        if (ControlEngine.validate(layout).isNotEmpty()) null else layout
    }.getOrNull()

    private companion object {
        const val TAG = "ControlRepository"
    }
}
