/**
 * Licensed to Ravel, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Ravel, Inc. licenses this file
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

package org.goldenorb.zookeeper;

import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.goldenorb.conf.OrbConfiguration;

public class OrbFastBarrier implements Barrier {
  
  private OrbConfiguration orbConf;
  private String barrierName;
  int numOfMembers;
  private String member;
  private ZooKeeper zk;
  private boolean active;
  
  /**
   * Constructs an OrbFastBarrier object. Which implements an O(n) version of enter
   * 
   * @param orbConf
   *          - OrbConfiguration
   * @param barrierName
   *          - The barrier's name
   * @param numOfMembers
   *          - The total number of expected members to join under the barrier node
   * @param member
   *          - A member node's name
   * @param zk
   *          - ZooKeeper object
   */
  public OrbFastBarrier(OrbConfiguration orbConf,
                    String barrierName,
                    int numOfMembers,
                    String member,
                    ZooKeeper zk) {
    this.orbConf = orbConf;
    this.barrierName = barrierName;
    this.numOfMembers = numOfMembers;
    this.member = member;
    this.zk = zk;
    this.active = true;
  }
  
  
  /**
   * This method creates a new member node under the barrier node if it does not already exist. It currently
   * is implemented with an O(n^2) algorithm where all members periodically check if the others have joined.
   * 
   * @exception InterruptedException
   *              throws OrbZKFailure
   * @exception KeeperException
   *              throws OrbZKFailure
   */
  @Override
  public void enter() throws OrbZKFailure {
 // general path looks like: "/barrierName/member"
    String barrierPath = "/" + barrierName;
    String memberPath = barrierPath + "/" + member;
    
    /*  If this barrier is the first to enter() it will create the barrier node and
     *  firstToEnter will be the path of the barrier node.  Otherwise firstToEnter
     *  will equal null.
     */
    String firstToEnter = ZookeeperUtils.tryToCreateNode(zk, barrierPath, CreateMode.PERSISTENT);
    ZookeeperUtils.tryToCreateNode(zk, memberPath, CreateMode.EPHEMERAL);
    
    if (firstToEnter != null) { // becomes the counter for this barrier
      try {
        BarrierWatcher bw = new BarrierWatcher(this);
        List<String> memberList = zk.getChildren(barrierPath, bw);
        synchronized(this) {
          while (memberList.size() < numOfMembers) {
            //synchronized(this) {
              this.wait(1000);
              memberList = zk.getChildren(barrierPath, bw);
            }
          }
        // Everyone has joined, give the All Clear to move forward
        ZookeeperUtils.tryToCreateNode(zk, barrierPath+"/AllClear", CreateMode.EPHEMERAL);
        // delete its node on they way out
        ZookeeperUtils.deleteNodeIfEmpty(zk, memberPath);
      } catch (KeeperException e) {
        throw new OrbZKFailure(e);
      } catch (InterruptedException e) {
        throw new OrbZKFailure(e);
      }
    } else { // not first to enter, therefore just watches for the AllClear node
      try {
        BarrierWatcher bw = new BarrierWatcher(this);
        while(zk.exists(barrierPath + "/AllClear", bw) == null) {
          synchronized (this) {
            this.wait(1000);
          }
        }
        // delete its node on they way out
        ZookeeperUtils.deleteNodeIfEmpty(zk, memberPath);
      } catch (KeeperException e) {
        throw new OrbZKFailure(e);
      } catch (InterruptedException e) {
        throw new OrbZKFailure(e);
      }
    }
  }
    
  public void makeInactive() {
    this.active = false;
  }
  
  @Override
  public void setOrbConf(OrbConfiguration orbConf) {
    this.orbConf = orbConf;
  }
  
  @Override
  public OrbConfiguration getOrbConf() {
    return orbConf;
  }
  
  /**
   * This class implements a Watcher for usage in the barrier mechanism for ZooKeeper.
   * 
   * @author long
   */
  class BarrierWatcher implements Watcher {
    OrbFastBarrier ofb;
    
    /**
     * This constructs a BarrierWatcher object given a configured OrbFastBarrier object.
     * 
     * @param orbFastBarrier
     */
    public BarrierWatcher(OrbFastBarrier orbFastBarrier) {
      this.ofb = orbFastBarrier;
    }
    
    /**
     * This method processes notifications triggered by Watchers.
     */
    @Override
    public synchronized void process(WatchedEvent event) {
      synchronized (ofb) {
        if (OrbFastBarrier.this.active) {
          ofb.notify();
        }
      }
    }
    
  }

  
}
