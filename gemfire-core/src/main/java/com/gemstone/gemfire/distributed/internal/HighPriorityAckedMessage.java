/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.gemstone.gemfire.distributed.internal;

import java.util.concurrent.ThreadPoolExecutor;
import com.gemstone.gemfire.*;
import com.gemstone.gemfire.internal.*;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;

import java.io.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A message that is sent to a given collection of managers and then
 * awaits replies.  It is used by some tests to flush the unordered communication
 * channels after no-ack tests.<p>
 * On the receiving end, the message will wait for the high priority queue
 * to drain before sending a reply.  This guarantees that all high priority
 * messages have been received and applied to the cache.  Their reply messages
 * may not necessarily have been sent back or processed (if they have any).
 * 
 * @author bruce
 * @since 5.1
 */
public final class HighPriorityAckedMessage extends HighPriorityDistributionMessage implements MessageWithReply {
  /** The is of the distribution manager that sent the message */
  private InternalDistributedMember id;
  private int processorId;
  private operationType op;
  
  static enum operationType {
    DRAIN_POOL, DUMP_STACK
  };

  transient DistributionManager originDm;
  transient private ReplyProcessor21 rp;
  private boolean useNative;
  
  public HighPriorityAckedMessage() {
    super();
    InternalDistributedSystem ds = InternalDistributedSystem.getAnyInstance();
    this.originDm = (DistributionManager)ds.getDistributionManager();
    this.id = this.originDm.getDistributionManagerId();
  }
  
  /**
   * Request stack dumps.  This does not wait for responses.  If useNative is
   * true we attempt to use OSProcess native code and null is returned.
   * Otherwise we use a thread mx bean to generate the traces.  If returnStacks
   * is true the stacks are not logged but are returned in a map in gzipped
   * form.
   */
  public Map<InternalDistributedMember, byte[]> dumpStacks(Set recipients, @SuppressWarnings("hiding") boolean useNative, boolean returnStacks) {
    this.op = operationType.DUMP_STACK;
    this.useNative = useNative;
    Set recips = new HashSet(recipients);
    DistributedMember me = originDm.getDistributionManagerId();
    if (recips.contains(me)) {
      recips.remove(me);
    }
    CollectingReplyProcessor<byte[]> cp = null;
    if (returnStacks) {
      cp = new CollectingReplyProcessor<byte[]>(originDm, recips);
      this.processorId = cp.getProcessorId();
    }
    originDm.putOutgoing(this);
    if (cp != null) {
      try {
        cp.waitForReplies();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return cp.getResults();
    }
    return null;
  }
  
  /**
   * send the message and wait for replies
   * @param recipients the destination manager ids
   * @param multicast whether to use multicast or unicast
   * @throws InterruptedException if the operation is interrupted (as by shutdown)
   * @throws ReplyException if an exception was sent back by another manager
   */
  public void send(Set recipients, boolean multicast)
    throws InterruptedException, ReplyException
  {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    this.op = operationType.DRAIN_POOL;
    Set recips = new HashSet(recipients);
    DistributedMember me = originDm.getDistributionManagerId();
    if (recips.contains(me)) {
      recips.remove(me);
    }
    rp = new ReplyProcessor21(originDm, recips);
    processorId = rp.getProcessorId();
    setRecipients(recips);
    setMulticast(multicast);
    originDm.putOutgoing(this);
    
    rp.waitForReplies();
  }
    

  /**
   * Sets the id of the distribution manager that is shutting down
   */
  void setDistributionManagerId(InternalDistributedMember id) {
    this.id = id;
  }
  
  /** set the reply processor id that's used to wait for acknowledgements */
  public void setProcessorId(int pid) {
    processorId = pid;
  }
  
  /** return the reply processor id that's used to wait for acknowledgements */
  @Override  
  public int getProcessorId() {
    return processorId;
  }

  /**
   * Adds the distribution manager that is started up to the current
   * DM's list of members.
   *
   * This method is invoked on the receiver side
   */
  @Override  
  protected void process(DistributionManager dm) {
    switch (this.op) {
    case DRAIN_POOL:
      Assert.assertTrue(this.id != null);
      // wait 10 seconds for the high priority queue to drain
      long endTime = System.currentTimeMillis() + 10000;
      ThreadPoolExecutor pool = (ThreadPoolExecutor)dm.getHighPriorityThreadPool();
      while (pool.getActiveCount() > 1 && System.currentTimeMillis() < endTime) {
        boolean interrupted = Thread.interrupted();
        try { 
          Thread.sleep(500); }
        catch (InterruptedException ie) {
          interrupted = true;
          dm.getCancelCriterion().checkCancelInProgress(ie);
          // if interrupted, we must be shutting down
          return;
        }
        finally {
          if (interrupted) Thread.currentThread().interrupt();
        }
      }
      if (pool.getActiveCount() > 1) {
        
        dm.getLoggerI18n().warning(
            LocalizedStrings.HighPriorityAckedMessage_0_THERE_ARE_STILL_1_OTHER_THREADS_ACTIVE_IN_THE_HIGH_PRIORITY_THREAD_POOL,
            new Object[] {this, Integer.valueOf(pool.getActiveCount()-1)});
      }
      ReplyMessage.send(getSender(), processorId, null, dm, null);
      break;
    case DUMP_STACK:
      if (this.processorId > 0) {
//        dm.getLoggerI18n().dumpConfig(sb);
        try {
          byte[] zippedStacks = OSProcess.zipStacks();
          ReplyMessage.send(getSender(), processorId, zippedStacks, dm, null);
        } catch (IOException e) {
          ReplyMessage.send(getSender(), processorId, new ReplyException(e),
              dm, null);
        }
      } else {
        OSProcess.printStacks(0, dm.getLoggerI18n().convertToLogWriter(), this.useNative);
      }
    }
  }

  public int getDSFID() {
    return HIGH_PRIORITY_ACKED_MESSAGE;
  }

  @Override  
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    out.writeInt(processorId);
    out.writeInt(this.op.ordinal());
    out.writeBoolean(this.useNative);
    DataSerializer.writeObject(this.id, out);
  }

  @Override  
  public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {

    super.fromData(in);
    processorId = in.readInt();
    this.op = operationType.values()[in.readInt()];
    this.useNative = in.readBoolean();
    this.id = (InternalDistributedMember) DataSerializer.readObject(in);
  }

  @Override  
  public String toString() {
    return "<HighPriorityAckedMessage from=" + this.id + ";processorId=" + this.processorId + ">";
  }

}
