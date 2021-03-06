/*
 * openTCS copyright information:
 * Copyright (c) 2013 Fraunhofer IML
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.guing.application.action.view;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import org.opentcs.guing.application.OpenTCSView;

/**
 * An action for adding new drawing views.
 *
 * @author Philipp Seifert (Fraunhofer IML)
 */
public class AddDrawingViewAction
    extends AbstractAction {

  public final static String ID = "view.addDrawingView";
  private final OpenTCSView view;

  /**
   * Creates a new instance.
   */
  public AddDrawingViewAction(OpenTCSView view) {
    this.view = view;
  }

  @Override
  public void actionPerformed(ActionEvent evt) {
    view.addDrawingView();
  }
}
