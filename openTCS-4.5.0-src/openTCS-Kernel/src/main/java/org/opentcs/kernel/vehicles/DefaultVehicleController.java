/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.kernel.vehicles;

import com.google.inject.assistedinject.Assisted;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static java.util.Objects.requireNonNull;
import java.util.Queue;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.engio.mbassy.bus.MBassador;
import org.opentcs.access.LocalKernel;
import org.opentcs.components.kernel.ResourceAllocationException;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.data.TCSObjectReference;
import org.opentcs.data.model.Location;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.TCSResource;
import org.opentcs.data.model.Triple;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.notification.UserNotification;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.Route;
import org.opentcs.data.order.Route.Step;
import org.opentcs.drivers.vehicle.LoadHandlingDevice;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;
import org.opentcs.drivers.vehicle.VehicleCommAdapterEvent;
import org.opentcs.drivers.vehicle.VehicleController;
import org.opentcs.drivers.vehicle.VehicleProcessModel;
import static org.opentcs.util.Assertions.checkState;
import org.opentcs.util.ExplainedBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Realizes a bidirectional connection between the kernel and a communication adapter controlling a
 * vehicle.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class DefaultVehicleController
    implements VehicleController,
               PropertyChangeListener {

  /**
   * This class's Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(DefaultVehicleController.class);
  /**
   * This class's configuration.
   */
  private final VehiclesConfiguration configuration;
  /**
   * The local kernel.
   */
  private final LocalKernel localKernel;
  /**
   * The scheduler maintaining the resources.
   */
  private final Scheduler scheduler;
  /**
   * The application's event bus.
   */
  private final MBassador<Object> eventBus;
  /**
   * The vehicle controlled by this controller/the communication adapter.
   */
  private final Vehicle vehicle;
  /**
   * The communication adapter controlling the physical vehicle.
   */
  private final VehicleCommAdapter commAdapter;
  /**
   * The comm adapter's process model.
   */
  private final VehicleProcessModel vehicleModel;
  /**
   * This controller's <em>enabled</em> flag.
   */
  private volatile boolean initialized;
  /**
   * A list of commands that still need to be sent to the communication adapter.
   */
  private final Queue<MovementCommand> futureCommands = new LinkedList<>();
  /**
   * A command for which a resource allocation is pending and which has not yet been sent to the
   * adapter.
   */
  private volatile MovementCommand pendingCommand;
  /**
   * A set of resources for which allocation is pending.
   */
  private volatile Set<TCSResource<?>> pendingResources;
  /**
   * A list of commands that have been sent to the communication adapter.
   */
  private final Queue<MovementCommand> commandsSent = new LinkedList<>();
  /**
   * The resources this controller has allocated for each command.
   */
  private final Queue<Set<TCSResource<?>>> allocatedResources = new LinkedList<>();
  /**
   * The drive order that the vehicle currently has to process.
   */
  private volatile DriveOrder currentDriveOrder;
  /**
   * The communication adapter's last known state.
   */
  private volatile VehicleCommAdapter.State commAdapterState = VehicleCommAdapter.State.UNKNOWN;
  /**
   * The capacity of the communication adapter's command queue.
   */
  private final int adapterCommandQueueCapacity;
  /**
   * Flag indicating that we're currently waiting for resources to be allocated
   * by the scheduler, ensuring that we do not allocate more than one set of
   * resources at a time (which can cause deadlocks).
   */
  private volatile boolean waitingForAllocation;

  /**
   * Creates a new StandardVehicleController associated with the given vehicle.
   *
   * @param vehicle The vehicle this vehicle controller will be associated with.
   * @param adapter The communication adapter of the associated vehicle.
   * @param kernel The kernel instance maintaining the model.
   * @param scheduler The scheduler managing resource allocations.
   * @param eventBus The application's event bus.
   * @param configuration This class's configuration.
   */
  @Inject
  public DefaultVehicleController(@Assisted @Nonnull Vehicle vehicle,
                                  @Assisted @Nonnull VehicleCommAdapter adapter,
                                  @Nonnull LocalKernel kernel,
                                  @Nonnull Scheduler scheduler,
                                  @Nonnull MBassador<Object> eventBus,
                                  @Nonnull VehiclesConfiguration configuration) {
    this.vehicle = requireNonNull(vehicle, "vehicle");
    this.commAdapter = requireNonNull(adapter, "adapter");
    this.localKernel = requireNonNull(kernel, "kernel");
    this.scheduler = requireNonNull(scheduler, "scheduler");
    this.eventBus = requireNonNull(eventBus, "eventBus");
    this.configuration = requireNonNull(configuration, "configuration");

    this.vehicleModel = commAdapter.getProcessModel();
    this.adapterCommandQueueCapacity = adapter.getCommandQueueCapacity();

    // Add a first entry into allocatedResources to shift freeing of resources
    // in commandExecuted() by one - we need to free the resources allocated for
    // the command before the one executed there.
    allocatedResources.add(null);
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public void initialize() {
    if (initialized) {
      return;
    }

    localKernel.setVehicleRechargeOperation(vehicle.getReference(),
                                            commAdapter.getRechargeOperation());
    vehicleModel.addPropertyChangeListener(this);

    // Initialize standard attributes once.
    setVehiclePosition(vehicleModel.getVehiclePosition());
    localKernel.setVehiclePrecisePosition(vehicle.getReference(),
                                          vehicleModel.getVehiclePrecisePosition());
    localKernel.setVehicleOrientationAngle(vehicle.getReference(),
                                           vehicleModel.getVehicleOrientationAngle());
    localKernel.setVehicleEnergyLevel(vehicle.getReference(), vehicleModel.getVehicleEnergyLevel());
    localKernel.setVehicleLoadHandlingDevices(vehicle.getReference(),
                                              vehicleModel.getVehicleLoadHandlingDevices());
    updateVehicleState(vehicleModel.getVehicleState());
    updateCommAdapterState(vehicleModel.getVehicleAdapterState());

    initialized = true;
  }

  @Override
  public void terminate() {
    if (!initialized) {
      return;
    }

    vehicleModel.removePropertyChangeListener(this);
    // Reset the vehicle's position.
    updatePosition(null, null);
    localKernel.setVehiclePrecisePosition(vehicle.getReference(), null);
    // Free all allocated resources.
    freeAllResources();

    updateCommAdapterState(VehicleCommAdapter.State.UNKNOWN);
    updateVehicleState(Vehicle.State.UNKNOWN);

    initialized = false;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void propertyChange(PropertyChangeEvent evt) {
    if (evt.getSource() != vehicleModel) {
      return;
    }

    if (Objects.equals(evt.getPropertyName(), VehicleProcessModel.Attribute.POSITION.name())) {
      setVehiclePosition((String) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.PRECISE_POSITION.name())) {
      localKernel.setVehiclePrecisePosition(vehicle.getReference(), (Triple) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.ORIENTATION_ANGLE.name())) {
      localKernel.setVehicleOrientationAngle(vehicle.getReference(), (Double) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.ENERGY_LEVEL.name())) {
      localKernel.setVehicleEnergyLevel(vehicle.getReference(), (Integer) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.LOAD_HANDLING_DEVICES.name())) {
      localKernel.setVehicleLoadHandlingDevices(vehicle.getReference(),
                                                (List<LoadHandlingDevice>) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(), VehicleProcessModel.Attribute.STATE.name())) {
      updateVehicleState((Vehicle.State) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.COMM_ADAPTER_STATE.name())) {
      updateCommAdapterState((VehicleCommAdapter.State) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.COMMAND_EXECUTED.name())) {
      commandExecuted((MovementCommand) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.COMMAND_FAILED.name())) {
      localKernel.withdrawTransportOrderByVehicle(vehicle.getReference(), true, false);
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.USER_NOTIFICATION.name())) {
      localKernel.publishUserNotification((UserNotification) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.COMM_ADAPTER_EVENT.name())) {
      eventBus.publish((VehicleCommAdapterEvent) evt.getNewValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.VEHICLE_PROPERTY.name())) {
      VehicleProcessModel.VehiclePropertyUpdate propUpdate
          = (VehicleProcessModel.VehiclePropertyUpdate) evt.getNewValue();
      localKernel.setTCSObjectProperty(vehicle.getReference(),
                                       propUpdate.getKey(),
                                       propUpdate.getValue());
    }
    else if (Objects.equals(evt.getPropertyName(),
                            VehicleProcessModel.Attribute.TRANSPORT_ORDER_PROPERTY.name())) {
      VehicleProcessModel.TransportOrderPropertyUpdate propUpdate
          = (VehicleProcessModel.TransportOrderPropertyUpdate) evt.getNewValue();
      if (currentDriveOrder != null) {
        localKernel.setTCSObjectProperty(currentDriveOrder.getTransportOrder(),
                                         propUpdate.getKey(),
                                         propUpdate.getValue());
      }
    }
  }

  @Override
  public void setDriveOrder(@Nonnull DriveOrder newOrder,
                            @Nonnull Map<String, String> orderProperties)
      throws IllegalStateException {
    synchronized (commAdapter) {
      requireNonNull(newOrder, "newOrder");
      requireNonNull(orderProperties, "orderProperties");
      requireNonNull(newOrder.getRoute(), "newOrder.getRoute()");
      // Assert that there isn't still is a drive order that hasn't been finished/removed, yet.
      checkState(currentDriveOrder == null,
                 "%s still has an order! Current order: %s, new order: %s",
                 vehicle.getName(),
                 currentDriveOrder,
                 newOrder);

      scheduler.claim(this, asResourceSequence(newOrder.getRoute().getSteps()));

      currentDriveOrder = newOrder;
      localKernel.setVehicleRouteProgressIndex(vehicle.getReference(), Vehicle.ROUTE_INDEX_DEFAULT);
      createFutureCommands(newOrder, orderProperties);

      // The communication adapter MUST have capacity for a new command - its
      // queue should be empty.
      checkState(canSendNextCommand(), "Cannot send next command for some reason");
      allocateForNextCommand();
      // Set the vehicle's next expected position.
      Point nextPoint = newOrder.getRoute().getSteps().get(0).getDestinationPoint();
      localKernel.setVehicleNextPosition(vehicle.getReference(),
                                         nextPoint.getReference());
    }
  }

  @Override
  public void clearDriveOrder() {
    synchronized (commAdapter) {
      currentDriveOrder = null;

      // Clear pending resource allocations. If they still arrive, we will
      // refuse them in allocationSuccessful().
      waitingForAllocation = false;
      pendingResources = null;

      localKernel.setVehicleRouteProgressIndex(vehicle.getReference(), Vehicle.ROUTE_INDEX_DEFAULT);
    }
  }

  @Override
  public void abortDriveOrder() {
    synchronized (commAdapter) {
      if (currentDriveOrder == null) {
        LOG.debug("{}: No drive order to be aborted", vehicle.getName());
        return;
      }
      futureCommands.clear();
    }
  }

  @Override
  public void clearCommandQueue() {
    synchronized (commAdapter) {
      commAdapter.clearCommandQueue();
      commandsSent.clear();
      futureCommands.clear();
      pendingCommand = null;
      // Free all resource sets that were reserved for future commands, except the current one...
      Set<TCSResource<?>> neededResources = allocatedResources.poll();
      for (Set<TCSResource<?>> resSet : allocatedResources) {
        if (resSet != null) {
          scheduler.free(this, resSet);
        }
      }
      allocatedResources.clear();
      // Put the resources for the current command/position back in...
      allocatedResources.add(neededResources);
    }
  }

  @Override
  public void resetVehiclePosition() {
    synchronized (commAdapter) {
      checkState(currentDriveOrder == null, "%s: Vehicle has a drive order", vehicle.getName());
      checkState(!waitingForAllocation,
                 "%s: Vehicle is waiting for resource allocation",
                 vehicle.getName());

      setVehiclePosition(null);
    }
  }

  @Override
  @Nonnull
  public ExplainedBoolean canProcess(@Nonnull List<String> operations) {
    requireNonNull(operations, "operations");

    synchronized (commAdapter) {
      return commAdapter.canProcess(operations);
    }
  }

  @Override
  public void sendCommAdapterMessage(@Nullable Object message) {
    synchronized (commAdapter) {
      commAdapter.processMessage(message);
    }
  }

  @Override
  @Nonnull
  public String getId() {
    return vehicle.getName();
  }

  @Override
  public boolean allocationSuccessful(@Nonnull Set<TCSResource<?>> resources) {
    requireNonNull(resources, "resources");

    // Look up the command the resources were required for.
    MovementCommand command;
    synchronized (commAdapter) {
      // Check if we've actually been waiting for these resources now. If not,
      // let the scheduler know that we don't want them.
      if (!Objects.equals(resources, pendingResources)) {
        LOG.warn("{}: Allocated resources ({}) != pending resources ({}), refusing them",
                 vehicle.getName(),
                 resources,
                 pendingResources);
        return false;
      }

      command = pendingCommand;
      // If there was no command in the queue, it must have been withdrawn in
      // the meantime - let the scheduler know that we don't need the resources
      // any more.
      if (command == null) {
        LOG.warn("{}: No pending command, pending resources = {}, refusing allocated resources: {}",
                 vehicle.getName(),
                 pendingResources,
                 resources);
        waitingForAllocation = false;
        pendingResources = null;
        return false;
      }
      pendingCommand = null;
      pendingResources = null;

      allocatedResources.add(resources);
      // Send the command to the communication adapter.
      checkState(commAdapter.enqueueCommand(command),
                 "Comm adapter did not accept command");
      commandsSent.add(command);

      // Check if the communication adapter has capacity for another command.
      waitingForAllocation = false;
      if (canSendNextCommand()) {
        allocateForNextCommand();
      }
    }
    // Let the scheduler know we've accepted the resources given.
    return true;
  }

  @Override
  public void allocationFailed(@Nonnull Set<TCSResource<?>> resources) {
    requireNonNull(resources, "resources");
    throw new IllegalStateException("Failed to allocate: " + resources);
  }

  @Override
  public String toString() {
    return "DefaultVehicleController{" + "vehicleName=" + vehicle.getName() + '}';
  }

  private void setVehiclePosition(String position) {
    // Place the vehicle on the given position, regardless of what the kernel
    // might expect. The vehicle is physically there, even if it shouldn't be.
    // The same is true for null values - if the vehicle says it's not on any
    // known position, it has to be treated as a fact.
    Point point;
    if (position == null) {
      point = null;
    }
    else {
      point = localKernel.getTCSObject(Point.class, position);
      // If the new position is not in the model, either ignore it or reset
      // the vehicle's position. (Some vehicles/drivers send intermediate
      // positions that cannot be order destinations and thus do not exist in
      // the model.
      if (point == null) {
        LOG.warn("{}: At unknown position {}", vehicle.getName(), position);
        if (configuration.ignoreUnknownReportedPositions()) {
          return;
        }
      }
    }
    synchronized (commAdapter) {
      // If the current drive order is null, just set the vehicle's position.
      if (currentDriveOrder == null) {
        LOG.debug("{}: Reported new position {} and we do not have a drive order.",
                  vehicle.getName(),
                  point);
        updatePositionWithoutOrder(point);
      }
      else if (commandsSent.isEmpty()) {
        // We have a drive order, but can't remember sending a command to the
        // vehicle. Just set the position without touching the resources, as
        // that might cause even more damage when we actually send commands
        // to the vehicle.
        LOG.debug("{}: Reported new position {} and we didn't send any commands of drive order.",
                  vehicle.getName(),
                  point);
        updatePosition(toReference(point), null);
      }
      else {
        updatePositionWithOrder(position, point);
      }
    }
  }

  private void commandExecuted(MovementCommand executedCommand) {
    requireNonNull(executedCommand, "executedCommand");

    synchronized (commAdapter) {
      // Check if the executed command is the one we expect at this point.
      MovementCommand expectedCommand = commandsSent.peek();
      if (!Objects.equals(expectedCommand, executedCommand)) {
        LOG.warn("{}: Communication adapter executed unexpected command: {} != {}",
                 vehicle.getName(),
                 executedCommand,
                 expectedCommand);
        // XXX The communication adapter executed an unexpected command. Do something!
      }
      // Remove the command from the queue, since it has been processed successfully.
      commandsSent.remove();
      // Free resources allocated for the command before the one now executed.
      Set<TCSResource<?>> oldResources = allocatedResources.poll();
      if (oldResources != null) {
        LOG.debug("{}: Freeing resources: {}", vehicle.getName(), oldResources);
        scheduler.free(this, oldResources);
      }
      else {
        LOG.debug("{}: Nothing to free.", vehicle.getName());
      }
      // Check if there are more commands to be processed for the current drive order.
      if (pendingCommand == null && futureCommands.isEmpty()) {
        LOG.debug("{}: No more commands in current drive order", vehicle.getName());
        // Check if there are still commands that have been sent to the communication adapter but
        // not yet executed. If not, the whole order has been executed completely - let the kernel
        // know about that so it can give us the next drive order.
        if (commandsSent.isEmpty() && !waitingForAllocation) {
          LOG.debug("{}: Current drive order processed", vehicle.getName());
          currentDriveOrder = null;
          // Let the kernel/dispatcher know that the drive order has been processed completely (by
          // setting its state to AWAITING_ORDER).
          localKernel.setVehicleRouteProgressIndex(vehicle.getReference(),
                                                   Vehicle.ROUTE_INDEX_DEFAULT);
          localKernel.setVehicleProcState(vehicle.getReference(), Vehicle.ProcState.AWAITING_ORDER);
        }
      }
      // There are more commands to be processed.
      // Check if we can send another command to the comm adapter.
      else if (canSendNextCommand()) {
        allocateForNextCommand();
      }
    }
  }

  private void createFutureCommands(DriveOrder newOrder, Map<String, String> orderProperties) {
    // Start processing the new order, i.e. fill futureCommands with corresponding command objects.
    String op = newOrder.getDestination().getOperation();
    Route orderRoute = newOrder.getRoute();
    Point finalDestination = orderRoute.getFinalDestinationPoint();
    Map<String, String> destProperties = newOrder.getDestination().getProperties();
    Iterator<Step> stepIter = orderRoute.getSteps().iterator();
    while (stepIter.hasNext()) {
      Step curStep = stepIter.next();
      // Ignore report positions on the route.
      if (curStep.getDestinationPoint().isHaltingPosition()) {
        boolean isFinalMovement = !stepIter.hasNext();

        String operation = isFinalMovement ? op : MovementCommand.NO_OPERATION;
        Location opLocation = isFinalMovement
            ? localKernel.getTCSObject(Location.class,
                                       newOrder.getDestination().getDestination().getName())
            : null;

        futureCommands.add(new MovementCommand(curStep,
                                               operation,
                                               opLocation,
                                               isFinalMovement,
                                               finalDestination,
                                               op,
                                               mergeProperties(orderProperties,
                                                               destProperties)));
      }
    }
  }

  private void updateCommAdapterState(VehicleCommAdapter.State newState) {
    commAdapterState = requireNonNull(newState, "newState");
    localKernel.setVehicleAdapterState(vehicle.getReference(), newState);
    // If the adapter's state is unknown, the vehicle's state is unknown, too.
    if (newState.equals(VehicleCommAdapter.State.UNKNOWN)) {
      updateVehicleState(Vehicle.State.UNKNOWN);
    }
  }

  private void updateVehicleState(Vehicle.State newState) {
    requireNonNull(newState, "newState");
    // If the communication adapter knows the state of the vehicle and is not
    // marked as connected with us, mark it as connected now. - It knows the
    // vehicle's state, so it must be connected to it.
    if (!Vehicle.State.UNKNOWN.equals(newState)
        && !VehicleCommAdapter.State.CONNECTED.equals(commAdapterState)) {
      updateCommAdapterState(VehicleCommAdapter.State.CONNECTED);
    }
    localKernel.setVehicleState(vehicle.getReference(), newState);
  }

  /**
   * Checks if we can send another command to the communication adapter without
   * overflowing its capacity and with respect to the number of commands still
   * in our queue and allocation requests to the scheduler in progress.
   *
   * @return <code>true</code> if, and only if, we can send another command.
   */
  private boolean canSendNextCommand() {
    int sendableCommands = Math.min(adapterCommandQueueCapacity - commandsSent.size(),
                                    futureCommands.size());
    if (sendableCommands <= 0) {
      LOG.debug("{}: Cannot send, number of sendable commands: {}",
                vehicle.getName(),
                sendableCommands);
      return false;
    }
    if (waitingForAllocation) {
      LOG.debug("{}: Cannot send, waiting for allocation", vehicle.getName());
      return false;
    }
    return true;
  }

  /**
   * Allocate the resources needed for executing the next command.
   */
  private void allocateForNextCommand() {
    checkState(pendingCommand == null, "pendingCommand != null");

    // Find out which resources are actually needed for the next command.
    MovementCommand moveCmd = futureCommands.poll();
    pendingResources = getNeededResources(moveCmd);
    LOG.debug("{}: Allocating resources: {}", vehicle.getName(), pendingResources);
    scheduler.allocate(this, pendingResources);
    // Remember that we're waiting for an allocation. This ensures that we only
    // wait for one allocation at a time, and that we get the resources from the
    // scheduler in the right order.
    waitingForAllocation = true;
    pendingCommand = moveCmd;
  }

  /**
   * Returns a set of resources needed for executing the given command.
   *
   * @param cmd The command for which to return the needed resources.
   * @return A set of resources needed for executing the given command.
   */
  private Set<TCSResource<?>> getNeededResources(MovementCommand cmd) {
    requireNonNull(cmd, "cmd");

    Set<TCSResource<?>> result = new HashSet<>();
    result.add(cmd.getStep().getDestinationPoint());
    if (cmd.getStep().getPath() != null) {
      result.add(cmd.getStep().getPath());
    }
    return result;
  }

  /**
   * Frees all resources allocated for the vehicle.
   */
  private void freeAllResources() {
    scheduler.freeAll(this);
    allocatedResources.clear();
  }

  /**
   * Returns the next command expected to be executed by the vehicle, skipping the current one.
   *
   * @return The next command expected to be executed by the vehicle.
   */
  private MovementCommand findNextCommand() {
    MovementCommand nextCommand = commandsSent.stream()
        .skip(1)
        .filter(cmd -> cmd != null)
        .findFirst()
        .orElse(null);

    if (nextCommand == null) {
      nextCommand = pendingCommand;
    }

    if (nextCommand == null) {
      futureCommands.stream()
          .filter(cmd -> cmd != null)
          .findFirst()
          .orElse(null);
    }

    return nextCommand;
  }

  private void updatePositionWithoutOrder(Point point) {
    // Allocate only the resources required for occupying the new position.
    freeAllResources();
    // If the vehicle is at an unknown position, it's impossible to say
    // which resources it needs, so don't allocate any in that case.
    if (point != null) {
      try {
        Set<TCSResource<?>> requiredResource = new HashSet<>();
        requiredResource.add(point);
        scheduler.allocateNow(this, requiredResource);
        allocatedResources.add(requiredResource);
      }
      catch (ResourceAllocationException exc) {
        LOG.warn("{}: Could not allocate required resources immediately, ignored.",
                 vehicle.getName(),
                 exc);
      }
    }
    updatePosition(toReference(point), null);
  }

  private void updatePositionWithOrder(String position, Point point) {
    // If a drive order is being processed, check if the reported position
    // is the one we expect.
    MovementCommand moveCommand = commandsSent.stream().findFirst().get();

    Point dstPoint = moveCommand.getStep().getDestinationPoint();
    if (dstPoint.getName().equals(position)) {
      // Update the vehicle's progress index.
      localKernel.setVehicleRouteProgressIndex(vehicle.getReference(),
                                               moveCommand.getStep().getRouteIndex());
      // Let the scheduler know where we are now.
      scheduler.updateProgressIndex(this, moveCommand.getStep().getRouteIndex());
    }
    else if (position == null) {
      LOG.info("{}: Resetting position for vehicle", vehicle.getName());
    }
    else {
      LOG.warn("{}: Reported position: {}, expected: {}",
               vehicle.getName(),
               position,
               dstPoint.getName());
    }

    updatePosition(toReference(point), extractNextPosition(findNextCommand()));
  }

  private void updatePosition(TCSObjectReference<Point> posRef,
                              TCSObjectReference<Point> nextPosRef) {
    localKernel.setVehiclePosition(vehicle.getReference(), posRef);
    localKernel.setVehicleNextPosition(vehicle.getReference(), nextPosRef);
  }

  private static TCSObjectReference<Point> toReference(Point point) {
    return point == null ? null : point.getReference();
  }

  private static TCSObjectReference<Point> extractNextPosition(MovementCommand nextCommand) {
    if (nextCommand == null) {
      return null;
    }
    else {
      return nextCommand.getStep().getDestinationPoint().getReference();
    }
  }

  /**
   * Merges the properties of a transport order and those of a drive order.
   *
   * @param orderProps The properties of a transport order.
   * @param destProps The properties of a drive order destination.
   * @return The merged properties.
   */
  private static Map<String, String> mergeProperties(Map<String, String> orderProps,
                                                     Map<String, String> destProps) {
    requireNonNull(orderProps, "orderProps");
    requireNonNull(destProps, "destProps");

    Map<String, String> result = new HashMap<>();
    result.putAll(orderProps);
    result.putAll(destProps);
    return result;
  }

  private static List<Set<TCSResource<?>>> asResourceSequence(@Nonnull List<Route.Step> steps) {
    requireNonNull(steps, "steps");

    List<Set<TCSResource<?>>> result = new ArrayList<>(steps.size());
    for (Route.Step step : steps) {
      result.add(new HashSet<>(Arrays.asList(step.getDestinationPoint(), step.getPath())));
    }
    return result;
  }
}
