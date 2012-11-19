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
package org.apache.hadoop.yarn.server.resourcemanager.resource;

import java.util.Comparator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.Resource;

/**
 * A set of {@link Resource} comparision and manipulation interfaces.
 */
@Private
@Unstable
public abstract class ResourceCalculator implements Comparator<Resource> {

  private static final Log LOG = LogFactory.getLog(ResourceCalculator.class);

  public static int divideAndCeil(int a, int b) {
    if (b == 0) {
      LOG.info("divideAndCeil called with a=" + a + " b=" + b);
      return 0;
    }
    return (a + (b - 1)) / b;
  }

  public static int roundUp(int a, int b) {
    return divideAndCeil(a, b) * b;
  }

  public static int roundDown(int a, int b) {
    return (a / b) * b;
  }

  public abstract int computeAvailableContainers(
      Resource available, Resource required);

  public abstract Resource multiplyAndNormalizeUp(
      Resource lhs, double by, Resource factor);
  
  public abstract Resource multiplyAndNormalizeDown(
      Resource lhs, double by, Resource factor);
  
  public abstract Resource roundUp(Resource lhs, Resource rhs);

  public abstract Resource roundDown(Resource lhs, Resource rhs);
  
  public abstract float divide(Resource lhs, Resource rhs);
  
  public abstract float ratio(Resource lhs, Resource rhs);

  public abstract Resource divideAndCeil(Resource lhs, int rhs);
  
}
