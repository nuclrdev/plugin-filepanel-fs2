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

import dev.nuclr.platform.plugin.NuclrPluginContext;

public final class SoundEvents {

	public static final String PopupSound = "PopupSound";
	public static final String CancelSound = "CancelSound";
	public static final String ConfirmationSound = "ConfirmationSound";
	public static final String ErrorSound = "ErrorSound";
	public static final String ProcessCompleteSound = "ProcessCompleteSound";
	public static final String WarningSound = "WarningSound";

	private SoundEvents() {
	}

	public static void popup(NuclrPluginContext context) {
		emit(context, PopupSound);
	}

	public static void cancel(NuclrPluginContext context) {
		emit(context, CancelSound);
	}

	public static void confirmation(NuclrPluginContext context) {
		emit(context, ConfirmationSound);
	}

	public static void error(NuclrPluginContext context) {
		emit(context, ErrorSound);
	}

	public static void processComplete(NuclrPluginContext context) {
		emit(context, ProcessCompleteSound);
	}

	public static void warning(NuclrPluginContext context) {
		emit(context, WarningSound);
	}

	public static void emit(NuclrPluginContext context, String eventType) {
		if (context == null || context.getEventBus() == null || eventType == null || eventType.isBlank()) {
			return;
		}
		context.getEventBus().emit(eventType);
	}
}
