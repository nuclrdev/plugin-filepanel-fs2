package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FreedesktopTrashTest {

	@Test
	void fileMovesIntoHomeTrashWithItsRecord(@TempDir Path dir) throws IOException {
		Path trashDir = dir.resolve("data/Trash");
		Path file = Files.writeString(dir.resolve("hello.txt"), "hi");

		new FreedesktopTrash(trashDir).moveToTrash(file);

		assertFalse(Files.exists(file));
		assertEquals("hi", Files.readString(trashDir.resolve("files/hello.txt")));
		String info = Files.readString(trashDir.resolve("info/hello.txt.trashinfo"), StandardCharsets.UTF_8);
		assertTrue(info.startsWith("[Trash Info]\n"), info);
		assertTrue(info.contains("\nPath=" + FreedesktopTrash.encodePath(file.toAbsolutePath().normalize().toString()) + "\n"), info);
		assertTrue(info.matches("(?s).*\nDeletionDate=\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\n"), info);
	}

	@Test
	void folderMovesWholeAndNameClashesGetANumber(@TempDir Path dir) throws IOException {
		Path trashDir = dir.resolve("Trash");
		FreedesktopTrash trash = new FreedesktopTrash(trashDir);
		Path first = Files.createDirectories(dir.resolve("a/text"));
		Files.writeString(first.resolve("one.txt"), "1");
		Path second = Files.createDirectories(dir.resolve("b/text"));

		trash.moveToTrash(first);
		trash.moveToTrash(second);

		assertEquals("1", Files.readString(trashDir.resolve("files/text/one.txt")));
		assertTrue(Files.isDirectory(trashDir.resolve("files/text.2")));
		assertTrue(Files.exists(trashDir.resolve("info/text.trashinfo")));
		assertTrue(Files.exists(trashDir.resolve("info/text.2.trashinfo")));
	}

	@Test
	void missingItemIsReportedAndLeavesNoRecord(@TempDir Path dir) {
		Path trashDir = dir.resolve("Trash");

		assertThrows(NoSuchFileException.class,
				() -> new FreedesktopTrash(trashDir).moveToTrash(dir.resolve("gone.txt")));
		assertFalse(Files.exists(trashDir.resolve("info/gone.txt.trashinfo")));
	}

	@Test
	void refusesToTrashAFolderHoldingTheTrash(@TempDir Path dir) throws IOException {
		Path home = Files.createDirectories(dir.resolve("home"));
		FreedesktopTrash trash = new FreedesktopTrash(home.resolve(".local/share/Trash"));

		assertThrows(IOException.class, () -> trash.moveToTrash(home));
		assertTrue(Files.isDirectory(home));
	}

	@Test
	void pathIsPercentEncodedButKeepsSlashes() {
		assertEquals("/home/u/name%20with%20spaces.txt", FreedesktopTrash.encodePath("/home/u/name with spaces.txt"));
		assertEquals("/%C3%9Cn%C3%AF.txt", FreedesktopTrash.encodePath("/Ünï.txt"));
		assertEquals("a-b_c.d~e/f", FreedesktopTrash.encodePath("a-b_c.d~e/f"));
	}
}
