/*
 * openTCS copyright information:
 * Copyright (c) 2016 Fraunhofer IML
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.guing;

import org.opentcs.customizations.plantoverview.PlantOverviewInjectionModule;

/**
 * Configures/binds the default vehicle and location themes of the openTCS plant overview.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class DefaultThemesModule
    extends PlantOverviewInjectionModule {

  @Override
  @SuppressWarnings("deprecation")
  protected void configure() {
    vehicleThemeBinder();
    locationThemeBinder();
  }
}
