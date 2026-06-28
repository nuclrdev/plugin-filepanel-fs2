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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.nuclr.platform.plugin.NuclrResource;

/**
 * Immutable description of a single Find File search, produced by
 * {@link FindFileDialog} and consumed by {@link FindFileService}.
 *
 * <p>Deliberately a plain class (no record) with an explicit {@link Builder},
 * field validation and hand-written {@code equals}/{@code hashCode}/{@code toString}.
 * Construction is the single point where the request is checked for validity, so an
 * instance that exists is always runnable.
 *
 * <p>Validation rules enforced by {@link Builder#build()}:
 * <ul>
 *   <li>at least one of {@code namePattern} / {@code containingText} is non-blank;</li>
 *   <li>a {@link ScopeType#CUSTOM_PATH} scope requires a non-blank {@code customPath};</li>
 *   <li>a {@link ContentMatchMode#REGEX} content query must compile;</li>
 *   <li>a {@link ContentMatchMode#HEX} content query must be valid hex octets;</li>
 *   <li>size and date ranges must be non-inverted (min ≤ max, from ≤ to).</li>
 * </ul>
 */
public final class FindFileRequest {

	private final String namePattern;
	private final String containingText;
	private final ContentMatchMode contentMode;
	private final boolean caseSensitive;
	private final boolean wholeWord;
	private final boolean invertMatch;

	private final ScopeType scopeType;
	private final String customPath;
	private final List<NuclrResource> roots;

	private final boolean searchSubfolders;
	private final boolean followSymlinks;
	private final boolean searchArchives;
	private final boolean includeHidden;
	private final boolean respectGitignore;

	private final LocalDateTime modifiedFrom;
	private final LocalDateTime modifiedTo;
	private final Long minSizeBytes;
	private final Long maxSizeBytes;
	private final Charset encoding;
	private final boolean searchNtfsAlternateStreams;

	private FindFileRequest(Builder b) {
		this.namePattern = b.namePattern == null ? "" : b.namePattern.trim();
		this.containingText = b.containingText == null ? "" : b.containingText;
		this.contentMode = b.contentMode;
		this.caseSensitive = b.caseSensitive;
		this.wholeWord = b.wholeWord;
		this.invertMatch = b.invertMatch;

		this.scopeType = b.scopeType;
		this.customPath = b.customPath == null ? "" : b.customPath.trim();
		this.roots = Collections.unmodifiableList(new ArrayList<>(b.roots));

		this.searchSubfolders = b.searchSubfolders;
		this.followSymlinks = b.followSymlinks;
		this.searchArchives = b.searchArchives;
		this.includeHidden = b.includeHidden;
		this.respectGitignore = b.respectGitignore;

		this.modifiedFrom = b.modifiedFrom;
		this.modifiedTo = b.modifiedTo;
		this.minSizeBytes = b.minSizeBytes;
		this.maxSizeBytes = b.maxSizeBytes;
		this.encoding = b.encoding == null ? StandardCharsets.UTF_8 : b.encoding;
		this.searchNtfsAlternateStreams = b.searchNtfsAlternateStreams;
	}

	public String getNamePattern() {
		return namePattern;
	}

	public String getContainingText() {
		return containingText;
	}

	public ContentMatchMode getContentMode() {
		return contentMode;
	}

	public boolean isCaseSensitive() {
		return caseSensitive;
	}

	public boolean isWholeWord() {
		return wholeWord;
	}

	public boolean isInvertMatch() {
		return invertMatch;
	}

	public ScopeType getScopeType() {
		return scopeType;
	}

	public String getCustomPath() {
		return customPath;
	}

	/** The resolved scope roots the service should walk; never {@code null}, may be empty. */
	public List<NuclrResource> getRoots() {
		return roots;
	}

	public boolean isSearchSubfolders() {
		return searchSubfolders;
	}

	public boolean isFollowSymlinks() {
		return followSymlinks;
	}

	public boolean isSearchArchives() {
		return searchArchives;
	}

	public boolean isIncludeHidden() {
		return includeHidden;
	}

	public boolean isRespectGitignore() {
		return respectGitignore;
	}

	public LocalDateTime getModifiedFrom() {
		return modifiedFrom;
	}

	public LocalDateTime getModifiedTo() {
		return modifiedTo;
	}

	public Long getMinSizeBytes() {
		return minSizeBytes;
	}

	public Long getMaxSizeBytes() {
		return maxSizeBytes;
	}

	public Charset getEncoding() {
		return encoding;
	}

	public boolean isSearchNtfsAlternateStreams() {
		return searchNtfsAlternateStreams;
	}

	/** {@code true} when a content match should be applied (the "Containing" field is non-blank). */
	public boolean hasContentQuery() {
		return !containingText.isBlank();
	}

	/** {@code true} when a name filter narrower than "match everything" is in effect. */
	public boolean hasNameQuery() {
		return !namePattern.isEmpty() && !namePattern.equals("*");
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FindFileRequest other)) {
			return false;
		}
		return caseSensitive == other.caseSensitive
				&& wholeWord == other.wholeWord
				&& invertMatch == other.invertMatch
				&& searchSubfolders == other.searchSubfolders
				&& followSymlinks == other.followSymlinks
				&& searchArchives == other.searchArchives
				&& includeHidden == other.includeHidden
				&& respectGitignore == other.respectGitignore
				&& searchNtfsAlternateStreams == other.searchNtfsAlternateStreams
				&& namePattern.equals(other.namePattern)
				&& containingText.equals(other.containingText)
				&& contentMode == other.contentMode
				&& scopeType == other.scopeType
				&& customPath.equals(other.customPath)
				&& roots.equals(other.roots)
				&& Objects.equals(modifiedFrom, other.modifiedFrom)
				&& Objects.equals(modifiedTo, other.modifiedTo)
				&& Objects.equals(minSizeBytes, other.minSizeBytes)
				&& Objects.equals(maxSizeBytes, other.maxSizeBytes)
				&& encoding.equals(other.encoding);
	}

	@Override
	public int hashCode() {
		return Objects.hash(namePattern, containingText, contentMode, caseSensitive, wholeWord, invertMatch,
				scopeType, customPath, roots, searchSubfolders, followSymlinks, searchArchives, includeHidden,
				respectGitignore, modifiedFrom, modifiedTo, minSizeBytes, maxSizeBytes, encoding,
				searchNtfsAlternateStreams);
	}

	@Override
	public String toString() {
		return "FindFileRequest{name=" + namePattern
				+ ", containing=" + (containingText.isBlank() ? "<none>" : "[" + contentMode + "]")
				+ ", scope=" + scopeType
				+ (scopeType == ScopeType.CUSTOM_PATH ? "(" + customPath + ")" : "")
				+ ", roots=" + roots.size()
				+ ", subfolders=" + searchSubfolders
				+ ", symlinks=" + followSymlinks
				+ ", hidden=" + includeHidden
				+ ", gitignore=" + respectGitignore
				+ "}";
	}

	/**
	 * Mutable builder for {@link FindFileRequest}. All validation happens in
	 * {@link #build()}; setters never throw, so the dialog can populate the builder
	 * incrementally from field state and surface a single error at submit time.
	 */
	public static final class Builder {

		private String namePattern = "*";
		private String containingText = "";
		private ContentMatchMode contentMode = ContentMatchMode.TEXT;
		private boolean caseSensitive;
		private boolean wholeWord;
		private boolean invertMatch;

		private ScopeType scopeType = ScopeType.CURRENT_FOLDER;
		private String customPath = "";
		private List<NuclrResource> roots = new ArrayList<>();

		private boolean searchSubfolders = true;
		private boolean followSymlinks;
		private boolean searchArchives;
		private boolean includeHidden;
		private boolean respectGitignore;

		private LocalDateTime modifiedFrom;
		private LocalDateTime modifiedTo;
		private Long minSizeBytes;
		private Long maxSizeBytes;
		private Charset encoding = StandardCharsets.UTF_8;
		private boolean searchNtfsAlternateStreams;

		private Builder() {
		}

		public Builder namePattern(String value) {
			this.namePattern = value;
			return this;
		}

		public Builder containingText(String value) {
			this.containingText = value;
			return this;
		}

		public Builder contentMode(ContentMatchMode value) {
			this.contentMode = value;
			return this;
		}

		public Builder caseSensitive(boolean value) {
			this.caseSensitive = value;
			return this;
		}

		public Builder wholeWord(boolean value) {
			this.wholeWord = value;
			return this;
		}

		public Builder invertMatch(boolean value) {
			this.invertMatch = value;
			return this;
		}

		public Builder scopeType(ScopeType value) {
			this.scopeType = value;
			return this;
		}

		public Builder customPath(String value) {
			this.customPath = value;
			return this;
		}

		public Builder roots(List<NuclrResource> value) {
			this.roots = value == null ? new ArrayList<>() : new ArrayList<>(value);
			return this;
		}

		public Builder searchSubfolders(boolean value) {
			this.searchSubfolders = value;
			return this;
		}

		public Builder followSymlinks(boolean value) {
			this.followSymlinks = value;
			return this;
		}

		public Builder searchArchives(boolean value) {
			this.searchArchives = value;
			return this;
		}

		public Builder includeHidden(boolean value) {
			this.includeHidden = value;
			return this;
		}

		public Builder respectGitignore(boolean value) {
			this.respectGitignore = value;
			return this;
		}

		public Builder modifiedFrom(LocalDateTime value) {
			this.modifiedFrom = value;
			return this;
		}

		public Builder modifiedTo(LocalDateTime value) {
			this.modifiedTo = value;
			return this;
		}

		public Builder minSizeBytes(Long value) {
			this.minSizeBytes = value;
			return this;
		}

		public Builder maxSizeBytes(Long value) {
			this.maxSizeBytes = value;
			return this;
		}

		public Builder encoding(Charset value) {
			this.encoding = value;
			return this;
		}

		public Builder searchNtfsAlternateStreams(boolean value) {
			this.searchNtfsAlternateStreams = value;
			return this;
		}

		/**
		 * Validate the accumulated state and produce an immutable request.
		 *
		 * @throws IllegalArgumentException if any validation rule is violated
		 * @throws NullPointerException     if a required enum field is {@code null}
		 */
		public FindFileRequest build() {
			Objects.requireNonNull(contentMode, "contentMode");
			Objects.requireNonNull(scopeType, "scopeType");

			String name = namePattern == null ? "" : namePattern.trim();
			String containing = containingText == null ? "" : containingText;

			if (name.isEmpty() && containing.isBlank()) {
				throw new IllegalArgumentException(
						"A search needs at least a name pattern or a containing-text query");
			}

			if (scopeType == ScopeType.CUSTOM_PATH && (customPath == null || customPath.isBlank())) {
				throw new IllegalArgumentException("Custom path scope requires a non-blank path");
			}

			if (!containing.isBlank()) {
				validateContentQuery(containing);
			}

			if (minSizeBytes != null && minSizeBytes < 0) {
				throw new IllegalArgumentException("Minimum size must not be negative");
			}
			if (maxSizeBytes != null && maxSizeBytes < 0) {
				throw new IllegalArgumentException("Maximum size must not be negative");
			}
			if (minSizeBytes != null && maxSizeBytes != null && minSizeBytes > maxSizeBytes) {
				throw new IllegalArgumentException("Minimum size exceeds maximum size");
			}
			if (modifiedFrom != null && modifiedTo != null && modifiedFrom.isAfter(modifiedTo)) {
				throw new IllegalArgumentException("Modified-from date is after modified-to date");
			}

			return new FindFileRequest(this);
		}

		private void validateContentQuery(String containing) {
			switch (contentMode) {
				case REGEX -> {
					try {
						Pattern.compile(containing);
					} catch (PatternSyntaxException e) {
						throw new IllegalArgumentException("Invalid regular expression: " + e.getMessage(), e);
					}
				}
				case HEX -> {
					String compact = containing.replaceAll("\\s+", "");
					if (compact.isEmpty()) {
						throw new IllegalArgumentException("Hex query is empty");
					}
					if (compact.length() % 2 != 0) {
						throw new IllegalArgumentException("Hex query must have an even number of digits");
					}
					if (!compact.matches("[0-9A-Fa-f]+")) {
						throw new IllegalArgumentException("Hex query contains non-hex characters");
					}
				}
				case TEXT -> {
					// Any non-blank literal is valid.
				}
			}
		}
	}
}
