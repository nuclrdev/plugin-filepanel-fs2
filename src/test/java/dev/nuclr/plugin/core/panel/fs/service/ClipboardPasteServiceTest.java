/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipboardPasteServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 15, 30, 12);

	@TempDir
	Path dir;

	@Test
	void writesReadablePngWithTimestampedName() throws Exception {
		var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(1, 1, 0xFF336699);

		Path saved = ClipboardPasteService.saveImage(dir, image, NOW);

		assertEquals("Clipboard image 2026-10-04 153012.png", saved.getFileName().toString());
		BufferedImage read = ImageIO.read(saved.toFile());
		assertNotNull(read);
		assertEquals(3, read.getWidth());
		assertEquals(2, read.getHeight());
		assertEquals(0xFF336699, read.getRGB(1, 1));
	}

	@Test
	void imageNeverOverwritesAnExistingFile() throws Exception {
		Files.writeString(dir.resolve("Clipboard image 2026-10-04 153012.png"), "keep");
		var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

		Path second = ClipboardPasteService.saveImage(dir, image, NOW);
		Path third = ClipboardPasteService.saveImage(dir, image, NOW);

		assertEquals("Clipboard image 2026-10-04 153012 (2).png", second.getFileName().toString());
		assertEquals("Clipboard image 2026-10-04 153012 (3).png", third.getFileName().toString());
		assertEquals("keep", Files.readString(dir.resolve("Clipboard image 2026-10-04 153012.png")));
	}

	@Test
	void writesTextAsUtf8UnderTheEnteredName() throws Exception {
		Path saved = ClipboardPasteService.saveText(dir, "  notes.md ", "h\u00e9llo \u2713");

		assertEquals(dir.resolve("notes.md"), saved);
		assertEquals("h\u00e9llo \u2713", Files.readString(saved, StandardCharsets.UTF_8));
	}

	@Test
	void textUsesPlatformLineEndings() throws Exception {
		Path saved = ClipboardPasteService.saveText(dir, "a.txt", "one\ntwo\n");

		String sep = System.lineSeparator();
		assertEquals("one" + sep + "two" + sep, Files.readString(saved));
		assertEquals("one\r\ntwo", ClipboardPasteService.withPlatformLineEndings("one\r\ntwo"),
				"text that already has CR line breaks is kept as it is");
	}

	@Test
	void textRefusesTakenAndInvalidNames() throws Exception {
		Files.writeString(dir.resolve("taken.txt"), "keep");

		assertThrows(IllegalArgumentException.class, () -> ClipboardPasteService.saveText(dir, "taken.txt", "new"));
		assertThrows(IllegalArgumentException.class, () -> ClipboardPasteService.saveText(dir, "   ", "x"));
		assertThrows(IllegalArgumentException.class, () -> ClipboardPasteService.saveText(dir, "a/b.txt", "x"));
		assertThrows(IllegalArgumentException.class, () -> ClipboardPasteService.saveText(dir, "..", "x"));
		assertEquals("keep", Files.readString(dir.resolve("taken.txt")));
	}

	@Test
	void freeNameSkipsTakenNames() throws Exception {
		assertEquals("Clip.txt", ClipboardPasteService.freeName(dir, "Clip", ".txt"));
		Files.writeString(dir.resolve("Clip.txt"), "");
		Files.createDirectory(dir.resolve("Clip (2).txt"));

		assertEquals("Clip (3).txt", ClipboardPasteService.freeName(dir, "Clip", ".txt"));
	}

	@Test
	void pathLikeTextIsRecognised() {
		assertTrue(ClipboardPasteService.looksLikePath("C:\\Users\\me\\Docsuments"));
		assertTrue(ClipboardPasteService.looksLikePath("  \"d:/work/missing\"  "));
		assertTrue(ClipboardPasteService.looksLikePath("\\\\server\\share\\x"));
		assertTrue(ClipboardPasteService.looksLikePath("/usr/lcoal/bin"));
		assertTrue(ClipboardPasteService.looksLikePath("~"));
		assertTrue(ClipboardPasteService.looksLikePath("~/projects"));
	}

	@Test
	void ordinaryTextIsNotPathLike() {
		assertFalse(ClipboardPasteService.looksLikePath("hello world"));
		assertFalse(ClipboardPasteService.looksLikePath("C:\\one\nC:\\two"), "several lines are content");
		assertFalse(ClipboardPasteService.looksLikePath("~tilde words"));
		assertFalse(ClipboardPasteService.looksLikePath("https://nuclr.dev"));
		assertFalse(ClipboardPasteService.looksLikePath("   "));
		assertFalse(ClipboardPasteService.looksLikePath(null));
	}

	@Test
	void previewShowsTwoLinesAndMarksTheRest() {
		assertEquals("first\nsecond\n\u2026", ClipboardPasteService.preview("\n  first  \n\nsecond\nthird\n"));
		assertEquals("only", ClipboardPasteService.preview("only"));
		assertEquals("x".repeat(79) + "\u2026", ClipboardPasteService.preview("x".repeat(200)));
	}
}
