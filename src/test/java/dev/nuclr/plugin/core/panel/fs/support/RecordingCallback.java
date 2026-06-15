/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.support;

import java.util.ArrayList;
import java.util.List;

import dev.nuclr.platform.plugin.NuclrPluginCallback;

/**
 * {@link NuclrPluginCallback} that records every interaction so tests can assert
 * on the progress signals a service emitted. Supports two cancellation triggers:
 * an immediate {@link #cancelled} flag and {@link #cancelAfterProgress}, which
 * flips the flag once a given number of {@code onProgress} calls have happened
 * (useful to exercise mid-operation cancellation deterministically).
 */
public final class RecordingCallback implements NuclrPluginCallback {

	public final List<String> started = new ArrayList<>();
	public final List<String> errored = new ArrayList<>();
	public int progressCount = 0;
	public int completeCount = 0;
	public int errorCount = 0;

	public volatile boolean cancelled = false;
	/** When {@code >= 0}, {@link #cancelled} is set true once this many onProgress calls occur. */
	public int cancelAfterProgress = -1;

	@Override
	public void onStart(String description) {
		started.add(description);
	}

	@Override
	public void onProgress(long current, long total) {
		progressCount++;
		if (cancelAfterProgress >= 0 && progressCount >= cancelAfterProgress) {
			cancelled = true;
		}
	}

	@Override
	public void onComplete() {
		completeCount++;
	}

	@Override
	public void onError(String description, Exception e) {
		errorCount++;
		errored.add(description);
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}
}
