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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;

/**
 * Everything {@link FindFileDialog} needs from the plugin that opened it: the scope
 * inputs (current/other folder, marked items, volumes) and the collaborators that keep
 * the dialog plugin-agnostic (browse, path parsing, and the request sink).
 *
 * <p>A plain immutable value object — assembled by the plugin via {@link Builder} and
 * handed to the dialog's constructor so the dialog stays purely UI.
 */
public final class FindFileContext {

	private final NuclrResource currentFolder;
	private final NuclrResource otherPanelFolder;
	private final List<NuclrResource> markedItems;
	private final Supplier<List<NuclrResource>> volumesSupplier;
	private final ResourceBrowser browser;
	private final ResourcePathParser pathParser;
	private final Consumer<FindFileRequest> onSubmit;
	private final NuclrPluginContext pluginContext;

	private FindFileContext(Builder b) {
		this.currentFolder = b.currentFolder;
		this.otherPanelFolder = b.otherPanelFolder;
		this.markedItems = Collections.unmodifiableList(new ArrayList<>(b.markedItems));
		this.volumesSupplier = b.volumesSupplier != null ? b.volumesSupplier : List::of;
		this.browser = b.browser;
		this.pathParser = b.pathParser;
		this.onSubmit = b.onSubmit;
		this.pluginContext = b.pluginContext;
	}

	public NuclrResource getCurrentFolder() {
		return currentFolder;
	}

	public NuclrResource getOtherPanelFolder() {
		return otherPanelFolder;
	}

	public List<NuclrResource> getMarkedItems() {
		return markedItems;
	}

	public List<NuclrResource> volumes() {
		List<NuclrResource> v = volumesSupplier.get();
		return v != null ? v : List.of();
	}

	public ResourceBrowser getBrowser() {
		return browser;
	}

	public ResourcePathParser getPathParser() {
		return pathParser;
	}

	public Consumer<FindFileRequest> getOnSubmit() {
		return onSubmit;
	}

	public NuclrPluginContext getPluginContext() {
		return pluginContext;
	}

	public boolean hasOtherPanel() {
		return otherPanelFolder != null;
	}

	public boolean hasMarkedItems() {
		return !markedItems.isEmpty();
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Mutable builder for {@link FindFileContext}. */
	public static final class Builder {

		private NuclrResource currentFolder;
		private NuclrResource otherPanelFolder;
		private List<NuclrResource> markedItems = List.of();
		private Supplier<List<NuclrResource>> volumesSupplier;
		private ResourceBrowser browser;
		private ResourcePathParser pathParser;
		private Consumer<FindFileRequest> onSubmit;
		private NuclrPluginContext pluginContext;

		private Builder() {
		}

		public Builder currentFolder(NuclrResource value) {
			this.currentFolder = value;
			return this;
		}

		public Builder otherPanelFolder(NuclrResource value) {
			this.otherPanelFolder = value;
			return this;
		}

		public Builder markedItems(List<NuclrResource> value) {
			this.markedItems = value == null ? List.of() : value;
			return this;
		}

		public Builder volumesSupplier(Supplier<List<NuclrResource>> value) {
			this.volumesSupplier = value;
			return this;
		}

		public Builder browser(ResourceBrowser value) {
			this.browser = value;
			return this;
		}

		public Builder pathParser(ResourcePathParser value) {
			this.pathParser = value;
			return this;
		}

		public Builder onSubmit(Consumer<FindFileRequest> value) {
			this.onSubmit = value;
			return this;
		}

		public Builder pluginContext(NuclrPluginContext value) {
			this.pluginContext = value;
			return this;
		}

		public FindFileContext build() {
			return new FindFileContext(this);
		}
	}
}
