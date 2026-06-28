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
package dev.nuclr.plugin.core.panel.fs.find;

import java.awt.Window;
import java.util.Optional;

import dev.nuclr.platform.plugin.NuclrResource;

/**
 * Abstraction over the "Browse" action in {@link FindFileDialog}. The dialog never
 * hard-codes a chooser; instead it delegates to the navigator supplied by the active
 * panel plugin, so an SFTP panel can present an SFTP-aware picker and a local panel a
 * local one.
 *
 * <p>Any directory listing the picker performs must happen off the EDT; the call returns
 * the chosen location, or {@link Optional#empty()} when the user cancels.
 */
@FunctionalInterface
public interface ResourceBrowser {

	/**
	 * Present the plugin's resource picker, anchored to {@code owner}, starting at
	 * {@code start} (which may be {@code null}).
	 *
	 * @return the chosen resource, or empty if cancelled
	 */
	Optional<NuclrResource> browse(Window owner, NuclrResource start);
}
