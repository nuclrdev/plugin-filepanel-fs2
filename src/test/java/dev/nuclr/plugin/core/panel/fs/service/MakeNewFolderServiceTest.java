/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the name-validation rule of {@link MakeNewFolderService}. The public
 * {@code makeNewFolder} entry point drives Swing dialogs (input prompt + error
 * popups), so it is not unit-testable headless; the meaningful, deterministic
 * logic is the single-segment folder-name check, exercised here via reflection.
 */
class MakeNewFolderServiceTest {

	private static Method isInvalid;

	@BeforeAll
	static void lookup() throws Exception {
		isInvalid = MakeNewFolderService.class.getDeclaredMethod("isInvalidSingleFolderName", String.class);
		isInvalid.setAccessible(true);
	}

	private static boolean invalid(String name) throws Exception {
		return (boolean) isInvalid.invoke(null, name);
	}

	@Test
	void rejectsDotAndDotDot() throws Exception {
		assertTrue(invalid("."));
		assertTrue(invalid(".."));
	}

	@Test
	void rejectsPathSeparatorsAndNul() throws Exception {
		assertTrue(invalid("a/b"));
		assertTrue(invalid("a\\b"));
		assertTrue(invalid("a\0b"));
		assertTrue(invalid("/leading"));
	}

	@Test
	void acceptsOrdinarySingleSegmentNames() throws Exception {
		assertFalse(invalid("New Folder"));
		assertFalse(invalid("project.v2"));
		assertFalse(invalid("a"));
		assertFalse(invalid("..dotsInside")); // not equal to ".." and has no separators
	}
}
