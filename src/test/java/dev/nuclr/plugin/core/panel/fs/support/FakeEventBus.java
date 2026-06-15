/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginCallback;

/**
 * In-memory {@link NuclrEventBus} that records every emit and tracks subscriptions,
 * so tests can assert on the events a plugin published and on its subscribe/unsubscribe
 * lifecycle without a running commander.
 */
public final class FakeEventBus implements NuclrEventBus {

	/** A single recorded {@code emit(...)} call. */
	public static final class Emission {
		public final Object source;
		public final String type;
		public final Map<String, Object> event;
		public final NuclrPluginCallback callback;

		Emission(Object source, String type, Map<String, Object> event, NuclrPluginCallback callback) {
			this.source = source;
			this.type = type;
			this.event = event;
			this.callback = callback;
		}
	}

	public final List<Emission> emissions = new ArrayList<>();
	public final List<NuclrEventListener> listeners = new ArrayList<>();
	public int subscribeCount = 0;
	public int unsubscribeCount = 0;

	/** @return every emission whose type equals {@code type}. */
	public List<Emission> emissionsOfType(String type) {
		List<Emission> out = new ArrayList<>();
		for (Emission e : emissions) {
			if (type.equals(e.type)) {
				out.add(e);
			}
		}
		return out;
	}

	@Override
	public void emit(Object source, String type, Map<String, Object> event, NuclrPluginCallback callback) {
		emissions.add(new Emission(source, type, event, callback));
	}

	@Override
	public void emit(Object source, String type, Map<String, Object> event) {
		emissions.add(new Emission(source, type, event, null));
	}

	@Override
	public void emit(String type, Map<String, Object> event, NuclrPluginCallback callback) {
		emissions.add(new Emission(null, type, event, callback));
	}

	@Override
	public void emit(String type, NuclrPluginCallback callback) {
		emissions.add(new Emission(null, type, null, callback));
	}

	@Override
	public void emit(String type) {
		emissions.add(new Emission(null, type, null, null));
	}

	@Override
	public void subscribe(NuclrEventListener listener) {
		subscribeCount++;
		listeners.add(listener);
	}

	@Override
	public void unsubscribe(NuclrEventListener listener) {
		unsubscribeCount++;
		listeners.remove(listener);
	}
}
