/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.swing.JDialog;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.matcher.JButtonMatcher;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.DialogFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.fs.service.move.MoveEngine.Action;
import dev.nuclr.plugin.core.panel.fs.service.move.MoveEngine.Resolution;
import dev.nuclr.plugin.core.panel.fs.support.AbstractGuiTest;

/**
 * AssertJ Swing tests for the plugin's F6 modal dialogs: the {@link MoveDialog} setup form and the
 * {@link MoveConflictDialog} "File already exists" resolver (including its nested rename prompt).
 *
 * <p>Both dialogs block on the thread that opens them, so each test runs the blocking call on a
 * background executor, grabs the modal {@link JDialog} with {@link WindowFinder}, drives its widgets
 * through the robot, then reads the value the (now-returned) future produced.
 *
 * <p>All GUI assertions live in this one class on purpose: re-initialising the AssertJ Swing robot
 * for a <em>second</em> class on the same physical desktop reliably fails to surface the next modal
 * dialog. A single continuous robot session per JVM is stable — see the {@code gui-tests} surefire
 * execution in the POM.
 */
class MoveDialogsGuiTest extends AbstractGuiTest {

	private final ExecutorService exec = Executors.newSingleThreadExecutor();

	@AfterEach
	void shutdownExecutor() {
		exec.shutdownNow();
	}

	private DialogFixture findDialog(String title) {
		return WindowFinder.findDialog(new GenericTypeMatcher<JDialog>(JDialog.class) {
			@Override
			protected boolean isMatching(JDialog dialog) {
				return title.equals(dialog.getTitle()) && dialog.isShowing();
			}
		}).withTimeout(5000).using(robot);
	}

	// ---- MoveDialog (setup form) -------------------------------------------------------------

	private Future<MoveOptions> showAsync(String header, String prefill) {
		return exec.submit(() -> MoveDialog.show(header, prefill));
	}

	@Test
	void setupConfirmCollectsEditedOptions() throws Exception {
		Future<MoveOptions> future = showAsync("deploy.bat", "C:\\dst\\deploy.bat");
		DialogFixture dialog = findDialog("Rename/Move");

		dialog.textBox().deleteText().enterText("C:\\dst\\renamed.bat");
		dialog.comboBox().selectItem("Overwrite");
		dialog.radioButton(radioButtonWithText("Copy")).check();
		dialog.checkBox(checkBoxWithText("Preserve all timestamps")).check();
		dialog.checkBox(checkBoxWithText("Also ask on R/O files")).uncheck();
		dialog.button(JButtonMatcher.withText("Rename")).click();

		MoveOptions options = future.get(5, TimeUnit.SECONDS);
		assertThat(options).isNotNull();
		assertThat(options.getDestination()).isEqualTo(Path.of("C:\\dst\\renamed.bat"));
		assertThat(options.getConflictMode()).isEqualTo(MoveOptions.ConflictMode.OVERWRITE);
		assertThat(options.getAccessRights()).isEqualTo(MoveOptions.AccessRights.COPY);
		assertThat(options.isPreserveTimestamps()).isTrue();
		assertThat(options.isAskOnReadOnly()).isFalse();
	}

	@Test
	void setupConfirmWithUntouchedFormUsesDefaults() throws Exception {
		Future<MoveOptions> future = showAsync("3 items", "C:\\dst\\");
		DialogFixture dialog = findDialog("Rename/Move");

		dialog.button(JButtonMatcher.withText("Rename")).click();

		MoveOptions options = future.get(5, TimeUnit.SECONDS);
		assertThat(options).isNotNull();
		assertThat(options.getDestination()).isEqualTo(Path.of("C:\\dst\\"));
		assertThat(options.getConflictMode()).isEqualTo(MoveOptions.ConflictMode.ASK);
		assertThat(options.getAccessRights()).isEqualTo(MoveOptions.AccessRights.DEFAULT);
		assertThat(options.isAskOnReadOnly()).isTrue();
		assertThat(options.isPreserveTimestamps()).isFalse();
	}

	@Test
	void setupCancelButtonReturnsNull() throws Exception {
		Future<MoveOptions> future = showAsync("deploy.bat", "C:\\dst\\deploy.bat");
		DialogFixture dialog = findDialog("Rename/Move");

		dialog.button(JButtonMatcher.withText("Cancel")).click();

		assertThat(future.get(5, TimeUnit.SECONDS)).isNull();
	}

	@Test
	void setupEscapeKeyReturnsNull() throws Exception {
		Future<MoveOptions> future = showAsync("deploy.bat", "C:\\dst\\deploy.bat");
		DialogFixture dialog = findDialog("Rename/Move");

		dialog.pressAndReleaseKeys(KeyEvent.VK_ESCAPE);

		assertThat(future.get(5, TimeUnit.SECONDS)).isNull();
	}

	@Test
	void setupEmptyDestinationKeepsDialogOpen() throws Exception {
		Future<MoveOptions> future = showAsync("deploy.bat", "C:\\dst\\deploy.bat");
		DialogFixture dialog = findDialog("Rename/Move");

		dialog.textBox().deleteText().enterText("   ");
		dialog.button(JButtonMatcher.withText("Rename")).click();

		// A blank destination is rejected: the dialog stays up and nothing is returned yet.
		dialog.requireVisible();
		assertThat(future.isDone()).isFalse();

		dialog.button(JButtonMatcher.withText("Cancel")).click();
		assertThat(future.get(5, TimeUnit.SECONDS)).isNull();
	}

	// ---- MoveConflictDialog (resolver) -------------------------------------------------------

	private Future<Resolution> resolveAsync(MoveConflictDialog resolver, Path source, Path target) {
		return exec.submit(() -> resolver.resolve(source, target));
	}

	private static Path[] clashingFiles(Path dir) throws IOException {
		Path source = Files.writeString(dir.resolve("new.txt"), "NEW");
		Path target = Files.writeString(dir.resolve("existing.txt"), "OLD");
		return new Path[] { source, target };
	}

	@Test
	void conflictOverwriteButtonResolvesToOverwrite(@TempDir Path dir) throws Exception {
		Path[] f = clashingFiles(dir);
		Future<Resolution> future = resolveAsync(new MoveConflictDialog(), f[0], f[1]);
		DialogFixture dialog = findDialog("Warning");

		dialog.button(JButtonMatcher.withText("Overwrite")).click();

		Resolution resolution = future.get(5, TimeUnit.SECONDS);
		assertThat(resolution.action()).isEqualTo(Action.OVERWRITE);
		assertThat(resolution.renameTarget()).isNull();
	}

	@Test
	void conflictSkipButtonResolvesToSkip(@TempDir Path dir) throws Exception {
		Path[] f = clashingFiles(dir);
		Future<Resolution> future = resolveAsync(new MoveConflictDialog(), f[0], f[1]);
		DialogFixture dialog = findDialog("Warning");

		dialog.button(JButtonMatcher.withText("Skip")).click();

		assertThat(future.get(5, TimeUnit.SECONDS).action()).isEqualTo(Action.SKIP);
	}

	@Test
	void conflictEscapeKeyResolvesToCancel(@TempDir Path dir) throws Exception {
		Path[] f = clashingFiles(dir);
		Future<Resolution> future = resolveAsync(new MoveConflictDialog(), f[0], f[1]);
		DialogFixture dialog = findDialog("Warning");

		dialog.pressAndReleaseKeys(KeyEvent.VK_ESCAPE);

		assertThat(future.get(5, TimeUnit.SECONDS).action()).isEqualTo(Action.CANCEL);
	}

	@Test
	void conflictRenameOpensNestedPromptAndReturnsTypedTarget(@TempDir Path dir) throws Exception {
		Path[] f = clashingFiles(dir);
		Path custom = dir.resolve("custom-name.txt");

		Future<Resolution> future = resolveAsync(new MoveConflictDialog(), f[0], f[1]);
		DialogFixture warning = findDialog("Warning");
		warning.button(JButtonMatcher.withText("Rename")).click();

		DialogFixture rename = findDialog("Rename");
		rename.textBox().deleteText().enterText(custom.toString());
		rename.button(JButtonMatcher.withText("OK")).click();

		Resolution resolution = future.get(5, TimeUnit.SECONDS);
		assertThat(resolution.action()).isEqualTo(Action.RENAME);
		assertThat(resolution.renameTarget()).isEqualTo(custom);
	}

	@Test
	void conflictRememberChoiceAppliesToLaterClashesWithoutPrompting(@TempDir Path dir) throws Exception {
		Path[] f = clashingFiles(dir);
		MoveConflictDialog resolver = new MoveConflictDialog();

		Future<Resolution> first = resolveAsync(resolver, f[0], f[1]);
		DialogFixture dialog = findDialog("Warning");
		dialog.checkBox(checkBoxWithText("Remember choice")).check();
		dialog.button(JButtonMatcher.withText("Skip")).click();
		assertThat(first.get(5, TimeUnit.SECONDS).action()).isEqualTo(Action.SKIP);

		// The sticky answer is returned directly — no dialog is shown — so this call is safe on the
		// test thread and must not block.
		Resolution second = resolver.resolve(f[0], f[1]);
		assertThat(second.action()).isEqualTo(Action.SKIP);
	}
}
