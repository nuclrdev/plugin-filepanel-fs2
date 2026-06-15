/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;

/**
 * A stand-in "other" file-panel plugin used to verify that
 * {@code LocalFileSystemPlugin.act} delegates a copy to a different plugin. It
 * records every {@code act(...)} call. {@link FilePanelNuclrPlugin} is a non-sealed
 * interface, so it can be implemented directly in tests (Mockito cannot mock the
 * sealed {@code BaseNuclrPlugin} hierarchy reliably).
 */
public final class RecordingFilePanelPlugin implements FilePanelNuclrPlugin {

	/** A single recorded {@code act(...)} invocation. */
	public static final class ActCall {
		public final BaseNuclrPlugin other;
		public final String actionType;
		public final List<NuclrResource> selected;
		public final NuclrResource focused;

		ActCall(BaseNuclrPlugin other, String actionType, List<NuclrResource> selected, NuclrResource focused) {
			this.other = other;
			this.actionType = actionType;
			this.selected = selected;
			this.focused = focused;
		}
	}

	private final String id;
	private final String uuid;
	public final List<ActCall> actCalls = new ArrayList<>();

	public RecordingFilePanelPlugin(String id, String uuid) {
		this.id = id;
		this.uuid = uuid;
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		actCalls.add(new ActCall(other, actionType, selectedResources, focusedResource));
	}

	// --- identity used by the delegation logic ---
	@Override
	public String id() {
		return id;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	// --- remaining contract: inert stubs ---
	@Override
	public String name() {
		return "Recording Plugin";
	}

	@Override
	public String version() {
		return "0.0.0";
	}

	@Override
	public String description() {
		return "test";
	}

	@Override
	public String author() {
		return "test";
	}

	@Override
	public String license() {
		return "Apache-2.0";
	}

	@Override
	public String website() {
		return null;
	}

	@Override
	public String pageUrl() {
		return null;
	}

	@Override
	public String docUrl() {
		return null;
	}

	@Override
	public Developer developer() {
		return Developer.Community;
	}

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
	}

	@Override
	public NuclrPluginContext getContext() {
		return null;
	}

	@Override
	public void init() {
	}

	@Override
	public void unload() {
	}

	@Override
	public void closeResource() {
	}

	@Override
	public NuclrResource getCurrentResource() {
		return null;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return false;
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return null;
	}

	@Override
	public String getCurrentLocationDisplayText() {
		return "";
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		return "";
	}
}
