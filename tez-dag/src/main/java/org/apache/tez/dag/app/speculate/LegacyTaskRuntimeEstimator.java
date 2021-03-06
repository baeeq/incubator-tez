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

package org.apache.tez.dag.app.speculate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.tez.dag.api.oldrecords.TaskAttemptState;
import org.apache.tez.dag.app.dag.DAG;
import org.apache.tez.dag.app.dag.Task;
import org.apache.tez.dag.app.dag.TaskAttempt;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventStatusUpdate.TaskAttemptStatus;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.dag.records.TezTaskID;

public class LegacyTaskRuntimeEstimator extends StartEndTimesBase {

  private final Map<TaskAttempt, AtomicLong> attemptRuntimeEstimates
      = new ConcurrentHashMap<TaskAttempt, AtomicLong>();
  private final ConcurrentHashMap<TaskAttempt, AtomicLong> attemptRuntimeEstimateVariances
      = new ConcurrentHashMap<TaskAttempt, AtomicLong>();

  @Override
  public void updateAttempt(TaskAttemptStatus status, long timestamp) {
    super.updateAttempt(status, timestamp);
    

    TezTaskAttemptID attemptID = status.id;
    TezTaskID taskID = attemptID.getTaskID();
    DAG job = context.getDAG();

    if (job == null) {
      return;
    }

    Task task = job.getVertex(taskID.getVertexID()).getTask(taskID);

    if (task == null) {
      return;
    }

    TaskAttempt taskAttempt = task.getAttempt(attemptID);

    if (taskAttempt == null) {
      return;
    }

    Long boxedStart = (Long) startTimes.get(attemptID);
    long start = boxedStart == null ? Long.MIN_VALUE : boxedStart;

    // We need to do two things.
    //  1: If this is a completion, we accumulate statistics in the superclass
    //  2: If this is not a completion, we learn more about it.

    // This is not a completion, but we're cooking.
    //
    if (taskAttempt.getState() == TaskAttemptState.RUNNING) {
      // See if this task is already in the registry
      AtomicLong estimateContainer = attemptRuntimeEstimates.get(taskAttempt);
      AtomicLong estimateVarianceContainer
          = attemptRuntimeEstimateVariances.get(taskAttempt);

      if (estimateContainer == null) {
        if (attemptRuntimeEstimates.get(taskAttempt) == null) {
          attemptRuntimeEstimates.put(taskAttempt, new AtomicLong());

          estimateContainer = attemptRuntimeEstimates.get(taskAttempt);
        }
      }

      if (estimateVarianceContainer == null) {
        attemptRuntimeEstimateVariances.putIfAbsent(taskAttempt, new AtomicLong());
        estimateVarianceContainer = attemptRuntimeEstimateVariances.get(taskAttempt);
      }


      long estimate = -1;
      long varianceEstimate = -1;

      // This code assumes that we'll never consider starting a third
      //  speculative task attempt if two are already running for this task
      if (start > 0 && timestamp > start) {
        estimate = (long) ((timestamp - start) / Math.max(0.0001, status.progress));
        varianceEstimate = (long) (estimate * status.progress / 10);
      }
      if (estimateContainer != null) {
        estimateContainer.set(estimate);
      }
      if (estimateVarianceContainer != null) {
        estimateVarianceContainer.set(varianceEstimate);
      }
    }
  }

  private long storedPerAttemptValue
       (Map<TaskAttempt, AtomicLong> data, TezTaskAttemptID attemptID) {
    TezTaskID taskID = attemptID.getTaskID();
    DAG job = context.getDAG();

    Task task = job.getVertex(taskID.getVertexID()).getTask(taskID);

    if (task == null) {
      return -1L;
    }

    TaskAttempt taskAttempt = task.getAttempt(attemptID);

    if (taskAttempt == null) {
      return -1L;
    }

    AtomicLong estimate = data.get(taskAttempt);

    return estimate == null ? -1L : estimate.get();

  }

  @Override
  public long estimatedRuntime(TezTaskAttemptID attemptID) {
    return storedPerAttemptValue(attemptRuntimeEstimates, attemptID);
  }

  @Override
  public long runtimeEstimateVariance(TezTaskAttemptID attemptID) {
    return storedPerAttemptValue(attemptRuntimeEstimateVariances, attemptID);
  }
}
