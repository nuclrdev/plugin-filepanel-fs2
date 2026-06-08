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

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.commons.lang3.SerializationUtils;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class Helper {

	public static NuclrResource copy(final NuclrResource source) {
		return SerializationUtils.clone(source);
	}

	public static FileNuclrResource build(NuclrPluginContext ctx, final Path path) {
		// All attribute population (name, size, folder, link, system, hidden,
		// timestamps) lives in the FileNuclrResource constructor as the single source
		// of truth, reading the filesystem once. Don't re-stat here.
		return new FileNuclrResource(ctx, path);
	}

	/**
	 * Opens the system file manager and, where supported, selects the given file.
	 * Falls back to opening the containing folder.
	 */
	public static void revealInFileManager(Path path) throws IOException {
	    Objects.requireNonNull(path, "path");

	    Path target = path.toAbsolutePath().normalize();
	    Path parent = target.getParent();
	    if (parent == null) {
	        parent = target; // root
	    }

	    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

	    List<String> command;
	    if (os.contains("win")) {
	        // explorer /select, "C:\path\to\file"  — selects the file in its folder
	        command = List.of("explorer.exe", "/select,", target.toString());
	    } else if (os.contains("mac") || os.contains("darwin")) {
	        // open -R reveals (selects) the file in Finder
	        command = List.of("open", "-R", target.toString());
	    } else {
	        // Linux/other: try freedesktop file manager with selection, fall back to opening folder
	        if (canRun("dbus-send")) {
	            // Most modern Linux file managers implement org.freedesktop.FileManager1
	            command = List.of(
	                "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1",
	                "--type=method_call", "/org/freedesktop/FileManager1",
	                "org.freedesktop.FileManager1.ShowItems",
	                "array:string:" + target.toUri(),
	                "string:");
	        } else if (canRun("xdg-open")) {
	            command = List.of("xdg-open", parent.toString()); // opens the folder (no selection)
	        } else {
	            // Last resort: AWT Desktop opens the folder
	            if (Desktop.isDesktopSupported()
	                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
	                Desktop.getDesktop().open(parent.toFile());
	                return;
	            }
	            throw new IOException("No supported file manager launcher found");
	        }
	    }

	    new ProcessBuilder(command)
	            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
	            .redirectError(ProcessBuilder.Redirect.DISCARD)
	            .start();
	    // Note: not waiting for the process; these launchers fork/return quickly.
	}

	private static boolean canRun(String exe) {
	    String path = System.getenv("PATH");
	    if (path == null) return false;
	    for (String dir : path.split(File.pathSeparator)) {
	        if (!dir.isBlank() && Files.isExecutable(Path.of(dir, exe))) {
	            return true;
	        }
	    }
	    return false;
	}

}
