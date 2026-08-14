/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.LocalFileSystemPlugin;
import dev.nuclr.plugin.core.panel.fs.support.RecordingFilePanelPlugin.ActCall;

/**
 * A stand-in peer instance of the FS panel: the second pane, when it too is a
 * local filesystem panel. It records every {@code act(...)} call instead of
 * performing it.
 *
 * <p>Since SDK 4.0.0 a plugin cannot ask another plugin for its plugin id, so
 * {@code LocalFileSystemPlugin.act} recognises a peer FS pane by type rather
 * than by id. A generic fake therefore no longer stands in for one — the peer
 * has to actually be a {@code LocalFileSystemPlugin}.
 */
public final class RecordingLocalFileSystemPlugin extends LocalFileSystemPlugin {

	public final List<ActCall> actCalls = new ArrayList<>();

	public RecordingLocalFileSystemPlugin(String uuid) {
		this.uuid = uuid;
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		actCalls.add(new ActCall(other, actionType, selectedResources, focusedResource));
	}
}
