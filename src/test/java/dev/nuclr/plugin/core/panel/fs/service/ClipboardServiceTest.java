/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.support.TestResource;

/**
 * Tests the pure path-resolution logic of {@link ClipboardService}. The public
 * {@code showClipboardMenu} entry point and the {@code copyFullPaths}/{@code copyFiles} actions drive
 * Swing (a popup menu) and the AWT system clipboard, neither available headless; the meaningful,
 * deterministic logic is {@code pathText}/{@code nameText} — the {@code ".."}-to-current-folder
 * substitution and the path/fullPath/name fallback chains — plus the {@code isParent} predicate,
 * exercised via reflection.
 */
class ClipboardServiceTest {

	private static Method pathText;
	private static Method nameText;
	private static Method isParent;

	@BeforeAll
	static void lookup() throws Exception {
		pathText = ClipboardService.class.getDeclaredMethod("pathText", NuclrResource.class, NuclrResource.class);
		pathText.setAccessible(true);
		nameText = ClipboardService.class.getDeclaredMethod("nameText", NuclrResource.class, NuclrResource.class);
		nameText.setAccessible(true);
		isParent = ClipboardService.class.getDeclaredMethod("isParent", NuclrResource.class);
		isParent.setAccessible(true);
	}

	private static String pathText(NuclrResource resource, NuclrResource currentFolder) throws Exception {
		return (String) pathText.invoke(null, resource, currentFolder);
	}

	private static String nameText(NuclrResource resource, NuclrResource currentFolder) throws Exception {
		return (String) nameText.invoke(null, resource, currentFolder);
	}

	private static boolean isParent(NuclrResource resource) throws Exception {
		return (boolean) isParent.invoke(null, resource);
	}

	@Test
	void parentEntryIsDetected() throws Exception {
		assertTrue(isParent(new TestResource(Path.of(".."), "..", true)));
		assertFalse(isParent(new TestResource(Path.of("a.txt"))));
		assertFalse(isParent(null));
	}

	@Test
	void regularResourceYieldsItsAbsolutePath() throws Exception {
		Path file = Path.of("some", "a.txt");
		String text = pathText(new TestResource(file), null);

		assertEquals(file.toAbsolutePath().toString(), text);
	}

	@Test
	void parentEntrySubstitutesCurrentFolder() throws Exception {
		Path folder = Path.of("current", "folder");
		NuclrResource parent = new TestResource(Path.of(".."), "..", true);

		String text = pathText(parent, new TestResource(folder));

		assertEquals(folder.toAbsolutePath().toString(), text, "the \"..\" entry contributes the open folder's path");
	}

	@Test
	void parentEntryWithoutCurrentFolderYieldsEmptyString() throws Exception {
		NuclrResource parent = new TestResource(Path.of(".."), "..", true);

		assertEquals("", pathText(parent, null));
	}

	@Test
	void resourceWithoutPathFallsBackToFullPath() throws Exception {
		TestResource noPath = new TestResource(null);
		noPath.setName("orphan");
		noPath.setFullPath("X:/typed/full/path");

		assertEquals("X:/typed/full/path", pathText(noPath, null));
	}

	@Test
	void resourceWithoutPathOrFullPathFallsBackToName() throws Exception {
		TestResource bare = new TestResource(null);
		bare.setName("just-a-name");
		bare.setFullPath(null);

		assertEquals("just-a-name", pathText(bare, null));
	}

	@Test
	void regularResourceYieldsItsBareName() throws Exception {
		String text = nameText(new TestResource(Path.of("some", "deep", "a.txt")), null);

		assertEquals("a.txt", text, "only the last segment goes onto the clipboard");
	}

	@Test
	void parentEntrySubstitutesCurrentFolderName() throws Exception {
		NuclrResource parent = new TestResource(Path.of(".."), "..", true);

		String text = nameText(parent, new TestResource(Path.of("current", "folder")));

		assertEquals("folder", text, "the \"..\" entry contributes the open folder's name");
	}

	@Test
	void parentEntryWithoutCurrentFolderYieldsEmptyName() throws Exception {
		NuclrResource parent = new TestResource(Path.of(".."), "..", true);

		assertEquals("", nameText(parent, null));
	}

	@Test
	void filesystemRootYieldsTheRootItself() throws Exception {
		Path root = Path.of("/").toAbsolutePath().getRoot();

		assertEquals(root.toString(), nameText(new TestResource(root), null),
				"a root has no file-name element, so the root string is used");
	}

	@Test
	void resourceWithoutPathFallsBackToName() throws Exception {
		TestResource noPath = new TestResource(null);
		noPath.setName("orphan");
		noPath.setFullPath("X:/typed/full/path");

		assertEquals("orphan", nameText(noPath, null));
	}
}
