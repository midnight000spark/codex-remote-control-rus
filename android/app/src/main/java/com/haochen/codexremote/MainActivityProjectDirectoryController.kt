package com.haochen.codexremote

import org.json.JSONObject

internal fun MainActivity.openProjectDirectoryPicker() {
    val draft = newChatDraft ?: return
    val initialPath = draft.projectPath?.trim().orEmpty()
    projectDirectoryPickerState =
        ProjectDirectoryPickerState(
            requestedPath = initialPath,
            currentPath = initialPath,
        )
    requestProjectDirectories(initialPath)
}

internal fun MainActivity.closeProjectDirectoryPicker() {
    projectDirectoryPickerState = null
}

internal fun MainActivity.requestProjectDirectories(path: String) {
    val requestedPath = path.trim()
    val current = projectDirectoryPickerState ?: return
    val loadingState =
        current.copy(
            requestedPath = requestedPath,
            loading = true,
            error = null,
            truncated = false,
        )
    projectDirectoryPickerState = loadingState

    val payload = JSONObject().apply {
        if (requestedPath.isNotBlank()) put("path", requestedPath)
    }
    val sent = sendRequest(
        "directory.list",
        payload,
        object : ResponseHandler {
            override fun onResponse(response: JSONObject) {
                val state = projectDirectoryPickerState ?: return
                if (state.requestedPath != requestedPath) return
                val result = response.optJSONObject("payload") ?: JSONObject()
                val directoriesJson = result.optJSONArray("directories")
                val directories = buildList {
                    if (directoriesJson != null) {
                        for (index in 0 until directoriesJson.length()) {
                            val item = directoriesJson.optJSONObject(index) ?: continue
                            val entryPath = item.optProtocolString("path")
                            if (entryPath.isBlank()) continue
                            add(
                                ProjectDirectoryEntry(
                                    name = item.optProtocolString("name").ifBlank {
                                        entryPath.substringAfterLast('/').ifBlank { entryPath }
                                    },
                                    path = entryPath,
                                ),
                            )
                        }
                    }
                }
                projectDirectoryPickerState =
                    state.copy(
                        currentPath = result.optProtocolString("path").ifBlank { requestedPath },
                        parentPath = result.optProtocolString("parent_path").takeIf { it.isNotBlank() },
                        directories = directories,
                        loading = false,
                        error = null,
                        truncated = result.optBoolean("truncated", false),
                    )
            }

            override fun onError(errorText: String) {
                val state = projectDirectoryPickerState ?: return
                if (state.requestedPath != requestedPath) return
                projectDirectoryPickerState = state.copy(loading = false, error = errorText)
            }

            override fun suppressDefaultErrorUi(): Boolean = true
        },
    )
    if (!sent) {
        projectDirectoryPickerState = loadingState.copy(loading = false, error = "Не удалось прочитать удалённый каталог")
    }
}

internal fun MainActivity.selectCurrentProjectDirectory() {
    val path = projectDirectoryPickerState?.currentPath?.trim().orEmpty()
    val draft = newChatDraft
    if (path.isBlank() || draft == null) return
    newChatDraft = draft.copy(projectPath = path)
    projectDirectoryPickerState = null
}

internal fun MainActivity.recentProjectDirectoryPaths(): List<String> {
    return sessions
        .sortedByDescending { it.updatedAt }
        .mapNotNull { it.cwd.trim().takeIf(String::isNotBlank) }
        .distinct()
        .take(5)
}
