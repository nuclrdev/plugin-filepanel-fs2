/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.support;

import java.util.Locale;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.plugin.NuclrPluginContext;

/**
 * Minimal {@link NuclrPluginContext} for tests. Exposes a {@link FakeEventBus}
 * and a fixed {@link Locale} (the only context method the plugin actually uses
 * in the code paths under test). Theme and settings are not needed by those
 * paths and return {@code null}.
 */
public final class FakeContext implements NuclrPluginContext {

	public final FakeEventBus eventBus = new FakeEventBus();
	private final Locale locale;

	public FakeContext() {
		this(Locale.US);
	}

	public FakeContext(Locale locale) {
		this.locale = locale;
	}

	@Override
	public NuclrEventBus getEventBus() {
		return eventBus;
	}

	@Override
	public NuclrThemeScheme getTheme() {
		return null;
	}

	@Override
	public NuclrSettings getSettings() {
		return null;
	}

	@Override
	public Locale getLocale() {
		return locale;
	}
}
