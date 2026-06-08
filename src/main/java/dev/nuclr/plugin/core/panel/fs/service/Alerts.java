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
package dev.nuclr.plugin.core.panel.fs.service;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Alerts {

	public static void showError(String title, String message) {
		runOnEdtAndWait(() -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE));
	}	
	
	public static void runOnEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Failed to run dialog on EDT: {}", e.getMessage(), e);
		}
	}

}
