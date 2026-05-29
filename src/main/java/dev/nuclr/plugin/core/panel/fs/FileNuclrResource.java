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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class FileNuclrResource extends NuclrResource {

	public static final String Name = "Name";
	public static final String Size = "Size";
	public static final String Date = "Date";
	public static final String Time = "Time";

	private Path path;

	public FileNuclrResource(Path path) {

		this.path = path;

		try {
			this.metadata.put(Name, path.getFileName().toString());
		} catch (Exception e) {
			this.metadata.put(Name, path.toString());
		}
		
		try {
			this.metadata.put(Size, Files.size(path));
		} catch (Exception e) {
			this.metadata.put(Size, 0L);
		}

		try {
			this.metadata.put(Date, Files.getLastModifiedTime(path).toMillis());
		} catch (IOException e) {
			this.metadata.put(Date, 0L);
		}

		try {
			this.metadata.put(Time, Files.getLastModifiedTime(path).toMillis());
		} catch (IOException e) {
			this.metadata.put(Time, 0L);
		}

	}

	@Override
	public String getUuid() {
		return path.toAbsolutePath().toString();
	}

	@Override
	public String getName() {
		return this.metadata.get(Name).toString();
	}

	@Override
	public boolean isFolder() {
		return Files.isDirectory(path);
	}

	@Override
	public String getColumnValue(int columnIndex) {
		return this.metadata.getOrDefault(switch (columnIndex) {
		case 0 -> Name;
		case 1 -> Size;
		case 2 -> Date;
		case 3 -> Time;
		default -> throw new IllegalArgumentException("Invalid column index: " + columnIndex);
		}, "").toString();
	}

	@Override
	public boolean isSystem() {
		try {
			if (!Files.exists(path)) {
				return false;
			}

			// Windows: check DOS system attribute
			var dosAttrs = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

			return dosAttrs.isSystem();
		} catch (UnsupportedOperationException e) {
			// Non-Windows file systems usually do not support DOS attributes
			return false;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public boolean isHidden() {
		try {
			return Files.exists(path) && Files.isHidden(path);
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public boolean isParent() {
		return path != null && path.getParent() != null;
	}

	@Override
	public boolean isLink() {
		return path != null && Files.isSymbolicLink(path);
	}

	@Override
	public String getFullPath() {
		return path != null ? path.toAbsolutePath().normalize().toString() : "";
	}

	@Override
	public long getLength() {
		try {
			return Files.exists(path) && !Files.isDirectory(path) ? Files.size(path) : 0L;
		} catch (IOException e) {
			return 0L;
		}
	}

	@Override
	public LocalDateTime getLastAccessDateTime() {
		try {
			var attrs = Files.readAttributes(path, BasicFileAttributes.class);
			return attrs.lastAccessTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException e) {
			log.warn("Failed to read last access time for {}: {}", path, e.getMessage());
		}
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}

	@Override
	public LocalDateTime getCreateDateTime() {
		try {
			var attrs = Files.readAttributes(path, BasicFileAttributes.class);
			return attrs.creationTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException e) {
			log.warn("Failed to read creation time for {}: {}", path, e.getMessage());
		}
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}

	@Override
	public LocalDateTime getLastModifiedDateTime() {
		try {
			return Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException e) {
			log.warn("Failed to read last modified time for {}: {}", path, e.getMessage());
		}
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}

}
