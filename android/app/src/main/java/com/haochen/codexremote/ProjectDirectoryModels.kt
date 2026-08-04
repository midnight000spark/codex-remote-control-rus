package com.haochen.codexremote

internal data class ProjectDirectoryEntry(
    val name: String,
    val path: String,
)

internal data class ProjectDirectoryPickerState(
    val requestedPath: String,
    val currentPath: String,
    val parentPath: String? = null,
    val directories: List<ProjectDirectoryEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val truncated: Boolean = false,
)
