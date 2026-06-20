/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.support;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.Window;

import javax.swing.JCheckBox;
import javax.swing.JRadioButton;

import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.Robot;
import org.assertj.swing.edt.FailOnThreadViolationRepaintManager;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for AssertJ Swing robot tests. Installs the EDT-violation repaint manager once, then
 * gives each test a fresh {@link Robot} over a new AWT hierarchy and tears it down afterwards.
 *
 * <p>Robot tests require a real screen, so every hook first {@code assumeFalse} on
 * {@link GraphicsEnvironment#isHeadless()}: on a headless machine (e.g. CI) the whole class is
 * skipped rather than failing. These classes are named {@code *GuiTest} and run only in the
 * non-headless surefire execution configured in the POM.
 */
public abstract class AbstractGuiTest {

	protected Robot robot;

	@BeforeAll
	static void installRepaintManager() {
		assumeFalse(GraphicsEnvironment.isHeadless(), "AssertJ Swing GUI tests require a display");
		FailOnThreadViolationRepaintManager.install();
	}

	@BeforeEach
	void setUpRobot() {
		assumeFalse(GraphicsEnvironment.isHeadless(), "AssertJ Swing GUI tests require a display");
		robot = BasicRobot.robotWithNewAwtHierarchy();
	}

	@AfterEach
	void tearDownRobot() {
		if (robot != null) {
			robot.cleanUp(); // disposes windows in this robot's hierarchy
			robot = null;
		}
		// If a test timed out finding a dialog, that dialog often appears just afterwards and its
		// modal event loop would block every later test in the class. Dispose anything still showing
		// so one environmental hiccup cannot cascade into the rest of the suite. A modal loop still
		// pumps EDT events, so this invokeAndWait runs even while a dialog is up.
		GuiActionRunner.execute(() -> {
			for (Window window : Window.getWindows()) {
				if (window.isShowing()) {
					window.dispose();
				}
			}
		});
	}

	/** Matcher for a {@link JCheckBox} carrying the given label (the dialogs set no component names). */
	protected static GenericTypeMatcher<JCheckBox> checkBoxWithText(String text) {
		return new GenericTypeMatcher<>(JCheckBox.class) {
			@Override
			protected boolean isMatching(JCheckBox component) {
				return text.equals(component.getText());
			}
		};
	}

	/** Matcher for a {@link JRadioButton} carrying the given label. */
	protected static GenericTypeMatcher<JRadioButton> radioButtonWithText(String text) {
		return new GenericTypeMatcher<>(JRadioButton.class) {
			@Override
			protected boolean isMatching(JRadioButton component) {
				return text.equals(component.getText());
			}
		};
	}
}
