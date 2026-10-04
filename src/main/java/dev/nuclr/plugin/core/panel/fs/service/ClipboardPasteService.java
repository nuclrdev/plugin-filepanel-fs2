/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * Saves clipboard content that is not a file or a path as a new file in the
 * panel's current folder:
 *
 * <ul>
 *   <li><b>Images</b> (a screenshot, an image copied in a browser or editor) are
 *       written straight away as {@code Clipboard image 2026-10-04 153012.png}.</li>
 *   <li><b>Text</b> is written as UTF-8 after the user confirms the name in a
 *       prompt pre-filled with {@code Clipboard text 2026-10-04 153012.txt}, so a
 *       stray Ctrl+V is undone with ESC.</li>
 * </ul>
 *
 * A {@code " (2)"}-style suffix keeps a generated name from replacing an existing file.
 */
@Slf4j
public final class ClipboardPasteService {

	private static final String IMAGE_TITLE = "Paste image";
	private static final String TEXT_TITLE = "Paste text";

	private static final DateTimeFormatter NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss");

	/** Drive-letter, UNC, root-relative and home-relative paths. */
	private static final Pattern PATH_LIKE = Pattern.compile("^([A-Za-z]:[\\\\/]|\\\\\\\\|/|~([\\\\/]|$)).*");

	private ClipboardPasteService() {
	}

	/**
	 * Write {@code image} into {@code currentFolder} as a PNG, reporting failures to the user.
	 *
	 * @return the created file, or {@code null} when nothing was written
	 */
	public static Path pasteImage(NuclrResource currentFolder, BufferedImage image, NuclrPluginContext context) {
		Path folder = writableFolder(currentFolder, IMAGE_TITLE, context);
		if (folder == null) {
			return null;
		}

		try {
			return saveImage(folder, image, LocalDateTime.now());
		} catch (IOException e) {
			log.warn("Failed to save clipboard image in [{}]: {}", folder, e.getMessage(), e);
			Alerts.showError(context, IMAGE_TITLE,
					e.getMessage() != null ? e.getMessage() : "Could not save the image.");
			return null;
		}
	}

	/**
	 * Ask for a file name and write {@code text} into {@code currentFolder} as UTF-8.
	 * The prompt is shown again after an invalid or taken name, keeping what was typed.
	 *
	 * @return the created file, or {@code null} when the user cancelled or nothing was written
	 */
	public static Path pasteText(NuclrResource currentFolder, String text, NuclrPluginContext context) {
		Path folder = writableFolder(currentFolder, TEXT_TITLE, context);
		if (folder == null) {
			return null;
		}

		String name = freeName(folder, "Clipboard text " + NAME_TIMESTAMP.format(LocalDateTime.now()), ".txt");
		String preview = preview(text);
		SoundEvents.popup(context);
		while (true) {
			final String suggested = name;
			final String[] entered = new String[1];
			Alerts.runOnEdtAndWait(() -> entered[0] = PasteTextDialog.showDialog(suggested, preview));
			if (entered[0] == null) {
				SoundEvents.cancel(context);
				return null;
			}
			name = entered[0];

			try {
				return saveText(folder, name, text);
			} catch (IllegalArgumentException e) {
				Alerts.showError(context, TEXT_TITLE, e.getMessage());
			} catch (IOException e) {
				log.warn("Failed to save clipboard text in [{}]: {}", folder, e.getMessage(), e);
				Alerts.showError(context, TEXT_TITLE,
						e.getMessage() != null ? e.getMessage() : "Could not save the text.");
				return null;
			}
		}
	}

	/**
	 * {@code true} when {@code text} is a single line written like an absolute or
	 * home-relative path. Such text that names nothing is a mistyped path, not
	 * content worth saving.
	 */
	public static boolean looksLikePath(String text) {
		if (text == null) {
			return false;
		}
		String line = text.strip();
		if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
			line = line.substring(1, line.length() - 1).strip();
		}
		return !line.isEmpty() && line.indexOf('\n') < 0 && line.indexOf('\r') < 0
				&& PATH_LIKE.matcher(line).matches();
	}

	private static Path writableFolder(NuclrResource currentFolder, String title, NuclrPluginContext context) {
		Path folder = currentFolder == null ? null : currentFolder.getPath();
		if (folder == null || !Files.isDirectory(folder)) {
			Alerts.showError(context, title, "No folder is open.");
			return null;
		}
		if (!Files.isWritable(folder)) {
			Alerts.showError(context, title, "The current folder is not writable.");
			return null;
		}
		return folder;
	}

	static Path saveImage(Path folder, BufferedImage image, LocalDateTime now) throws IOException {
		String baseName = "Clipboard image " + NAME_TIMESTAMP.format(now);
		for (int attempt = 1;; attempt++) {
			Path target = folder.resolve(numberedName(baseName, ".png", attempt));
			OutputStream out;
			try {
				// CREATE_NEW claims the name atomically, so a concurrent paste cannot overwrite it.
				out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			} catch (FileAlreadyExistsException e) {
				continue;
			}
			try (out) {
				if (!ImageIO.write(image, "png", out)) {
					throw new IOException("No PNG writer is available.");
				}
			} catch (IOException | RuntimeException e) {
				Files.deleteIfExists(target);
				throw e;
			}
			return target;
		}
	}

	static Path saveText(Path folder, String enteredName, String text) throws IOException {
		String name = enteredName == null ? "" : enteredName.trim();
		if (name.isBlank()) {
			throw new IllegalArgumentException("File name is required.");
		}
		if (CreateFileService.isInvalidSingleFileName(name)) {
			throw new IllegalArgumentException("File name cannot contain path separators.");
		}

		Path target;
		try {
			target = folder.resolve(name);
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("The file name is not valid.", e);
		}
		try {
			Files.writeString(target, withPlatformLineEndings(text), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (FileAlreadyExistsException e) {
			throw new IllegalArgumentException("A file or folder with that name already exists.", e);
		}
		return target;
	}

	/**
	 * AWT hands clipboard text over with {@code \n} line breaks; write them the
	 * way the platform's editors expect. Text that already carries {@code \r} is
	 * kept as it is.
	 */
	static String withPlatformLineEndings(String text) {
		if (text.indexOf('\r') >= 0 || "\n".equals(System.lineSeparator())) {
			return text;
		}
		return text.replace("\n", System.lineSeparator());
	}

	/** The first name in {@code base.ext}, {@code base (2).ext}, ... that is not taken in {@code folder}. */
	static String freeName(Path folder, String baseName, String extension) {
		for (int attempt = 1;; attempt++) {
			String name = numberedName(baseName, extension, attempt);
			if (!Files.exists(folder.resolve(name))) {
				return name;
			}
		}
	}

	private static String numberedName(String baseName, String extension, int attempt) {
		return attempt == 1 ? baseName + extension : baseName + " (" + attempt + ")" + extension;
	}

	/** The first two non-blank lines of {@code text}, each cut to a readable width. */
	static String preview(String text) {
		var lines = text.strip().lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty())
				.limit(3)
				.toList();
		var preview = new StringBuilder();
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			String line = lines.get(i);
			if (preview.length() > 0) {
				preview.append('\n');
			}
			preview.append(line.length() > 80 ? line.substring(0, 79) + "…" : line);
		}
		if (lines.size() > 2) {
			preview.append("\n…");
		}
		return preview.toString();
	}
}
