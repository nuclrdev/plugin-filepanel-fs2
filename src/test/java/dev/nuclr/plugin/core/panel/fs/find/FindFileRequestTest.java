/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** Validity and value-semantics tests for {@link FindFileRequest} construction. */
class FindFileRequestTest {

	@Test
	void defaultsAreSensible() {
		FindFileRequest r = FindFileRequest.builder().build();

		assertEquals("*", r.getNamePattern());
		assertEquals("", r.getContainingText());
		assertEquals(ContentMatchMode.TEXT, r.getContentMode());
		assertEquals(ScopeType.CURRENT_FOLDER, r.getScopeType());
		assertTrue(r.isSearchSubfolders());
		assertFalse(r.isFollowSymlinks());
		assertFalse(r.isIncludeHidden());
		assertEquals(StandardCharsets.UTF_8, r.getEncoding());
		assertFalse(r.hasContentQuery());
		assertFalse(r.hasNameQuery());
	}

	@Test
	void blankNameAndContainingIsInvalid() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> FindFileRequest.builder().namePattern("   ").containingText("").build());
		assertTrue(ex.getMessage().toLowerCase().contains("name") || ex.getMessage().toLowerCase().contains("query"));
	}

	@Test
	void containingOnlyIsValid() {
		FindFileRequest r = FindFileRequest.builder().namePattern("").containingText("needle").build();
		assertTrue(r.hasContentQuery());
		assertTrue(r.getNamePattern().isEmpty());
	}

	@Test
	void namePatternIsTrimmed() {
		FindFileRequest r = FindFileRequest.builder().namePattern("  *.java  ").build();
		assertEquals("*.java", r.getNamePattern());
		assertTrue(r.hasNameQuery());
	}

	@Test
	void customPathScopeRequiresPath() {
		assertThrows(IllegalArgumentException.class,
				() -> FindFileRequest.builder().scopeType(ScopeType.CUSTOM_PATH).customPath("  ").build());

		FindFileRequest r = FindFileRequest.builder()
				.scopeType(ScopeType.CUSTOM_PATH).customPath("C:/tmp").build();
		assertEquals("C:/tmp", r.getCustomPath());
	}

	@Test
	void invalidRegexRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> FindFileRequest.builder().containingText("a(b").contentMode(ContentMatchMode.REGEX).build());
	}

	@Test
	void validRegexAccepted() {
		FindFileRequest r = FindFileRequest.builder()
				.containingText("foo.*bar").contentMode(ContentMatchMode.REGEX).build();
		assertEquals(ContentMatchMode.REGEX, r.getContentMode());
	}

	@Test
	void hexQueryValidation() {
		assertThrows(IllegalArgumentException.class, () -> hex("ABC")); // odd digits
		assertThrows(IllegalArgumentException.class, () -> hex("ZZ")); // non-hex
		FindFileRequest r = hex("89 50 4E 47");
		assertEquals(ContentMatchMode.HEX, r.getContentMode());
	}

	private static FindFileRequest hex(String query) {
		return FindFileRequest.builder().containingText(query).contentMode(ContentMatchMode.HEX).build();
	}

	@Test
	void sizeRangeValidation() {
		assertThrows(IllegalArgumentException.class,
				() -> FindFileRequest.builder().minSizeBytes(-1L).build());
		assertThrows(IllegalArgumentException.class,
				() -> FindFileRequest.builder().minSizeBytes(100L).maxSizeBytes(10L).build());

		FindFileRequest r = FindFileRequest.builder().minSizeBytes(10L).maxSizeBytes(100L).build();
		assertEquals(10L, r.getMinSizeBytes());
		assertEquals(100L, r.getMaxSizeBytes());
	}

	@Test
	void dateRangeValidation() {
		LocalDateTime from = LocalDateTime.of(2026, 6, 28, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 1, 1, 0, 0);
		assertThrows(IllegalArgumentException.class,
				() -> FindFileRequest.builder().modifiedFrom(from).modifiedTo(to).build());
	}

	@Test
	void nullContentModeRejected() {
		assertThrows(NullPointerException.class,
				() -> FindFileRequest.builder().contentMode(null).build());
	}

	@Test
	void equalsAndHashCode() {
		FindFileRequest a = FindFileRequest.builder().namePattern("*.txt").containingText("x").build();
		FindFileRequest b = FindFileRequest.builder().namePattern("*.txt").containingText("x").build();
		FindFileRequest c = FindFileRequest.builder().namePattern("*.md").containingText("x").build();

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
	}

	@Test
	void rootsAreDefensivelyCopiedAndUnmodifiable() {
		FindFileRequest r = FindFileRequest.builder().build();
		assertTrue(r.getRoots().isEmpty());
		assertThrows(UnsupportedOperationException.class,
				() -> r.getRoots().add(new dev.nuclr.plugin.core.panel.fs.support.TestResource(null)));
	}

	@Test
	void toStringMentionsScopeAndName() {
		FindFileRequest r = FindFileRequest.builder().namePattern("*.java").build();
		String s = r.toString();
		assertTrue(s.contains("CURRENT_FOLDER"));
		assertTrue(s.contains("*.java"));
		assertNull(r.getModifiedFrom());
	}
}
