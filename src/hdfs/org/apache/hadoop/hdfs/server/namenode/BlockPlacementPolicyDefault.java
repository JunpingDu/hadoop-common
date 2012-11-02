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
package org.apache.hadoop.hdfs.server.namenode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;

/** The class is responsible for choosing the desired number of targets
 * for placing block replicas.
 * The replica placement strategy is that if the writer is on a datanode,
 * the 1st replica is placed on the local machine, 
 * otherwise a random datanode. The 2nd replica is placed on a datanode
 * that is on a different rack. The 3rd replica is placed on a datanode
 * which is on a different node of the rack as the second replica.
 */
@InterfaceAudience.Private
public class BlockPlacementPolicyDefault extends BlockPlacementPolicy {
  private boolean considerLoad; 
  protected NetworkTopology clusterMap;
  private FSClusterStats stats;
  private long staleInterval;   // interval used to identify stale DataNodes

  BlockPlacementPolicyDefault(Configuration conf,  FSClusterStats stats,
                           NetworkTopology clusterMap) {
    initialize(conf, stats, clusterMap);
  }

  BlockPlacementPolicyDefault() {
  }
    
  /** {@inheritDoc} */
  public void initialize(Configuration conf,  FSClusterStats stats,
                         NetworkTopology clusterMap) {
    this.considerLoad = conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_REPLICATION_CONSIDERLOAD_KEY, true);
    this.stats = stats;
    this.clusterMap = clusterMap;
    this.staleInterval = conf.getLong(
        DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_INTERVAL_KEY,
        DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_INTERVAL_DEFAULT);
  }

  /** {@inheritDoc} */
  public DatanodeDescriptor[] chooseTarget(String srcPath,
                                    int numOfReplicas,
                                    DatanodeDescriptor writer,
                                    List<DatanodeDescriptor> chosenNodes,
                                    long blocksize) {
    return chooseTarget(numOfReplicas, writer, chosenNodes, null, blocksize);
  }

  /** {@inheritDoc} */
  public DatanodeDescriptor[] chooseTarget(String srcPath,
                                    int numOfReplicas,
                                    DatanodeDescriptor writer,
                                    List<DatanodeDescriptor> chosenNodes,
                                    HashMap<Node, Node> excludedNodes,
                                    long blocksize) {
    return chooseTarget(numOfReplicas, writer, chosenNodes, excludedNodes, blocksize);
  }


  /** {@inheritDoc} */
  @Override
  public DatanodeDescriptor[] chooseTarget(FSInodeInfo srcInode,
                                    int numOfReplicas,
                                    DatanodeDescriptor writer,
                                    List<DatanodeDescriptor> chosenNodes,
                                    long blocksize) {
    return chooseTarget(numOfReplicas, writer, chosenNodes, null, blocksize);
  }
  
  /**
   * Add <i>localMachine</i> and related nodes to <i>excludedNodes</i>
   * for next replica choosing.
   * In sub class, we can add more related nodes than localMachine
   * @return number of new excluded nodes 
   */
  protected int addToExcludedNodes(DatanodeDescriptor localMachine,
                                   HashMap<Node, Node> excludedNodes){
    Node node = excludedNodes.put(localMachine, localMachine);
    return node == null ? 1:0;
  }
  
  /**
   * This is not part of the public API but is used by the unit tests.
   */
  DatanodeDescriptor[] chooseTarget(int numOfReplicas,
                                    DatanodeDescriptor writer,
                                    List<DatanodeDescriptor> chosenNodes,
                                    HashMap<Node, Node> excludedNodes,
                                    long blocksize) {
    if (numOfReplicas == 0 || clusterMap.getNumOfLeaves()==0) {
      return new DatanodeDescriptor[0];
    }
      
    if (excludedNodes == null) {
      excludedNodes = new HashMap<Node, Node>();
    }
     
    int clusterSize = clusterMap.getNumOfLeaves();
    int totalNumOfReplicas = chosenNodes.size()+numOfReplicas;
    if (totalNumOfReplicas > clusterSize) {
      numOfReplicas -= (totalNumOfReplicas-clusterSize);
      totalNumOfReplicas = clusterSize;
    }
      
    int maxNodesPerRack = 
      (totalNumOfReplicas-1)/clusterMap.getNumOfRacks()+2;
      
    List<DatanodeDescriptor> results = 
      new ArrayList<DatanodeDescriptor>(chosenNodes);
    for (DatanodeDescriptor node:chosenNodes) {
      // add localMachine and related nodes to excludedNodes
      addToExcludedNodes(node, excludedNodes);
    }
      
    if (!clusterMap.contains(writer)) {
      writer=null;
    }
      
    boolean avoidStaleNodes = (stats != null && stats
        .isAvoidingStaleDataNodesForWrite());
    DatanodeDescriptor localNode = chooseTarget(numOfReplicas, writer,
        excludedNodes, blocksize, maxNodesPerRack, results, avoidStaleNodes);
      
    results.removeAll(chosenNodes);
      
    // sorting nodes to form a pipeline
    return getPipeline((writer==null)?localNode:writer,
                       results.toArray(new DatanodeDescriptor[results.size()]));
  }
    
  /* choose <i>numOfReplicas</i> from all data nodes */
  private DatanodeDescriptor chooseTarget(int numOfReplicas,
      DatanodeDescriptor writer, HashMap<Node, Node> excludedNodes,
      long blocksize, int maxNodesPerRack, List<DatanodeDescriptor> results,
      boolean avoidStaleNodes) {
      
    if (numOfReplicas == 0 || clusterMap.getNumOfLeaves()==0) {
      return writer;
    }
      
    int numOfResults = results.size();
    boolean newBlock = (numOfResults==0);
    if (writer == null && !newBlock) {
      writer = results.get(0);
    }
        
    // Keep a copy of original excludedNodes
    final HashMap<Node, Node> oldExcludedNodes = avoidStaleNodes ? 
        new HashMap<Node, Node>(excludedNodes) : null;
    
    try {
      if (numOfResults == 0) {
        writer = chooseLocalNode(writer, excludedNodes, blocksize,
            maxNodesPerRack, results, avoidStaleNodes);
        if (--numOfReplicas == 0) {
          return writer;
        }
      }
      if (numOfResults <= 1) {
        chooseRemoteRack(1, results.get(0), excludedNodes, blocksize,
            maxNodesPerRack, results, avoidStaleNodes);
        if (--numOfReplicas == 0) {
          return writer;
        }
      }
      if (numOfResults <= 2) {
        if (clusterMap.isOnSameRack(results.get(0), results.get(1))) {
          chooseRemoteRack(1, results.get(0), excludedNodes, blocksize,
              maxNodesPerRack, results, avoidStaleNodes);
        } else if (newBlock){
          chooseLocalRack(results.get(1), excludedNodes, blocksize,
              maxNodesPerRack, results, avoidStaleNodes);
        } else {
          chooseLocalRack(writer, excludedNodes, blocksize, maxNodesPerRack,
              results, avoidStaleNodes);
        }
        if (--numOfReplicas == 0) {
          return writer;
        }
      }
      chooseRandom(numOfReplicas, NodeBase.ROOT, excludedNodes, blocksize,
          maxNodesPerRack, results, avoidStaleNodes);
    } catch (NotEnoughReplicasException e) {
      FSNamesystem.LOG.warn("Not able to place enough replicas, still in need of "
               + numOfReplicas);
      if (avoidStaleNodes) {
        // excludedNodes now has - initial excludedNodes, any nodes that were
        // chosen and nodes that were tried but were not chosen because they
        // were stale, decommissioned or for any other reason a node is not
        // chosen for write. Retry again now not avoiding stale node
        for (Node node : results) {
          oldExcludedNodes.put(node, node);
        }
        return chooseTarget(numOfReplicas, writer, oldExcludedNodes, blocksize,
            maxNodesPerRack, results, false);
      }
    }
    return writer;
  }
  
  /**choose <i>localMachine</i> as the target.
   * if <i>localMachine</i> is not available, 
   * choose a node on the same rack
   * @return the chosen node
   */
  private DatanodeDescriptor chooseLocalNode(DatanodeDescriptor localMachine,
      HashMap<Node, Node> excludedNodes, long blocksize, int maxNodesPerRack,
      List<DatanodeDescriptor> results, boolean avoidStaleNodes)
    throws NotEnoughReplicasException {
    // if no local machine, randomly choose one node
    if (localMachine == null)
      return chooseRandom(NodeBase.ROOT, excludedNodes, blocksize,
          maxNodesPerRack, results, avoidStaleNodes);
      
    // otherwise try local machine first
    Node oldNode = excludedNodes.put(localMachine, localMachine);
    if (oldNode == null) { // was not in the excluded list
      if (isGoodTarget(localMachine, blocksize, maxNodesPerRack, false,
          results, avoidStaleNodes)) {
        results.add(localMachine);
        // add localMachine and related nodes to excludedNodes
        addToExcludedNodes(localMachine, excludedNodes);
        return localMachine;
      }
    } 
      
    // try a node on local rack
    return chooseLocalRack(localMachine, excludedNodes, blocksize,
        maxNodesPerRack, results, avoidStaleNodes);
  }
  
  /** Choose node with other locality other than <i>localMachine</i>.
   * As there is no local node is available, choose one node with nearest 
   * locality.
   * Default to be local rack, but could be overridden in sub-class for other 
   * localities.
   * @return the chosen node
   */
  protected DatanodeDescriptor chooseOtherLocalityNode(DatanodeDescriptor localMachine,
      HashMap<Node, Node> excludedNodes, long blocksize, int maxNodesPerRack,
      List<DatanodeDescriptor> results, boolean avoidStaleNodes) 
      throws NotEnoughReplicasException {
    return chooseLocalRack(localMachine, excludedNodes, blocksize,
        maxNodesPerRack, results, avoidStaleNodes);
  }

  /* choose one node from the rack that <i>localMachine</i> is on.
   * if no such node is available, choose one node from the rack where
   * a second replica is on.
   * if still no such node is available, choose a random node 
   * in the cluster.
   * @return the chosen node
   */
  protected DatanodeDescriptor chooseLocalRack(DatanodeDescriptor localMachine,
      HashMap<Node, Node> excludedNodes, long blocksize, int maxNodesPerRack,
      List<DatanodeDescriptor> results, boolean avoidStaleNodes)
    throws NotEnoughReplicasException {
    // no local machine, so choose a random machine
    if (localMachine == null) {
      return chooseRandom(NodeBase.ROOT, excludedNodes, blocksize,
          maxNodesPerRack, results, avoidStaleNodes);
    }
      
    // choose one from the local rack
    try {
      return chooseRandom(localMachine.getNetworkLocation(), excludedNodes,
          blocksize, maxNodesPerRack, results, avoidStaleNodes);
    } catch (NotEnoughReplicasException e1) {
      // find the second replica
      DatanodeDescriptor newLocal=null;
      for(Iterator<DatanodeDescriptor> iter=results.iterator();
          iter.hasNext();) {
        DatanodeDescriptor nextNode = iter.next();
        if (nextNode != localMachine) {
          newLocal = nextNode;
          break;
        }
      }
      if (newLocal != null) {
        try {
          return chooseRandom(newLocal.getNetworkLocation(), excludedNodes,
              blocksize, maxNodesPerRack, results, avoidStaleNodes);
        } catch(NotEnoughReplicasException e2) {
          // otherwise randomly choose one from the network
          return chooseRandom(NodeBase.ROOT, excludedNodes, blocksize,
              maxNodesPerRack, results, avoidStaleNodes);
        }
      } else {
        //otherwise randomly choose one from the network
        return chooseRandom(NodeBase.ROOT, excludedNodes, blocksize,
            maxNodesPerRack, results, avoidStaleNodes);
      }
    }
  }
    
  /* choose <i>numOfReplicas</i> nodes from the racks 
   * that <i>localMachine</i> is NOT on.
   * if not enough nodes are available, choose the remaining ones 
   * from the local rack
   */
    
  protected void chooseRemoteRack(int numOfReplicas,
                                DatanodeDescriptor localMachine,
                                HashMap<Node, Node> excludedNodes,
                                long blocksize,
                                int maxReplicasPerRack,
                                List<DatanodeDescriptor> results,
                                boolean avoidStaleNodes)
    throws NotEnoughReplicasException {
    int oldNumOfReplicas = results.size();
    // randomly choose one node from remote racks
    try {
      chooseRandom(numOfReplicas, "~" + localMachine.getNetworkLocation(),
          excludedNodes, blocksize, maxReplicasPerRack, results,
          avoidStaleNodes);
    } catch (NotEnoughReplicasException e) {
      chooseRandom(numOfReplicas-(results.size()-oldNumOfReplicas),
                   localMachine.getNetworkLocation(), excludedNodes, blocksize, 
                   maxReplicasPerRack, results, avoidStaleNodes);
    }
  }

  /* Randomly choose one target from <i>nodes</i>.
   * @return the chosen node
   */
  protected DatanodeDescriptor chooseRandom(String nodes,
                                            HashMap<Node, Node> excludedNodes,
                                            long blocksize,
                                            int maxNodesPerRack,
                                            List<DatanodeDescriptor> results,
                                            boolean avoidStaleNodes) 
    throws NotEnoughReplicasException {
    int numOfAvailableNodes =
      clusterMap.countNumOfAvailableNodes(nodes, excludedNodes.keySet());
    while(numOfAvailableNodes > 0) {
      DatanodeDescriptor chosenNode = 
        (DatanodeDescriptor)(clusterMap.chooseRandom(nodes));

      Node oldNode = excludedNodes.put(chosenNode, chosenNode);
      if (oldNode == null) { // choosendNode was not in the excluded list
        numOfAvailableNodes--;
        if (isGoodTarget(chosenNode, blocksize, maxNodesPerRack, results,
            avoidStaleNodes)) {
          results.add(chosenNode);
          // add localMachine and related nodes to excludedNodes
          int newExcludedNodes = addToExcludedNodes(chosenNode, excludedNodes);
          numOfAvailableNodes -= newExcludedNodes;
          return chosenNode;
        }
      }
    }

    throw new NotEnoughReplicasException(
        "Not able to place enough replicas");
  }
    
  /* Randomly choose <i>numOfReplicas</i> targets from <i>nodes</i>.
   */
  protected void chooseRandom(int numOfReplicas,
                            String nodes,
                            HashMap<Node, Node> excludedNodes,
                            long blocksize,
                            int maxNodesPerRack,
                            List<DatanodeDescriptor> results,
                            boolean avoidStaleNodes)
    throws NotEnoughReplicasException {
      
    int numOfAvailableNodes =
      clusterMap.countNumOfAvailableNodes(nodes, excludedNodes.keySet());
    while(numOfReplicas > 0 && numOfAvailableNodes > 0) {
      DatanodeDescriptor chosenNode = 
        (DatanodeDescriptor)(clusterMap.chooseRandom(nodes));
      Node oldNode = excludedNodes.put(chosenNode, chosenNode);
      if (oldNode == null) {
        numOfAvailableNodes--;

        if (isGoodTarget(chosenNode, blocksize, maxNodesPerRack, results,
            avoidStaleNodes)) {
          numOfReplicas--;
          results.add(chosenNode);
          // add localMachine and related nodes to excludedNodes
          int newExcludedNodes = addToExcludedNodes(chosenNode, excludedNodes);
          numOfAvailableNodes -= newExcludedNodes;
          
        }
      }
    }
      
    if (numOfReplicas>0) {
      throw new NotEnoughReplicasException(
                                           "Not able to place enough replicas");
    }
  }

  /* judge if a node is a good target.
   * return true if <i>node</i> has enough space, 
   * does not have too much load, and the rack does not have too many nodes
   */
  private boolean isGoodTarget(DatanodeDescriptor node, long blockSize,
      int maxTargetPerLoc, List<DatanodeDescriptor> results,
      boolean avoidStaleNodes) {
    return isGoodTarget(node, blockSize, maxTargetPerLoc, this.considerLoad,
        results, avoidStaleNodes);
  }
   
  /**
   * Determine if a node is a good target. 
   * 
   * @param node The target node
   * @param blockSize Size of block
   * @param maxTargetPerLoc Maximum number of targets per rack. The value of 
   *                        this parameter depends on the number of racks in 
   *                        the cluster and total number of replicas for a block
   * @param considerLoad whether or not to consider load of the target node
   * @param results A list containing currently chosen nodes. Used to check if 
   *                too many nodes has been chosen in the target rack. 
   * @param avoidStaleNodes Whether or not to avoid choosing stale nodes.               
   * @return Return true if <i>node</i> has enough space, 
   *         does not have too much load, 
   *         and the rack does not have too many nodes.
   */
  private boolean isGoodTarget(DatanodeDescriptor node,
                               long blockSize, int maxTargetPerLoc,
                               boolean considerLoad,
                               List<DatanodeDescriptor> results,
                               boolean avoidStaleNodes) {
    Log logr = FSNamesystem.LOG;
    // check if the node is (being) decommissed
    if (node.isDecommissionInProgress() || node.isDecommissioned()) {
      logr.debug("Node "+NodeBase.getPath(node)+
                " is not chosen because the node is (being) decommissioned");
      return false;
    }

    if (avoidStaleNodes) {
      if (node.isStale(this.staleInterval)) {
        logr.debug("Node "+NodeBase.getPath(node)+
            " is not chosen because the node is (being) stale");
        return false;
      }
    }
    
    long remaining = node.getRemaining() - 
                     (node.getBlocksScheduled() * blockSize); 
    // check the remaining capacity of the target machine
    if (blockSize* FSConstants.MIN_BLOCKS_FOR_WRITE>remaining) {
      logr.debug("Node "+NodeBase.getPath(node)+
                " is not chosen because the node does not have enough space");
      return false;
    }
      
    // check the communication traffic of the target machine
    if (considerLoad) {
      double avgLoad = 0;
      int size = clusterMap.getNumOfLeaves();
      if (size != 0 && stats != null) {
        avgLoad = (double)stats.getTotalLoad()/size;
      }
      if (node.getXceiverCount() > (2.0 * avgLoad)) {
        logr.debug("Node "+NodeBase.getPath(node)+
                  " is not chosen because the node is too busy");
        return false;
      }
    }
      
    // check if the target rack has chosen too many nodes
    String rackname = getRack(node);
    int counter=1;
    for(Iterator<DatanodeDescriptor> iter = results.iterator();
        iter.hasNext();) {
      Node result = iter.next();
      if (rackname.equals(result.getNetworkLocation())) {
        counter++;
      }
    }
    if (counter>maxTargetPerLoc) {
      logr.debug("Node "+NodeBase.getPath(node)+
                " is not chosen because the rack has too many chosen nodes");
      return false;
    }
    return true;
  }
    
  /* Return a pipeline of nodes.
   * The pipeline is formed finding a shortest path that 
   * starts from the writer and traverses all <i>nodes</i>
   * This is basically a traveling salesman problem.
   */
  private DatanodeDescriptor[] getPipeline(
                                           DatanodeDescriptor writer,
                                           DatanodeDescriptor[] nodes) {
    if (nodes.length==0) return nodes;
      
    synchronized(clusterMap) {
      int index=0;
      if (writer == null || !clusterMap.contains(writer)) {
        writer = nodes[0];
      }
      for(;index<nodes.length; index++) {
        DatanodeDescriptor shortestNode = nodes[index];
        int shortestDistance = clusterMap.getDistance(writer, shortestNode);
        int shortestIndex = index;
        for(int i=index+1; i<nodes.length; i++) {
          DatanodeDescriptor currentNode = nodes[i];
          int currentDistance = clusterMap.getDistance(writer, currentNode);
          if (shortestDistance>currentDistance) {
            shortestDistance = currentDistance;
            shortestNode = currentNode;
            shortestIndex = i;
          }
        }
        //switch position index & shortestIndex
        if (index != shortestIndex) {
          nodes[shortestIndex] = nodes[index];
          nodes[index] = shortestNode;
        }
        writer = shortestNode;
      }
    }
    return nodes;
  }

  /**
   * Adjust rackmap, priSet, and remains after choosing replica to delete.
   */
  protected void adjustSetsWithChosenReplica(
      Map<String, List<DatanodeDescriptor>> rackMap,
      List<DatanodeDescriptor> priSet,
      List<DatanodeDescriptor> remains, DatanodeInfo cur) {
    // adjust rackmap, priSet, and remains
    String rack = getLocalityGroupForSplit(cur);
    List<DatanodeDescriptor> datanodes = rackMap.get(rack);
    datanodes.remove(cur);
    if(datanodes.isEmpty()) {
      rackMap.remove(rack);
    }
    if( priSet.remove(cur) ) {
      if (datanodes.size() == 1) {
        priSet.remove(datanodes.get(0));
        remains.add(datanodes.get(0));
      }
    } else {
      remains.remove(cur);
    }
  }

  /**
   * Get specific level of locality group from a data node for judging 
   * over-replicated. 
   * Default to be rack, can be overridden in sub class with other locality 
   * group for other split policy.
   */
  protected String getLocalityGroupForSplit(DatanodeInfo cur) {
    return getRack(cur);
  }

  protected String getRack(DatanodeInfo cur) {
    return cur.getNetworkLocation();
  }
  
  /**
   * Split data nodes into two sets, one set includes nodes on rack with
   * more than one  replica, the other set contains the remaining nodes.
   * 
   * @param dataNodes
   * @param rackMap a map from rack to datanodes
   * @param priSet contains nodes on rack with more than one replica
   * @param remains remains contains the remaining nodes
   */
  protected void splitNodesWithLocalityGroup(
      Collection<DatanodeDescriptor> dataNodes,
      Map<String, List<DatanodeDescriptor>> rackMap,
      List<DatanodeDescriptor> priSet,
      List<DatanodeDescriptor> remains) {
    for (Iterator<DatanodeDescriptor> iter = dataNodes.iterator();
        iter.hasNext();) {
      DatanodeDescriptor node = iter.next();
      String rackName = getLocalityGroupForSplit(node);
      List<DatanodeDescriptor> datanodeList = rackMap.get(rackName);
      if(datanodeList==null) {
        datanodeList = new ArrayList<DatanodeDescriptor>();
      }
      datanodeList.add(node);
      rackMap.put(rackName, datanodeList);
    }
    
    // split nodes into two sets
    for(List<DatanodeDescriptor> datanodeList : rackMap.values()) {
      if (datanodeList.size() == 1 ) {
        // remains contains the remaining nodes
        remains.add(datanodeList.get(0));
      } else {
        // priSet contains nodes on rack with more than one replica
        priSet.addAll(datanodeList);
      }
    }
  }
  
  /** {@inheritDoc} */
  public int verifyBlockPlacement(String srcPath,
                                  LocatedBlock lBlk,
                                  int minRacks) {
    DatanodeInfo[] locs = lBlk.getLocations();
    if (locs == null)
      locs = new DatanodeInfo[0];
    int numRacks = clusterMap.getNumOfRacks();
    if(numRacks <= 1) // only one rack
      return 0;
    minRacks = Math.min(minRacks, numRacks);
    // 1. Check that all locations are different.
    // 2. Count locations on different racks.
    Set<String> racks = new TreeSet<String>();
    for (DatanodeInfo dn : locs)
      racks.add(dn.getNetworkLocation());
    return minRacks - racks.size();
  }

  /** {@inheritDoc} */
  public DatanodeDescriptor chooseReplicaToDelete(FSInodeInfo inode,
                                                 Block block,
                                                 short replicationFactor,
                                                 Collection<DatanodeDescriptor> first, 
                                                 Collection<DatanodeDescriptor> second) {
    long minSpace = Long.MAX_VALUE;
    DatanodeDescriptor cur = null;

    // pick replica from the priSet. If priSet is empty, then pick replicas
    // from remains set.
    Iterator<DatanodeDescriptor> iter = pickupReplicaSet(first, second);

    // pick node with least free space
    while (iter.hasNext() ) {
      DatanodeDescriptor node = iter.next();
      long free = node.getRemaining();
      if (minSpace > free) {
        minSpace = free;
        cur = node;
      }
    }
    return cur;
  }
  
  /**
   * Pick up replica node set for deleting replica as over-replicated. 
   * First set contains replica nodes on rack with more than one
   * replica while second set contains remaining replica nodes.
   * So pick up first set if not empty. If first is empty, then pick second.
   */
  protected Iterator<DatanodeDescriptor> pickupReplicaSet(
      Collection<DatanodeDescriptor> priSet,
      Collection<DatanodeDescriptor> remains) {
    Iterator<DatanodeDescriptor> iter = 
        priSet.isEmpty() ? remains.iterator() : priSet.iterator();
    return iter;
  }
}
