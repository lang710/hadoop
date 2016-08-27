/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationResourceUsageReport;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceOption;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.api.records.UpdateContainerRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.InvalidResourceRequestException;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.SchedulerResourceTypes;
import org.apache.hadoop.yarn.server.api.protocolrecords.NMContainerStatus;
import org.apache.hadoop.yarn.server.resourcemanager.RMAuditLogger;
import org.apache.hadoop.yarn.server.resourcemanager.RMAuditLogger.AuditConstants;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppMoveEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerFinishedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer
    .RMContainerNMDoneChangeResourceEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerRecoverEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeCleanContainerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity
    .LeafQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.QueueEntitlement;
import org.apache.hadoop.yarn.util.resource.Resources;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;


@SuppressWarnings("unchecked")
@Private
@Unstable
public abstract class AbstractYarnScheduler
    <T extends SchedulerApplicationAttempt, N extends SchedulerNode>
    extends AbstractService implements ResourceScheduler {

  private static final Log LOG = LogFactory.getLog(AbstractYarnScheduler.class);

  protected final ClusterNodeTracker<N> nodeTracker =
      new ClusterNodeTracker<>();

  protected Resource minimumAllocation;

  protected RMContext rmContext;
  
  private volatile Priority maxClusterLevelAppPriority;

  /*
   * All schedulers which are inheriting AbstractYarnScheduler should use
   * concurrent version of 'applications' map.
   */
  protected ConcurrentMap<ApplicationId, SchedulerApplication<T>> applications;
  protected int nmExpireInterval;

  protected final static List<Container> EMPTY_CONTAINER_LIST =
      new ArrayList<Container>();
  protected static final Allocation EMPTY_ALLOCATION = new Allocation(
    EMPTY_CONTAINER_LIST, Resources.createResource(0), null, null, null);

  /**
   * Construct the service.
   *
   * @param name service name
   */
  public AbstractYarnScheduler(String name) {
    super(name);
  }

  @Override
  public void serviceInit(Configuration conf) throws Exception {
    nmExpireInterval =
        conf.getInt(YarnConfiguration.RM_NM_EXPIRY_INTERVAL_MS,
          YarnConfiguration.DEFAULT_RM_NM_EXPIRY_INTERVAL_MS);
    long configuredMaximumAllocationWaitTime =
        conf.getLong(YarnConfiguration.RM_WORK_PRESERVING_RECOVERY_SCHEDULING_WAIT_MS,
          YarnConfiguration.DEFAULT_RM_WORK_PRESERVING_RECOVERY_SCHEDULING_WAIT_MS);
    nodeTracker.setConfiguredMaxAllocationWaitTime(
        configuredMaximumAllocationWaitTime);
    maxClusterLevelAppPriority = getMaxPriorityFromConf(conf);
    createReleaseCache();
    super.serviceInit(conf);
  }

  @VisibleForTesting
  public ClusterNodeTracker getNodeTracker() {
    return nodeTracker;
  }

  public List<Container> getTransferredContainers(
      ApplicationAttemptId currentAttempt) {
    ApplicationId appId = currentAttempt.getApplicationId();
    SchedulerApplication<T> app = applications.get(appId);
    List<Container> containerList = new ArrayList<Container>();
    RMApp appImpl = this.rmContext.getRMApps().get(appId);
    if (appImpl.getApplicationSubmissionContext().getUnmanagedAM()) {
      return containerList;
    }
    if (app == null) {
      return containerList;
    }
    Collection<RMContainer> liveContainers =
        app.getCurrentAppAttempt().getLiveContainers();
    ContainerId amContainerId =
        rmContext.getRMApps().get(appId).getCurrentAppAttempt()
          .getMasterContainer().getId();
    for (RMContainer rmContainer : liveContainers) {
      if (!rmContainer.getContainerId().equals(amContainerId)) {
        containerList.add(rmContainer.getContainer());
      }
    }
    return containerList;
  }

  public Map<ApplicationId, SchedulerApplication<T>>
      getSchedulerApplications() {
    return applications;
  }

  /**
   * Add blacklisted NodeIds to the list that is passed.
   *
   * @param app application attempt.
   */
  public List<N> getBlacklistedNodes(final SchedulerApplicationAttempt app) {

    NodeFilter nodeFilter = new NodeFilter() {
      @Override
      public boolean accept(SchedulerNode node) {
        return SchedulerAppUtils.isPlaceBlacklisted(app, node, LOG);
      }
    };
    return nodeTracker.getNodes(nodeFilter);
  }

  @Override
  public Resource getClusterResource() {
    return nodeTracker.getClusterCapacity();
  }

  @Override
  public Resource getMinimumResourceCapability() {
    return minimumAllocation;
  }

  @Override
  public Resource getMaximumResourceCapability() {
    return nodeTracker.getMaxAllowedAllocation();
  }

  @Override
  public Resource getMaximumResourceCapability(String queueName) {
    return getMaximumResourceCapability();
  }

  protected void initMaximumResourceCapability(Resource maximumAllocation) {
    nodeTracker.setConfiguredMaxAllocation(maximumAllocation);
  }

  protected synchronized void containerLaunchedOnNode(
      ContainerId containerId, SchedulerNode node) {
    // Get the application for the finished container
    SchedulerApplicationAttempt application =
        getCurrentAttemptForContainer(containerId);
    if (application == null) {
      LOG.info("Unknown application " + containerId.getApplicationAttemptId()
          .getApplicationId() + " launched container " + containerId
          + " on node: " + node);
      this.rmContext.getDispatcher().getEventHandler()
        .handle(new RMNodeCleanContainerEvent(node.getNodeID(), containerId));
      return;
    }

    application.containerLaunchedOnNode(containerId, node.getNodeID());
  }
  
  protected void containerIncreasedOnNode(ContainerId containerId,
      SchedulerNode node, Container increasedContainerReportedByNM) {
    // Get the application for the finished container
    SchedulerApplicationAttempt application =
        getCurrentAttemptForContainer(containerId);
    if (application == null) {
      LOG.info("Unknown application "
          + containerId.getApplicationAttemptId().getApplicationId()
          + " increased container " + containerId + " on node: " + node);
      this.rmContext.getDispatcher().getEventHandler()
          .handle(new RMNodeCleanContainerEvent(node.getNodeID(), containerId));
      return;
    }
    LeafQueue leafQueue = (LeafQueue) application.getQueue();
    synchronized (leafQueue) {
      RMContainer rmContainer = getRMContainer(containerId);
      if (rmContainer == null) {
        // Some unknown container sneaked into the system. Kill it.
        this.rmContext.getDispatcher().getEventHandler()
            .handle(new RMNodeCleanContainerEvent(
                node.getNodeID(), containerId));
        return;
      }
      rmContainer.handle(new RMContainerNMDoneChangeResourceEvent(
          containerId, increasedContainerReportedByNM.getResource()));
    }
  }

  public T getApplicationAttempt(ApplicationAttemptId applicationAttemptId) {
    SchedulerApplication<T> app =
        applications.get(applicationAttemptId.getApplicationId());
    return app == null ? null : app.getCurrentAppAttempt();
  }

  @Override
  public SchedulerAppReport getSchedulerAppInfo(
      ApplicationAttemptId appAttemptId) {
    SchedulerApplicationAttempt attempt = getApplicationAttempt(appAttemptId);
    if (attempt == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Request for appInfo of unknown attempt " + appAttemptId);
      }
      return null;
    }
    return new SchedulerAppReport(attempt);
  }

  @Override
  public ApplicationResourceUsageReport getAppResourceUsageReport(
      ApplicationAttemptId appAttemptId) {
    SchedulerApplicationAttempt attempt = getApplicationAttempt(appAttemptId);
    if (attempt == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Request for appInfo of unknown attempt " + appAttemptId);
      }
      return null;
    }
    return attempt.getResourceUsageReport();
  }

  public T getCurrentAttemptForContainer(ContainerId containerId) {
    return getApplicationAttempt(containerId.getApplicationAttemptId());
  }

  @Override
  public RMContainer getRMContainer(ContainerId containerId) {
    SchedulerApplicationAttempt attempt =
        getCurrentAttemptForContainer(containerId);
    return (attempt == null) ? null : attempt.getRMContainer(containerId);
  }

  @Override
  public SchedulerNodeReport getNodeReport(NodeId nodeId) {
    return nodeTracker.getNodeReport(nodeId);
  }

  @Override
  public String moveApplication(ApplicationId appId, String newQueue)
      throws YarnException {
    throw new YarnException(getClass().getSimpleName()
        + " does not support moving apps between queues");
  }

  public void removeQueue(String queueName) throws YarnException {
    throw new YarnException(getClass().getSimpleName()
        + " does not support removing queues");
  }

  @Override
  public void addQueue(Queue newQueue) throws YarnException {
    throw new YarnException(getClass().getSimpleName()
        + " does not support this operation");
  }

  @Override
  public void setEntitlement(String queue, QueueEntitlement entitlement)
      throws YarnException {
    throw new YarnException(getClass().getSimpleName()
        + " does not support this operation");
  }

  private void killOrphanContainerOnNode(RMNode node,
      NMContainerStatus container) {
    if (!container.getContainerState().equals(ContainerState.COMPLETE)) {
      this.rmContext.getDispatcher().getEventHandler().handle(
        new RMNodeCleanContainerEvent(node.getNodeID(),
          container.getContainerId()));
    }
  }

  public synchronized void recoverContainersOnNode(
      List<NMContainerStatus> containerReports, RMNode nm) {
    if (!rmContext.isWorkPreservingRecoveryEnabled()
        || containerReports == null
        || (containerReports != null && containerReports.isEmpty())) {
      return;
    }

    for (NMContainerStatus container : containerReports) {
      ApplicationId appId =
          container.getContainerId().getApplicationAttemptId().getApplicationId();
      RMApp rmApp = rmContext.getRMApps().get(appId);
      if (rmApp == null) {
        LOG.error("Skip recovering container " + container
            + " for unknown application.");
        killOrphanContainerOnNode(nm, container);
        continue;
      }

      SchedulerApplication<T> schedulerApp = applications.get(appId);
      if (schedulerApp == null) {
        LOG.info("Skip recovering container  " + container
            + " for unknown SchedulerApplication. Application current state is "
            + rmApp.getState());
        killOrphanContainerOnNode(nm, container);
        continue;
      }

      LOG.info("Recovering container " + container);
      SchedulerApplicationAttempt schedulerAttempt =
          schedulerApp.getCurrentAppAttempt();

      if (!rmApp.getApplicationSubmissionContext()
        .getKeepContainersAcrossApplicationAttempts()) {
        // Do not recover containers for stopped attempt or previous attempt.
        if (schedulerAttempt.isStopped()
            || !schedulerAttempt.getApplicationAttemptId().equals(
              container.getContainerId().getApplicationAttemptId())) {
          LOG.info("Skip recovering container " + container
              + " for already stopped attempt.");
          killOrphanContainerOnNode(nm, container);
          continue;
        }
      }

      // create container
      RMContainer rmContainer = recoverAndCreateContainer(container, nm);

      // recover RMContainer
      rmContainer.handle(new RMContainerRecoverEvent(container.getContainerId(),
        container));

      // recover scheduler node
      SchedulerNode schedulerNode = nodeTracker.getNode(nm.getNodeID());
      schedulerNode.recoverContainer(rmContainer);

      // recover queue: update headroom etc.
      Queue queue = schedulerAttempt.getQueue();
      queue.recoverContainer(
          getClusterResource(), schedulerAttempt, rmContainer);

      // recover scheduler attempt
      schedulerAttempt.recoverContainer(schedulerNode, rmContainer);
            
      // set master container for the current running AMContainer for this
      // attempt.
      RMAppAttempt appAttempt = rmApp.getCurrentAppAttempt();
      if (appAttempt != null) {
        Container masterContainer = appAttempt.getMasterContainer();

        // Mark current running AMContainer's RMContainer based on the master
        // container ID stored in AppAttempt.
        if (masterContainer != null
            && masterContainer.getId().equals(rmContainer.getContainerId())) {
          ((RMContainerImpl)rmContainer).setAMContainer(true);
        }
      }

      synchronized (schedulerAttempt) {
        Set<ContainerId> releases = schedulerAttempt.getPendingRelease();
        if (releases.contains(container.getContainerId())) {
          // release the container
          rmContainer.handle(new RMContainerFinishedEvent(container
            .getContainerId(), SchedulerUtils.createAbnormalContainerStatus(
            container.getContainerId(), SchedulerUtils.RELEASED_CONTAINER),
            RMContainerEventType.RELEASED));
          releases.remove(container.getContainerId());
          LOG.info(container.getContainerId() + " is released by application.");
        }
      }
    }
  }

  private RMContainer recoverAndCreateContainer(NMContainerStatus status,
      RMNode node) {
    Container container =
        Container.newInstance(status.getContainerId(), node.getNodeID(),
          node.getHttpAddress(), status.getAllocatedResource(),
          status.getPriority(), null);
    container.setVersion(status.getVersion());
    ApplicationAttemptId attemptId =
        container.getId().getApplicationAttemptId();
    RMContainer rmContainer =
        new RMContainerImpl(container, attemptId, node.getNodeID(),
          applications.get(attemptId.getApplicationId()).getUser(), rmContext,
          status.getCreationTime(), status.getNodeLabelExpression());
    return rmContainer;
  }

  /**
   * Recover resource request back from RMContainer when a container is 
   * preempted before AM pulled the same. If container is pulled by
   * AM, then RMContainer will not have resource request to recover.
   * @param rmContainer rmContainer
   */
  private void recoverResourceRequestForContainer(RMContainer rmContainer) {
    List<ResourceRequest> requests = rmContainer.getResourceRequests();

    // If container state is moved to ACQUIRED, request will be empty.
    if (requests == null) {
      return;
    }

    // Add resource request back to Scheduler ApplicationAttempt.

    // We lookup the application-attempt here again using
    // getCurrentApplicationAttempt() because there is only one app-attempt at
    // any point in the scheduler. But in corner cases, AMs can crash,
    // corresponding containers get killed and recovered to the same-attempt,
    // but because the app-attempt is extinguished right after, the recovered
    // requests don't serve any purpose, but that's okay.
    SchedulerApplicationAttempt schedulerAttempt =
        getCurrentAttemptForContainer(rmContainer.getContainerId());
    if (schedulerAttempt != null) {
      schedulerAttempt.recoverResourceRequestsForContainer(requests);
    }
  }

  protected void createReleaseCache() {
    // Cleanup the cache after nm expire interval.
    new Timer().schedule(new TimerTask() {
      @Override
      public void run() {
        clearPendingContainerCache();
        LOG.info("Release request cache is cleaned up");
      }
    }, nmExpireInterval);
  }

  @VisibleForTesting
  public void clearPendingContainerCache() {
    for (SchedulerApplication<T> app : applications.values()) {
      T attempt = app.getCurrentAppAttempt();
      if (attempt != null) {
        synchronized (attempt) {
          for (ContainerId containerId : attempt.getPendingRelease()) {
            RMAuditLogger.logFailure(app.getUser(),
                AuditConstants.RELEASE_CONTAINER,
                "Unauthorized access or invalid container", "Scheduler",
                "Trying to release container not owned by app "
                    + "or with invalid id.", attempt.getApplicationId(),
                containerId, null);
          }
          attempt.getPendingRelease().clear();
        }
      }
    }
  }

  @VisibleForTesting
  @Private
  // clean up a completed container
  public void completedContainer(RMContainer rmContainer,
      ContainerStatus containerStatus, RMContainerEventType event) {

    if (rmContainer == null) {
      LOG.info("Container " + containerStatus.getContainerId()
          + " completed with event " + event
          + ", but corresponding RMContainer doesn't exist.");
      return;
    }

    if (!rmContainer.isRemotelyAllocated()) {
      completedContainerInternal(rmContainer, containerStatus, event);
    } else {
      ContainerId containerId = rmContainer.getContainerId();
      // Inform the container
      rmContainer.handle(
          new RMContainerFinishedEvent(containerId, containerStatus, event));
      SchedulerApplicationAttempt schedulerAttempt =
          getCurrentAttemptForContainer(containerId);
      if (schedulerAttempt != null) {
        schedulerAttempt.removeRMContainer(containerId);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Completed container: " + rmContainer.getContainerId() +
            " in state: " + rmContainer.getState() + " event:" + event);
      }
    }

    // If the container is getting killed in ACQUIRED state, the requester (AM
    // for regular containers and RM itself for AM container) will not know what
    // happened. Simply add the ResourceRequest back again so that requester
    // doesn't need to do anything conditionally.
    recoverResourceRequestForContainer(rmContainer);
  }

  // clean up a completed container
  protected abstract void completedContainerInternal(RMContainer rmContainer,
      ContainerStatus containerStatus, RMContainerEventType event);

  protected void releaseContainers(List<ContainerId> containers,
      SchedulerApplicationAttempt attempt) {
    for (ContainerId containerId : containers) {
      RMContainer rmContainer = getRMContainer(containerId);
      if (rmContainer == null) {
        if (System.currentTimeMillis() - ResourceManager.getClusterTimeStamp()
            < nmExpireInterval) {
          LOG.info(containerId + " doesn't exist. Add the container"
              + " to the release request cache as it maybe on recovery.");
          synchronized (attempt) {
            attempt.getPendingRelease().add(containerId);
          }
        } else {
          RMAuditLogger.logFailure(attempt.getUser(),
            AuditConstants.RELEASE_CONTAINER,
            "Unauthorized access or invalid container", "Scheduler",
            "Trying to release container not owned by app or with invalid id.",
            attempt.getApplicationId(), containerId, null);
        }
      }
      completedContainer(rmContainer,
        SchedulerUtils.createAbnormalContainerStatus(containerId,
          SchedulerUtils.RELEASED_CONTAINER), RMContainerEventType.RELEASED);
    }
  }

  protected void decreaseContainers(
      List<UpdateContainerRequest> decreaseRequests,
      SchedulerApplicationAttempt attempt) {
    if (null == decreaseRequests || decreaseRequests.isEmpty()) {
      return;
    }
    // Pre-process decrease requests
    List<SchedContainerChangeRequest> schedDecreaseRequests =
        createSchedContainerChangeRequests(decreaseRequests, false);
    for (SchedContainerChangeRequest request : schedDecreaseRequests) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Processing decrease request:" + request);
      }
      // handle decrease request
      decreaseContainer(request, attempt);
    }
  }

  protected abstract void decreaseContainer(
      SchedContainerChangeRequest decreaseRequest,
      SchedulerApplicationAttempt attempt);

  @Override
  public SchedulerNode getSchedulerNode(NodeId nodeId) {
    return nodeTracker.getNode(nodeId);
  }

  @Override
  public synchronized void moveAllApps(String sourceQueue, String destQueue)
      throws YarnException {
    // check if destination queue is a valid leaf queue
    try {
      getQueueInfo(destQueue, false, false);
    } catch (IOException e) {
      LOG.warn(e);
      throw new YarnException(e);
    }
    // check if source queue is a valid
    List<ApplicationAttemptId> apps = getAppsInQueue(sourceQueue);
    if (apps == null) {
      String errMsg = "The specified Queue: " + sourceQueue + " doesn't exist";
      LOG.warn(errMsg);
      throw new YarnException(errMsg);
    }
    // generate move events for each pending/running app
    for (ApplicationAttemptId app : apps) {
      SettableFuture<Object> future = SettableFuture.create();
      this.rmContext
          .getDispatcher()
          .getEventHandler()
          .handle(new RMAppMoveEvent(app.getApplicationId(), destQueue, future));
    }
  }

  @Override
  public synchronized void killAllAppsInQueue(String queueName)
      throws YarnException {
    // check if queue is a valid
    List<ApplicationAttemptId> apps = getAppsInQueue(queueName);
    if (apps == null) {
      String errMsg = "The specified Queue: " + queueName + " doesn't exist";
      LOG.warn(errMsg);
      throw new YarnException(errMsg);
    }
    // generate kill events for each pending/running app
    for (ApplicationAttemptId app : apps) {
      this.rmContext
          .getDispatcher()
          .getEventHandler()
          .handle(new RMAppEvent(app.getApplicationId(), RMAppEventType.KILL,
          "Application killed due to expiry of reservation queue " +
          queueName + "."));
    }
  }
  
  /**
   * Process resource update on a node.
   */
  public synchronized void updateNodeResource(RMNode nm, 
      ResourceOption resourceOption) {
    SchedulerNode node = getSchedulerNode(nm.getNodeID());
    Resource newResource = resourceOption.getResource();
    Resource oldResource = node.getTotalResource();
    if(!oldResource.equals(newResource)) {
      // Notify NodeLabelsManager about this change
      rmContext.getNodeLabelManager().updateNodeResource(nm.getNodeID(),
          newResource);
      
      // Log resource change
      LOG.info("Update resource on node: " + node.getNodeName()
          + " from: " + oldResource + ", to: "
          + newResource);

      nodeTracker.removeNode(nm.getNodeID());

      // update resource to node
      node.updateTotalResource(newResource);

      nodeTracker.addNode((N) node);
    } else {
      // Log resource change
      LOG.warn("Update resource on node: " + node.getNodeName() 
          + " with the same resource: " + newResource);
    }
  }

  /** {@inheritDoc} */
  @Override
  public EnumSet<SchedulerResourceTypes> getSchedulingResourceTypes() {
    return EnumSet.of(SchedulerResourceTypes.MEMORY);
  }

  @Override
  public Set<String> getPlanQueues() throws YarnException {
    throw new YarnException(getClass().getSimpleName()
        + " does not support reservations");
  }

  protected void refreshMaximumAllocation(Resource newMaxAlloc) {
    nodeTracker.setConfiguredMaxAllocation(newMaxAlloc);
  }

  @Override
  public List<ResourceRequest> getPendingResourceRequestsForAttempt(
      ApplicationAttemptId attemptId) {
    SchedulerApplicationAttempt attempt = getApplicationAttempt(attemptId);
    if (attempt != null) {
      return attempt.getAppSchedulingInfo().getAllResourceRequests();
    }
    return null;
  }

  @Override
  public Priority checkAndGetApplicationPriority(Priority priorityFromContext,
      String user, String queueName, ApplicationId applicationId)
      throws YarnException {
    // Dummy Implementation till Application Priority changes are done in
    // specific scheduler.
    return Priority.newInstance(0);
  }

  @Override
  public void updateApplicationPriority(Priority newPriority,
      ApplicationId applicationId) throws YarnException {
    // Dummy Implementation till Application Priority changes are done in
    // specific scheduler.
  }

  @Override
  public Priority getMaxClusterLevelAppPriority() {
    return maxClusterLevelAppPriority;
  }

  private Priority getMaxPriorityFromConf(Configuration conf) {
    return Priority.newInstance(conf.getInt(
        YarnConfiguration.MAX_CLUSTER_LEVEL_APPLICATION_PRIORITY,
        YarnConfiguration.DEFAULT_CLUSTER_LEVEL_APPLICATION_PRIORITY));
  }

  @Override
  public synchronized void setClusterMaxPriority(Configuration conf)
      throws YarnException {
    try {
      maxClusterLevelAppPriority = getMaxPriorityFromConf(conf);
    } catch (NumberFormatException e) {
      throw new YarnException(e);
    }
    LOG.info("Updated the cluste max priority to maxClusterLevelAppPriority = "
        + maxClusterLevelAppPriority);
  }
  
  /**
   * Sanity check increase/decrease request, and return
   * SchedulerContainerResourceChangeRequest according to given
   * UpdateContainerRequest.
   * 
   * <pre>
   * - Returns non-null value means validation succeeded
   * - Throw exception when any other error happens
   * </pre>
   */
  private SchedContainerChangeRequest createSchedContainerChangeRequest(
      UpdateContainerRequest request, boolean increase)
      throws YarnException {
    ContainerId containerId = request.getContainerId();
    RMContainer rmContainer = getRMContainer(containerId);
    if (null == rmContainer) {
      String msg =
          "Failed to get rmContainer for "
              + (increase ? "increase" : "decrease")
              + " request, with container-id=" + containerId;
      throw new InvalidResourceRequestException(msg);
    }
    SchedulerNode schedulerNode =
        getSchedulerNode(rmContainer.getAllocatedNode());
    return new SchedContainerChangeRequest(
        this.rmContext, schedulerNode, rmContainer, request.getCapability());
  }

  protected List<SchedContainerChangeRequest>
      createSchedContainerChangeRequests(
          List<UpdateContainerRequest> changeRequests,
          boolean increase) {
    List<SchedContainerChangeRequest> schedulerChangeRequests =
        new ArrayList<SchedContainerChangeRequest>();
    for (UpdateContainerRequest r : changeRequests) {
      SchedContainerChangeRequest sr = null;
      try {
        sr = createSchedContainerChangeRequest(r, increase);
      } catch (YarnException e) {
        LOG.warn("Error happens when checking increase request, Ignoring.."
            + " exception=", e);
        continue;
      }
      schedulerChangeRequests.add(sr);
    }
    return schedulerChangeRequests;
  }
}
