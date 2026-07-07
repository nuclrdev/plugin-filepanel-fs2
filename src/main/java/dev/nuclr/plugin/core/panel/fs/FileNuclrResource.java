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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FileNuclrResource extends NuclrResource {

	public static final List<String> ColumnNames = List.of(
			"Name",
			"Extension",
			"Size",
			"Type",
			"Modified",
			"Created",
			"Accessed",
			"Attributes",
			"Full Path");

	public static List<String> columnNamesFor(NuclrResource resource) {
		return ColumnNames;
	}

	private static final LocalDateTime EPOCH = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public FileNuclrResource(NuclrPluginContext ctx, Path path) {

		super(path);

		try {
			this.setName(path.getFileName().toString());
		} catch (Exception e) {
			this.setName(path.toString());
		}

		this.setFullPath(path.toAbsolutePath().normalize().toString());
		this.setUuid(path.toAbsolutePath().toString());

		// NOTE: do NOT probe Files.isReadable(path) here. On Windows that opens (and
		// closes) a handle to every file just to run an access check, which also wakes
		// Windows Defender to scan each one — turning a directory listing into seconds
		// of work. Far Manager stays instant because it reads the whole directory in a
		// single enumeration pass and never touches individual files. The `readable`
		// flag defaults to true and is only consulted by per-file actions (executable
		// quick-view, F4 editor), which can verify readability lazily when invoked.

		// Populate folder/size/link/system/hidden/timestamps from a SINGLE attribute
		// read (two only for the rare symlink). Reading each attribute via its own
		// Files.* call costs a separate syscall per attribute — ~12 per entry — which
		// is what makes large or network-mounted directories crawl.
		populateAttributes(path);

		this.getMetadata().put("Name", this.getName());
		this.getMetadata().put("Extension", extensionOf(this.getName()));

		if (isFolder()) {
			if (getName().equals("..")) {
				this.getMetadata().put("Size", "Up");
			} else {
				this.getMetadata().put("Size", "Folder");
			}
		} else {
			this.getMetadata().put("Size", FileUtils.byteCountToDisplaySize(this.getLength()));
		}

		this.getMetadata().put("Type", typeLabel());
		this.getMetadata().put("Modified", formatDateTime(this.getLastModifiedDateTime()));
		this.getMetadata().put("Created", formatDateTime(this.getCreatedDateTime()));
		this.getMetadata().put("Accessed", formatDateTime(this.getLastAccessDateTime()));
		this.getMetadata().put("Attributes", attributesLabel());
		this.getMetadata().put("Full Path", this.getFullPath());

	}

	public void setName(String name) {
		this.name = name;
		this.getMetadata().put("Name", name);
	}

	/**
	 * Read every displayed attribute in one shot.
	 *
	 * <p>
	 * On Windows {@link DosFileAttributes} extends {@link BasicFileAttributes} and
	 * additionally yields the system/hidden DOS flags, so a single
	 * {@code readAttributes} covers folder, size, all three timestamps, system and
	 * hidden. We read with {@code NOFOLLOW_LINKS} so the symlink itself is
	 * inspected (no network round-trip to a possibly-dead target, and no risk of a
	 * cyclic link); only when the entry actually is a symlink do we follow it once
	 * to learn whether its target is a directory.
	 */
	private void populateAttributes(Path path) {

		BasicFileAttributes attrs = null;
		boolean system = false;
		boolean hidden = false;

		try {
			if (SystemUtils.IS_OS_WINDOWS) {
				DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				attrs = dos;
				system = dos.isSystem();
				hidden = dos.isHidden();
			} else {
				attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				// POSIX convention: a leading dot marks a hidden entry (Files.isHidden does
				// exactly this on Unix, but as a separate syscall).
				String n = getName();
				hidden = n != null && n.startsWith(".") && !n.equals(".") && !n.equals("..");
			}
		} catch (IOException | UnsupportedOperationException e) {
			// Unreadable entry (permissions, broken mount, …) — surface it with safe
			// defaults rather than dropping it from the listing.
			log.debug("Failed to read attributes for {}: {}", path, e.getMessage());
		}

		if (attrs == null) {
			setFolder(false);
			setLength(0L);
			setLink(false);
			setSystem(false);
			setHidden(hidden);
			setLastModifiedDateTime(EPOCH);
			setCreatedDateTime(EPOCH);
			setLastAccessDateTime(EPOCH);
			return;
		}

		boolean link = attrs.isSymbolicLink();
		boolean directory;
		long length;

		if (link) {
			// Resolve the target's type once, following the link. A broken or cyclic
			// link simply fails here and is shown as a (non-directory) link.
			BasicFileAttributes target = readFollowing(path);
			directory = target != null && target.isDirectory();
			length = (target != null && !directory) ? target.size() : 0L;
		} else {
			directory = attrs.isDirectory();
			length = directory ? 0L : attrs.size();
		}

		setFolder(directory);
		setLength(length);
		setLink(link);
		setSystem(system);
		setHidden(hidden);
		setLastModifiedDateTime(toLocal(attrs.lastModifiedTime()));
		setCreatedDateTime(toLocal(attrs.creationTime()));
		setLastAccessDateTime(toLocal(attrs.lastAccessTime()));
	}

	private static BasicFileAttributes readFollowing(Path path) {
		try {
			return Files.readAttributes(path, BasicFileAttributes.class);
		} catch (IOException e) {
			return null;
		}
	}

	private static LocalDateTime toLocal(FileTime time) {
		return time.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
	}

	static String formatDateTime(LocalDateTime date) {
		if (date == null) {
			return "-";
		}
		return date.format(DISPLAY_DATE_TIME);
	}

	private String typeLabel() {
		if (isFolder()) {
			return isLink() ? "Folder link" : "Folder";
		}
		return isLink() ? "File link" : "File";
	}

	private String attributesLabel() {
		StringBuilder attributes = new StringBuilder();
		if (isFolder()) {
			attributes.append('D');
		}
		if (isLink()) {
			attributes.append('L');
		}
		if (isHidden()) {
			attributes.append('H');
		}
		if (isSystem()) {
			attributes.append('S');
		}
		return attributes.length() == 0 ? "-" : attributes.toString();
	}

	private static String extensionOf(String name) {
		if (name == null || name.equals("..")) {
			return "";
		}
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			return "";
		}
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	public InputStream openInputStream(OpenOption... options) throws Exception {
		return Files.newInputStream(path, options);
	}

}
