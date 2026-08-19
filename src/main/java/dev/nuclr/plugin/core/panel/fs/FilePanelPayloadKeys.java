/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs;

/** Shared payload keys used by the file-panel action and refresh services. */
public final class FilePanelPayloadKeys {

	public static final String RESULT_REFRESH = "result.refresh";
	public static final String RESULT_REFRESH_PATHS = "result.refresh.paths";
	public static final String RESULT_REFRESH_SELECTED_RESOURCE = "result.refresh.selected.resource";
	public static final String REFRESH_SOURCE_PLUGIN_UUID = "refresh.source.plugin.uuid";

	private FilePanelPayloadKeys() {
	}
}
