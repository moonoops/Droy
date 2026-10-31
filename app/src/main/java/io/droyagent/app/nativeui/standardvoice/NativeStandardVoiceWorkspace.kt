package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeWorkspace

/** Wake-up creates ordinary work; a managed partner space cannot own its new conversation. */
internal fun standardVoiceFreshWorkspace(selected: String?, default: String?, workspaces: List<NativeWorkspace>): String? {
    if (selected == null || workspaces.any { it.id == selected && !it.readOnly }) return selected
    return default?.takeIf { id -> workspaces.any { it.id == id && !it.readOnly } }
}
