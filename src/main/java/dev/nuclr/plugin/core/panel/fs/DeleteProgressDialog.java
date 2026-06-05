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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.SecondaryLoop;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import lombok.extern.slf4j.Slf4j;

/**
 * Modal-feeling progress dialog for a delete operation. The commander owns the
 * {@link NuclrPluginCallback} UI; the plugin drives it from its {@code handleMessage}.
 *
 * <p>The supplied {@code work} (typically a synchronous {@code eventBus.emit}) runs on a
 * background virtual thread while a {@link SecondaryLoop} keeps the EDT painting and the
 * owner window is disabled — the same technique as {@link LoadingDialog}. The dialog is
 * shown lazily on the first {@link NuclrPluginCallback#onStart} so that the plugin's own
 * confirmation popup (shown before any deletion) is not covered by an early progress bar,
 * and trivially fast deletes never flash a dialog.
 */
@Slf4j
public final class DeleteProgressDialog {

	private DeleteProgressDialog() {
	}

	/**
	 * Run {@code work}, passing it a callback wired to a progress dialog. Must be called on
	 * the EDT; blocks (while pumping events) until {@code work} returns.
	 */
	public static void run(Component parent, Consumer<NuclrPluginCallback> work) {

		if (!SwingUtilities.isEventDispatchThread()) {
			work.accept(noopCallback());
			return;
		}

		Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);

		JLabel itemLabel = new JLabel("Preparing…");
		JLabel countLabel = new JLabel(" ");
		JProgressBar bar = new JProgressBar();
		bar.setIndeterminate(true);
		JButton cancelButton = new JButton("Cancel");

		JDialog dialog = buildDialog(owner, itemLabel, countLabel, bar, cancelButton);

		AtomicBoolean cancelled = new AtomicBoolean(false);
		AtomicBoolean finished = new AtomicBoolean(false);
		AtomicBoolean shown = new AtomicBoolean(false);
		int[] completed = { 0 };

		cancelButton.addActionListener(e -> {
			cancelled.set(true);
			cancelButton.setEnabled(false);
			itemLabel.setText("Cancelling…");
		});

		// Note: we intentionally do NOT disable the owner window while this dialog is shown.
		// This dialog is focusable (it has a Cancel button), so disabling the owner and then
		// disposing the (active) dialog would leave Windows with no enabled window to activate,
		// deactivating/iconifying the main frame. The SecondaryLoop already keeps the operation
		// modal-in-effect by blocking until completion.
		Runnable ensureShown = () -> {
			if (shown.get() || finished.get()) {
				return;
			}
			shown.set(true);
			dialog.setVisible(true);
		};

		SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();

		NuclrPluginCallback callback = new NuclrPluginCallback() {
			@Override
			public void onStart(String description) {
				SwingUtilities.invokeLater(() -> {
					itemLabel.setText(description == null ? "" : description);
					ensureShown.run();
				});
			}

			@Override
			public void onProgress(long current, long total) {
				// Indeterminate bar; just keep the dialog visible/responsive.
				SwingUtilities.invokeLater(ensureShown);
			}

			@Override
			public void onComplete() {
				SwingUtilities.invokeLater(() -> {
					completed[0]++;
					countLabel.setText(completed[0] + " deleted");
				});
			}

			@Override
			public void onError(String description, Exception e) {
				// The plugin shows the Skip/Abort popup; here we only note it.
				log.warn("Delete error for [{}]: {}", description, e == null ? "?" : e.getMessage());
			}

			@Override
			public boolean isCancelled() {
				return cancelled.get();
			}
		};

		Thread.ofVirtual().start(() -> {
			try {
				work.accept(callback);
			} catch (Throwable t) {
				log.error("Delete work failed: {}", t.getMessage(), t);
			} finally {
				SwingUtilities.invokeLater(() -> {
					finished.set(true);
					if (shown.get()) {
						dialog.dispose();
					}
					loop.exit();
				});
			}
		});

		loop.enter();
	}

	private static JDialog buildDialog(Window owner, JLabel itemLabel, JLabel countLabel, JProgressBar bar,
			JButton cancelButton) {

		JDialog dialog = new JDialog(owner);
		dialog.setTitle("Delete");
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));

		JPanel north = new JPanel(new BorderLayout(0, 4));
		north.add(itemLabel, BorderLayout.NORTH);
		north.add(bar, BorderLayout.CENTER);
		north.add(countLabel, BorderLayout.SOUTH);
		content.add(north, BorderLayout.CENTER);

		JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		south.add(cancelButton);
		content.add(south, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(320, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);
		return dialog;
	}

	private static NuclrPluginCallback noopCallback() {
		return new NuclrPluginCallback() {
			@Override
			public void onStart(String description) {
			}

			@Override
			public void onProgress(long current, long total) {
			}

			@Override
			public void onComplete() {
			}

			@Override
			public void onError(String description, Exception e) {
			}

			@Override
			public boolean isCancelled() {
				return false;
			}
		};
	}
}
