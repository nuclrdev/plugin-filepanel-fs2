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

import java.util.Optional;

import dev.nuclr.platform.plugin.NuclrResource;

/**
 * Parses the free-text "Custom path…" field into a {@link NuclrResource}. Parsing is
 * delegated to the active plugin so plugin URIs ({@code sftp://host/path},
 * {@code zip:/x.zip!/src/}, …) are understood by whichever panel issued the search; a
 * local panel resolves plain filesystem paths.
 *
 * @see FindFileDialog
 */
@FunctionalInterface
public interface ResourcePathParser {

	/**
	 * Resolve {@code pathOrUri} into a resource, or {@link Optional#empty()} if it
	 * cannot be parsed / does not exist.
	 */
	Optional<NuclrResource> parse(String pathOrUri);
}
