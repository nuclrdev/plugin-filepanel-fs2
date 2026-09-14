/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

	@Test
	void refreshPayloadContainsSourceParentsDestinationAndRefreshFlag() {
		Map<String, Object> data = new HashMap<>();
		Path firstParent = Path.of("first");
		Path secondParent = Path.of("second");
		Path destination = Path.of("destination");

		MoveService.putRefreshResults(data,
				List.of(firstParent.resolve("a.txt"), secondParent.resolve("b.txt")), destination);

		assertEquals(Boolean.TRUE,
				data.get(dev.nuclr.plugin.core.panel.fs.FilePanelPayloadKeys.RESULT_REFRESH));
		assertEquals(List.of(firstParent, secondParent, destination),
				data.get(dev.nuclr.plugin.core.panel.fs.FilePanelPayloadKeys.RESULT_REFRESH_PATHS));
	}

	// ---- resolveTarget: how the typed destination is interpreted ----------------------------

	/** A bare name renames in place: it resolves against the file's folder, not the working dir. */
	@Test
	void nameOnlyRenamesInSourceFolder(@TempDir Path dir) throws IOException {
		Path src = Files.createDirectory(dir.resolve("src"));
		Path dst = Files.createDirectory(dir.resolve("dst"));
		Path file = Files.writeString(src.resolve("a.txt"), "x");

		MoveService.MoveTarget target = MoveService.resolveTarget(Path.of("b.txt"), false, List.of(file),
				dst.resolve("a.txt"));

		assertEquals(src.resolve("b.txt"), target.destination());
		assertTrue(target.explicit());
	}

	@Test
	void relativePathResolvesAgainstSourceFolder(@TempDir Path dir) throws IOException {
		Path src = Files.createDirectory(dir.resolve("src"));
		Path file = Files.writeString(src.resolve("a.txt"), "x");

		MoveService.MoveTarget target = MoveService.resolveTarget(Path.of("..", "other", "b.txt"), false,
				List.of(file), null);

		assertEquals(dir.resolve("other").resolve("b.txt"), target.destination());
		assertTrue(target.explicit());
	}

	@Test
	void relativeExistingFolderIsMovedInto(@TempDir Path dir) throws IOException {
		Path src = Files.createDirectory(dir.resolve("src"));
		Path sub = Files.createDirectory(src.resolve("sub"));
		Path file = Files.writeString(src.resolve("a.txt"), "x");

		MoveService.MoveTarget target = MoveService.resolveTarget(Path.of("sub"), false, List.of(file), null);

		assertEquals(sub, target.destination());
		assertFalse(target.explicit());
	}

	/** The pre-filled folder kept with a new name: rename and move. */
	@Test
	void fullPathWithNewNameIsExplicitTarget(@TempDir Path dir) throws IOException {
		Path src = Files.createDirectory(dir.resolve("src"));
		Path dst = Files.createDirectory(dir.resolve("dst"));
		Path file = Files.writeString(src.resolve("a.txt"), "x");

		MoveService.MoveTarget target = MoveService.resolveTarget(dst.resolve("b.txt"), false, List.of(file),
				dst.resolve("a.txt"));

		assertEquals(dst.resolve("b.txt"), target.destination());
		assertTrue(target.explicit());
	}

	/** Unchanged pre-fill for a folder whose name already exists there: merge, never nest foo\foo. */
	@Test
	void unchangedPrefillMergesIntoSameNamedFolder(@TempDir Path dir) throws IOException {
		Path src = Files.createDirectory(dir.resolve("src"));
		Path dst = Files.createDirectory(dir.resolve("dst"));
		Path folder = Files.createDirectory(src.resolve("foo"));
		Path existing = Files.createDirectory(dst.resolve("foo"));

		MoveService.MoveTarget target = MoveService.resolveTarget(existing, false, List.of(folder), existing);

		assertEquals(existing, target.destination());
		assertTrue(target.explicit());
	}

	/** Typing the folder's own name (in place, unchanged) is a self-move, never foo\foo. */
	@Test
	void sourceItselfIsExplicitTarget(@TempDir Path dir) throws IOException {
		Path folder = Files.createDirectory(dir.resolve("foo"));

		MoveService.MoveTarget target = MoveService.resolveTarget(Path.of("foo"), false, List.of(folder), folder);

		assertEquals(folder, target.destination());
		assertTrue(target.explicit());
		assertTrue(MoveEngine.isSelfMove(folder, target.destination()));
	}

	@Test
	void trailingSeparatorMeansMoveIntoFolder(@TempDir Path dir) throws IOException {
		Path src = Files.createDirectory(dir.resolve("src"));
		Path file = Files.writeString(src.resolve("a.txt"), "x");
		Path newFolder = dir.resolve("new-folder"); // does not exist yet

		MoveService.MoveTarget target = MoveService.resolveTarget(newFolder, true, List.of(file), null);

		assertEquals(newFolder, target.destination());
		assertFalse(target.explicit());
	}

	@Test
	void multipleSourcesResolveRelativeFolderAgainstSourceFolder(@TempDir Path dir) throws IOException {
		Path src = Files.createDirectory(dir.resolve("src"));
		Path a = Files.writeString(src.resolve("a.txt"), "a");
		Path b = Files.writeString(src.resolve("b.txt"), "b");

		MoveService.MoveTarget target = MoveService.resolveTarget(Path.of("backup"), false, List.of(a, b), null);

		assertEquals(src.resolve("backup"), target.destination());
		assertFalse(target.explicit());
	}

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
