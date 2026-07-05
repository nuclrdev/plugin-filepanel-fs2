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
package dev.nuclr.plugin.core.panel.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Gathers volume information (space, label, serial number) for a filesystem path in a
 * cross-platform way.
 *
 * <p>Total/available space come from the portable {@link FileStore} API and are always
 * accurate. Label and serial number have no cross-platform JDK API, so they are resolved
 * per-OS on a best-effort basis:
 * <ul>
 *   <li><b>Windows</b> — the JDK's {@code WindowsFileStore} exposes the label via
 *       {@link FileStore#name()} and the volume serial number via the
 *       {@code "volume:vsn"} attribute (formatted {@code XXXX-XXXX}).</li>
 *   <li><b>Linux</b> — shells out to {@code lsblk}/{@code blkid} for the filesystem
 *       LABEL and UUID (the closest analogue to a Windows serial).</li>
 *   <li><b>macOS</b> — shells out to {@code diskutil info} for the Volume Name and UUID.</li>
 * </ul>
 * Any value that cannot be determined is returned as {@code null}; the dialog renders
 * those as a dash.
 */
@Slf4j
final class DriveInfoService {

	private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	private static final boolean IS_WINDOWS = OS.contains("win");
	private static final boolean IS_MAC = OS.contains("mac") || OS.contains("darwin");

	private DriveInfoService() {
	}

	/** Immutable snapshot of a volume's information. Any of the string fields may be {@code null}. */
	record DriveInfo(Path path, long totalBytes, long usableBytes, String label, String serialNumber) {

		/** Percentage of space that is available (0..100), or -1 when total is unknown. */
		int availablePercent() {
			if (totalBytes <= 0) {
				return -1;
			}
			return (int) Math.round(usableBytes * 100.0 / totalBytes);
		}
	}

	/**
	 * Resolve the drive information for {@code path}. Space is always populated; label and
	 * serial are best-effort. Never throws — failures degrade to {@code null} fields.
	 */
	static DriveInfo inspect(Path path) {

		long total = 0L;
		long usable = 0L;
		String label = null;
		String serial = null;

		try {
			FileStore store = Files.getFileStore(path);
			total = store.getTotalSpace();
			usable = store.getUsableSpace();

			if (IS_WINDOWS) {
				label = blankToNull(store.name());
				serial = windowsSerial(store);
			}
		} catch (IOException e) {
			log.warn("Failed to read file store for {}: {}", path, e.getMessage());
		}

		// Non-Windows label/serial (and Windows fallbacks) resolved via OS tools.
		if (!IS_WINDOWS) {
			String[] labelAndSerial = IS_MAC ? macVolumeInfo(path) : linuxVolumeInfo(path);
			if (labelAndSerial != null) {
				if (label == null) {
					label = blankToNull(labelAndSerial[0]);
				}
				if (serial == null) {
					serial = blankToNull(labelAndSerial[1]);
				}
			}
		}

		return new DriveInfo(path, total, usable, label, serial);
	}

	/** Windows volume serial number via the JDK's {@code "volume:vsn"} attribute, formatted {@code XXXX-XXXX}. */
	private static String windowsSerial(FileStore store) {
		try {
			Object vsn = store.getAttribute("volume:vsn");
			if (vsn instanceof Integer i) {
				int v = i;
				return String.format("%04X-%04X", (v >>> 16) & 0xFFFF, v & 0xFFFF);
			}
		} catch (IOException | UnsupportedOperationException e) {
			log.debug("volume:vsn unavailable for {}: {}", store, e.getMessage());
		}
		return null;
	}

	/**
	 * Linux label + UUID via {@code lsblk} (preferred, no root needed) with a {@code blkid}
	 * fallback. Returns {@code [label, uuid]} or {@code null}.
	 */
	private static String[] linuxVolumeInfo(Path path) {
		String device = deviceFor(path);
		if (device == null) {
			return null;
		}

		// lsblk -nro LABEL,UUID <device>  ->  "MyLabel abcd-1234" (label may be empty)
		String out = runAndRead(List.of("lsblk", "-nro", "LABEL,UUID", device));
		if (out != null && !out.isBlank()) {
			String line = out.strip();
			int lastSpace = line.lastIndexOf(' ');
			if (lastSpace < 0) {
				// Only one token — could be UUID (unlabeled) or label with no UUID.
				return line.contains("-") ? new String[] { null, line } : new String[] { line, null };
			}
			return new String[] { line.substring(0, lastSpace).strip(), line.substring(lastSpace + 1).strip() };
		}

		// blkid -o export <device>  ->  LABEL=... / UUID=...
		String blkid = runAndRead(List.of("blkid", "-o", "export", device));
		if (blkid != null) {
			return new String[] { grep(blkid, "LABEL="), grep(blkid, "UUID=") };
		}

		return null;
	}

	/** The backing device for {@code path}, e.g. {@code /dev/sda1}, or {@code null}. */
	private static String deviceFor(Path path) {
		try {
			return blankToNull(Files.getFileStore(path).name());
		} catch (IOException e) {
			return null;
		}
	}

	/** macOS Volume Name + Volume UUID via {@code diskutil info}. Returns {@code [label, uuid]} or {@code null}. */
	private static String[] macVolumeInfo(Path path) {
		String out = runAndRead(List.of("diskutil", "info", path.toString()));
		if (out == null) {
			return null;
		}
		String name = grepAfterColon(out, "Volume Name:");
		String uuid = grepAfterColon(out, "Volume UUID:");
		return new String[] { name, uuid };
	}

	// ---- process + parsing helpers -------------------------------------------------

	/** Run a command with a short timeout and return its stdout, or {@code null} on any failure. */
	private static String runAndRead(List<String> command) {
		Process process = null;
		try {
			process = new ProcessBuilder(command)
					.redirectErrorStream(false)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();

			String output;
			try (InputStream in = process.getInputStream()) {
				output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}

			if (!process.waitFor(3, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return null;
			}
			return process.exitValue() == 0 ? output : null;
		} catch (IOException e) {
			log.debug("Command {} failed: {}", command.get(0), e.getMessage());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	/** Return the value following {@code key=} on a KEY=VALUE line (quotes stripped), or {@code null}. */
	private static String grep(String text, String key) {
		for (String line : text.split("\\R")) {
			String trimmed = line.strip();
			if (trimmed.startsWith(key)) {
				return blankToNull(trimmed.substring(key.length()).replace("\"", "").strip());
			}
		}
		return null;
	}

	/** Return the text after {@code key} (a "Label:" style prefix) on the first matching line, or {@code null}. */
	private static String grepAfterColon(String text, String key) {
		for (String line : text.split("\\R")) {
			int idx = line.indexOf(key);
			if (idx >= 0) {
				return blankToNull(line.substring(idx + key.length()).strip());
			}
		}
		return null;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
