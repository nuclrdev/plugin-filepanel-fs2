/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.support.TestResource;

/**
 * Tests the source-selection rule of {@link MoveService}. The public {@code move} entry point drives
 * Swing dialogs (setup / conflict / progress), so it is not unit-testable headless; the meaningful,
 * deterministic logic is {@code collectSources} — marked-selection-vs-cursor precedence plus the
 * filtering of the {@code ".."} parent entry and null-path resources — exercised here via reflection.
 */
class MoveServiceTest {

	private static Method collectSources;

	@BeforeAll
	static void lookup() throws Exception {
		collectSources = MoveService.class.getDeclaredMethod("collectSources", List.class, NuclrResource.class);
		collectSources.setAccessible(true);
	}

	@SuppressWarnings("unchecked")
	private static List<Path> collect(List<NuclrResource> selected, NuclrResource focused) throws Exception {
		return (List<Path>) collectSources.invoke(null, selected, focused);
	}

	@Test
	void usesMarkedSelectionWhenPresent() throws Exception {
		Path a = Path.of("a.txt");
		Path b = Path.of("b.txt");
		List<NuclrResource> selected = List.of(new TestResource(a), new TestResource(b));

		List<Path> result = collect(selected, new TestResource(Path.of("focused.txt")));

		assertEquals(List.of(a, b), result, "the marked selection wins over the cursor item");
	}

	@Test
	void fallsBackToFocusedWhenSelectionEmpty() throws Exception {
		Path focused = Path.of("focused.txt");

		List<Path> result = collect(List.of(), new TestResource(focused));

		assertEquals(List.of(focused), result);
	}

	@Test
	void skipsParentNavigationEntry() throws Exception {
		Path real = Path.of("real.txt");
		List<NuclrResource> selected = new ArrayList<>();
		selected.add(new TestResource(Path.of(".."), "..", true)); // the ".." pseudo-entry
		selected.add(new TestResource(real));

		List<Path> result = collect(selected, null);

		assertEquals(List.of(real), result, "the parent navigation entry is never moved");
	}

	@Test
	void skipsNullPathResources() throws Exception {
		Path real = Path.of("real.txt");
		List<NuclrResource> selected = new ArrayList<>();
		selected.add(new TestResource(null)); // no path -> skipped
		selected.add(new TestResource(real));

		List<Path> result = collect(selected, null);

		assertEquals(List.of(real), result);
	}

	@Test
	void returnsEmptyWhenNothingSelectedOrFocused() throws Exception {
		assertTrue(collect(null, null).isEmpty());
		assertTrue(collect(List.of(), null).isEmpty());
	}
}
