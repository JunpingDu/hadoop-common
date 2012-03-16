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

package org.apache.hadoop.mapreduce.v2.app.local;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.client.ClientService;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobCounterUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptContainerAssignedEvent;
import org.apache.hadoop.mapreduce.v2.app.rm.ContainerAllocator;
import org.apache.hadoop.mapreduce.v2.app.rm.ContainerAllocatorEvent;
import org.apache.hadoop.mapreduce.v2.app.rm.RMCommunicator;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.AMResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.util.BuilderUtils;
import org.apache.hadoop.yarn.util.Records;

/**
 * Allocates containers locally. Doesn't allocate a real container;
 * instead sends an allocated event for all requests.
 */
public class LocalContainerAllocator extends RMCommunicator
    implements ContainerAllocator {

  private static final Log LOG =
      LogFactory.getLog(LocalContainerAllocator.class);

  @SuppressWarnings("rawtypes")
  private final EventHandler eventHandler;
  private AtomicInteger containerCount = new AtomicInteger();
  private long retryInterval;
  private long retrystartTime;

  private final RecordFactory recordFactory =
      RecordFactoryProvider.getRecordFactory(null);

  public LocalContainerAllocator(ClientService clientService,
                                 AppContext context) {
    super(clientService, context);
    this.eventHandler = context.getEventHandler();
  }

  @Override
  public void init(Configuration conf) {
    super.init(conf);
    retryInterval =
        getConfig().getLong(MRJobConfig.MR_AM_TO_RM_WAIT_INTERVAL_MS,
            MRJobConfig.DEFAULT_MR_AM_TO_RM_WAIT_INTERVAL_MS);
    // Init startTime to current time. If all goes well, it will be reset after
    // first attempt to contact RM.
    retrystartTime = System.currentTimeMillis();
  }

  @SuppressWarnings("unchecked")
  @Override
  protected synchronized void heartbeat() throws Exception {
    AllocateRequest allocateRequest = BuilderUtils.newAllocateRequest(
        this.applicationAttemptId, this.lastResponseID, super
            .getApplicationProgress(), new ArrayList<ResourceRequest>(),
        new ArrayList<ContainerId>());
    AllocateResponse allocateResponse = scheduler.allocate(allocateRequest);
    AMResponse response;
    try {
      response = allocateResponse.getAMResponse();
      // Reset retry count if no exception occurred.
      retrystartTime = System.currentTimeMillis();
    } catch (Exception e) {
      // This can happen when the connection to the RM has gone down. Keep
      // re-trying until the retryInterval has expired.
      if (System.currentTimeMillis() - retrystartTime >= retryInterval) {
        LOG.error("Could not contact RM after " + retryInterval + " milliseconds.");
        eventHandler.handle(new JobEvent(this.getJob().getID(),
                                         JobEventType.INTERNAL_ERROR));
        throw new YarnException("Could not contact RM after " +
                                retryInterval + " milliseconds.");
      }
      // Throw this up to the caller, which may decide to ignore it and
      // continue to attempt to contact the RM.
      throw e;
    }
    if (response.getReboot()) {
      LOG.info("Event from RM: shutting down Application Master");
      // This can happen if the RM has been restarted. If it is in that state,
      // this application must clean itself up.
      eventHandler.handle(new JobEvent(this.getJob().getID(),
                                       JobEventType.INTERNAL_ERROR));
      throw new YarnException("Resource Manager doesn't recognize AttemptId: " +
                               this.getContext().getApplicationID());
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void handle(ContainerAllocatorEvent event) {
    if (event.getType() == ContainerAllocator.EventType.CONTAINER_REQ) {
      LOG.info("Processing the event " + event.toString());
      ContainerId cID = recordFactory.newRecordInstance(ContainerId.class);
      cID.setApplicationAttemptId(applicationAttemptId);
      // use negative ids to denote that these are local. Need a better way ??
      cID.setId((-1) * containerCount.getAndIncrement());
      
      Container container = recordFactory.newRecordInstance(Container.class);
      container.setId(cID);
      NodeId nodeId = Records.newRecord(NodeId.class);
      nodeId.setHost("localhost");
      nodeId.setPort(1234);
      container.setNodeId(nodeId);
      container.setContainerToken(null);
      container.setNodeHttpAddress("localhost:8042");
      // send the container-assigned event to task attempt

      if (event.getAttemptID().getTaskId().getTaskType() == TaskType.MAP) {
        JobCounterUpdateEvent jce =
            new JobCounterUpdateEvent(event.getAttemptID().getTaskId()
                .getJobId());
        // TODO Setting OTHER_LOCAL_MAP for now.
        jce.addCounterUpdate(JobCounter.OTHER_LOCAL_MAPS, 1);
        eventHandler.handle(jce);
      }
      eventHandler.handle(new TaskAttemptContainerAssignedEvent(
          event.getAttemptID(), container, applicationACLs));
    }
  }

}