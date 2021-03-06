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

package com.gemstone.gemfire.internal.cache;

/* enumerate each imported class because conflict with dl.u.c.TimeoutException */
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.NotSerializableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheEvent;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.CacheLoader;
import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.CacheWriter;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.LoaderHelper;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.RegionEvent;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.HighPriorityDistributionMessage;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.PooledDistributionMessage;
import com.gemstone.gemfire.distributed.internal.ProcessorKeeper21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.StoredObject;


/**
 * Implementation for distributed search, load and write operations in the
 * GemFire system. Provides an API for doing netSearch, netLoad, netSearchAndLoad and
 * netWrite. The class uses the DistributionAdvisor to route requests to the
 * appropriate members. It also uses the DistributionAdvisor to get region scope
 * and applies rules based on the scope. It makes uses of intelligent acceptors
 * that allow netSearch to happen as a one phase operation at all times.netLoad happens
 * as a one phase operation in all cases except where the scope is GLOBAL
 * At the receiving end, the request is converted into an appropriate message
 * whose process method responds to the request.
 *
 * @author Sudhir Menon
 */

public class SearchLoadAndWriteProcessor implements MembershipListener {

  public static final int SMALL_BLOB_SIZE =
     Integer.getInteger("DistributionManager.OptimizedUpdateByteLimit", 2000).intValue();

  static final long RETRY_TIME = Long.getLong("gemfire.search-retry-interval", 2000).longValue();


  private InternalDistributedMember selectedNode;
  private boolean selectedNodeDead = false;
  private int timeout;
  private boolean netSearchDone = false;
  private boolean netLoadDone = false;
  private long lastModified =0;
  protected int processorId;
  private Object aCallbackArgument;
  private String regionName;
  private Object key;
  protected LocalRegion region;
  private Object result = null;
  private boolean isSerialized = false; // is result serialized?
  private CacheDistributionAdvisor advisor = null;
  protected Exception remoteException = null;
  public DM distributionManager = null;
  private volatile boolean requestInProgress = false;
  private boolean remoteGetInProgress = false;
  private volatile boolean authorative = false;
  /** remoteLoadInProgress is volatile to make sure response threads see the current value */
  private volatile boolean remoteLoadInProgress = false;
  private static final ProcessorKeeper21 processorKeeper = new ProcessorKeeper21(false);
  //private static Set availableAcceptHelperSet = new HashSet();
  /** The members that haven't replied yet */
  // changed pendingResponders to synchronized Set to fix bug 38972
  private final Set pendingResponders = Collections.synchronizedSet(new HashSet());
  private List responseQueue = null;
  private boolean netWriteSucceeded = false;
  private int remainingTimeout = 0;
  private long startTimeSnapShot = 0;
  private long endTimeSnapShot = 0;

  private boolean netSearch = false;
  private boolean netLoad = false;
  private boolean attemptedLocalLoad = false; // added for bug 39738 
  private boolean localLoad = false;
  private boolean localWrite = false;
  private boolean netWrite = false;

  private final Object  membersLock = new Object();

  private Lock lock = null; // if non-null, then needs to be unlocked in release

  static final int NETSEARCH = 0;
  static final int NETLOAD = 1;
  static final int NETWRITE = 2;

  static final int BEFORECREATE = 0;
  static final int BEFOREDESTROY = 1;
  static final int BEFOREUPDATE = 2;
  static final int BEFOREREGIONDESTROY = 3;
  static final int BEFOREREGIONCLEAR = 4;
  

  /************** Public Methods ************************/

  Object doNetSearch()
  throws TimeoutException {

    resetResults();
    RegionAttributes attrs = region.getAttributes();
    this.requestInProgress=true;
    Scope scope = attrs.getScope();
    Assert.assertTrue(scope != Scope.LOCAL);
    netSearchForBlob();
    this.requestInProgress=false;
    return this.result;
  }

  void doSearchAndLoad(EntryEventImpl event, TXStateInterface txState,
      Object localValue) throws CacheLoaderException, TimeoutException {

    this.requestInProgress = true;
    RegionAttributes attrs = region.getAttributes();
    Scope scope = attrs.getScope();
    CacheLoader loader = region.basicGetLoader();
    if (scope.isLocal()) {
      Object obj = doLocalLoad(loader, false);
      event.setNewValue(obj);
    }
    else {
      searchAndLoad(event, txState, localValue);
    }
    this.requestInProgress = false;
    if (this.netSearch) {
      if (event.getOperation().isCreate()) {
        event.setOperation(Operation.SEARCH_CREATE);
      } else {
        event.setOperation(Operation.SEARCH_UPDATE);
      }
    } else if (this.netLoad) {
      if (event.getOperation().isCreate()) {
        event.setOperation(Operation.NET_LOAD_CREATE);
      } else {
        event.setOperation(Operation.NET_LOAD_UPDATE);
      }
    } else if (this.localLoad) {
      if (event.getOperation().isCreate()) {
        event.setOperation(Operation.LOCAL_LOAD_CREATE);
      } else {
        event.setOperation(Operation.LOCAL_LOAD_UPDATE);
      }
    }
  }

  /** return true if a CacheWriter was actually invoked */
  boolean doNetWrite(CacheEvent event, Set netWriteRecipients,
                     CacheWriter localWriter, int paction)
  throws CacheWriterException, TimeoutException {

    int action = paction;
    this.requestInProgress = true;
    Scope scope = this.region.scope;
    if (localWriter != null) {
      doLocalWrite(localWriter, event, action);
      this.requestInProgress = false;
      return true;
    }
    if (scope == Scope.LOCAL && (region.getPartitionAttributes() == null)) {
      return false;
    }
    CacheEvent listenerEvent = getEventForListener(event);
    if (action == BEFOREUPDATE && listenerEvent.getOperation().isCreate()) {
      action = BEFORECREATE;
    }
    boolean cacheWrote = netWrite(getEventForListener(event), action, netWriteRecipients);
    this.requestInProgress = false;
    return cacheWrote;

  }

  public void memberJoined(InternalDistributedMember id) {
    // Ignore - if they just joined, they don't have what we want
  }

  public void memberSuspect(InternalDistributedMember id,
      InternalDistributedMember whoSuspected) {
  }
  
  public void quorumLost(Set<InternalDistributedMember> failures, List<InternalDistributedMember> remaining) {
  }

  public void memberDeparted(final InternalDistributedMember id, final boolean crashed) {

    synchronized (membersLock) {
      pendingResponders.remove(id);
    }
    synchronized (this) {
      if (id.equals(selectedNode) && (this.requestInProgress) && (this.remoteGetInProgress)) {
        selectedNode = null;
        selectedNodeDead = true;
        computeRemainingTimeout();
        logFine(this + ": processing loss of member ", id);
        this.lastNotifySpot = 3;
        notifyAll(); // signal the waiter; we are not done; but we need the waiter to call sendNetSearchRequest
      }
      if(responseQueue != null) responseQueue.remove(id);
      checkIfDone();
    }
//    this.advisor.getLogWriter().info(
//        "DEBUG: netsearch id " + this.processorId + " received memberDeparted event for " + id);
  }

  int getProcessorId() {
    return this.processorId;
  }

  synchronized void checkIfDone() {
    if (!this.remoteGetInProgress
        && this.pendingResponders.isEmpty()) {
      // Synchronize in case a different response/reply is still
      // in progress, and it's the one thats got the goods (bug 28741)
      signalDone();
    }


  }

  synchronized void signalDone() {
    this.requestInProgress = false;
    this.lastNotifySpot = 1;
    notifyAll();
  }
  synchronized void signalTimedOut() {
    this.lastNotifySpot = 2;
    notifyAll();
  }
  private volatile int lastNotifySpot = 0;

  private VersionTag versionTag;

  synchronized void nackResponseComplete() {
    /*if (this.requestInProgress && currentHelper != null &&
        optimizer != null && optimizer.allRepliesReceived(currentHelper.nackServerChannel)) {
       this.requestInProgress = false;
        signalDone();
    }*/

  }

  static ProcessorKeeper21 getProcessorKeeper() {
    return processorKeeper;
  }

  boolean isNetSearch() {
    return netSearch;
  }

  boolean isNetLoad() {
    return netLoad;
  }

  boolean isLocalLoad() {
    return localLoad;
  }

  boolean isNetWrite() {
    return netWrite;
  }

  boolean isLocalWrite() {
    return localWrite;
  }

  long getLastModified() {
    return lastModified;
  }

  boolean resultIsSerialized() {
    return this.isSerialized;
  }

  static SearchLoadAndWriteProcessor getProcessor() {
    SearchLoadAndWriteProcessor processor = new SearchLoadAndWriteProcessor();
    processor.processorId = getProcessorKeeper().put(processor);
    return processor;
  }

  void release() {
    try {
      if (this.lock != null) {
        try {
          this.lock.unlock();
        }
        catch (CancelException ignore) {
        }
        this.lock = null;
      }
    }
    finally {
      try {
        if (this.advisor != null) {
          this.advisor.removeMembershipListener(this);
        }
      }
      catch (IllegalArgumentException e) {
      }
      finally {
        getProcessorKeeper().remove(this.processorId);
      }
    }
  }

  void remove() {
    getProcessorKeeper().remove( this.processorId);

  }



  void initialize(LocalRegion theRegion, Object theKey, Object theCallbackArg) {

    this.region = theRegion;
    this.regionName = theRegion.getFullPath();
    this.key = theKey;
    this.aCallbackArgument = theCallbackArg;
    RegionAttributes attrs = theRegion.getAttributes();
    Scope scope = attrs.getScope();
    if (scope.isDistributed()) {
      this.advisor = ((CacheDistributionAdvisee)this.region).getCacheDistributionAdvisor();
      this.distributionManager= ((CacheDistributionAdvisee)theRegion).getDistributionManager();
      this.timeout = getSearchTimeout();
      this.advisor.addMembershipListener(this);

    }
  }

  void setKey(Object key) {
    this.key = key;
  }

  /************** Protected Methods ********************/
  protected void setSelectedNode(InternalDistributedMember selectedNode) {
    this.selectedNode = selectedNode;
    this.selectedNodeDead = false;
  }

  protected int getTimeout() {
    return this.timeout;
  }

  protected Object getKey() {
    return this.key;
  }

  /************** Package Methods **********************/

  /************** Private Methods **********************/
  /**
   * Even though SearchLoadAndWriteProcessor may be in invoked in the context
   * of a local region, most of the services it provides are relevant to
   * distribution only. The 3 services it provides are netSearch, netLoad,
   * netWrite
   *
   */
  private SearchLoadAndWriteProcessor() {
    resetResults();
    this.pendingResponders.clear();
    this.attemptedLocalLoad = false;
    this.netSearchDone = false;
    this.isSerialized = false;
    this.result = null;
    this.key = null;
    this.requestInProgress = false;
    this.netWriteSucceeded = false;
    this.remoteGetInProgress = false;
    this.responseQueue = null;
  }


  /**
   * If we have a local cache loader and the region is not global, then invoke the loader
   * If the region is local, or the result is non-null, then return whatever the loader returned
   * do a netSearch amongst selected peers
   * if netSearch returns a blob, deserialize the blob and return that as the result
   * netSearch failed, so all we can do at this point is do a load
   * return result from load
   */
  private void searchAndLoad(EntryEventImpl event, final TXStateInterface tx,
      Object localValue) throws CacheLoaderException, TimeoutException {

    RegionAttributes attrs = region.getAttributes();
    Scope scope = attrs.getScope();
    DataPolicy dataPolicy = attrs.getDataPolicy();

    // if mirrored then we can optimize by skipping netsearch in some cases,
    // and if also skip netSearch if we find an INVALID token since we
    // know it was distributed. (Otherwise it would be a LOCAL_INVALID token)
    {
      if(localValue == Token.INVALID || dataPolicy.withReplication()) { 
//        logFine("SearchLoadAndWriteProcessor.searchAndLoad, skipping netload", null);
        // Don't bother net searching - if anyone had it, it would already be here
        load(event);
        return;
      }
    }

    final TXState txState;
    if (tx != null && (txState = tx.getLocalTXState()) != null) {
      final LocalRegion dataRegion = this.region.getDataRegionForRead(event,
          Operation.GET);
      final TXRegionState txr = txState.readRegion(dataRegion);
      if (txr != null) {
        txr.lock();
        boolean locked = true;
        try {
          final TXEntryState txEntry = txState.txReadEntry(event, this.region,
              dataRegion, txr, Operation.GET);
          if (txEntry != null) {
            if (txEntry.noValueInSystem()) {
              txr.unlock();
              locked = false;
              // If the tx view has it invalid or destroyed everywhere
              // then don't do a netsearch. We want to see the
              // transactional view.
              load(event);
              return;
            }
          }
        } finally {
          if (locked) {
            txr.unlock();
          }
        }
      }
    }

    Object obj = null;
    if (!scope.isGlobal()) {
      // copy into local var to prevent race condition
      CacheLoader loader = ((AbstractRegion)region).basicGetLoader();
      if (loader != null) {
        obj = doLocalLoad(loader, true);
        Assert.assertTrue(obj != Token.INVALID &&
                          obj != Token.LOCAL_INVALID);
        event.setNewValue(obj);
        this.isSerialized = false;
        this.result = obj;
        return;
      }
      if (scope.isLocal()) {
        return;
      }
    }
    netSearchForBlob();
    if (this.result != null) {
      Assert.assertTrue(this.result != Token.INVALID &&
                          this.result != Token.LOCAL_INVALID);
      if (this.isSerialized) {
        event.setSerializedNewValue((byte[])this.result);
      } else {
        event.setNewValue(this.result);
      }
      event.setVersionTag(this.versionTag);
      return;
    }

    load(event);
  }

  /** perform a net-search, setting this.result to the object found in the search */
  private void netSearchForBlob() throws TimeoutException {
    if (this.netSearchDone) return;
    this.netSearchDone = true;
    CachePerfStats stats = region.getCachePerfStats();
    long start = 0;
    Set sendSet = null;
    
    this.result = null;
    RegionAttributes attrs = region.getAttributes();
//    Object aCallbackArgument = null;
    this.requestInProgress = true;
    this.selectedNodeDead = false;
    initRemainingTimeout();
    start  = stats.startNetsearch();
    try {
      List<InternalDistributedMember> replicates = new ArrayList(advisor.adviseInitializedReplicates());
      if (replicates.size() > 1) {
        Collections.shuffle(replicates);
      }
      for(InternalDistributedMember replicate : replicates) {
        synchronized (this.pendingResponders) {
          this.pendingResponders.clear();
        }
        this.requestInProgress = true;
        this.remoteGetInProgress = true;
        synchronized(this) {
          setSelectedNode(replicate);
          this.lastNotifySpot = 0;
        }
        sendValueRequest(replicate, this.distributionManager.getLoggerI18n());
        waitForObject2(this.remainingTimeout);
        if(this.authorative) {
          if(this.result != null) {
            this.netSearch = true;
          }
          return;
        } else {
          //clear anything that might have been set by our query.
          this.selectedNode = null;
          this.selectedNodeDead = false;
          this.lastNotifySpot = 0;
          this.result = null;
        }
      }
      //    logFine("SearchLoadAndWriteProcessor.netSearchForBlob", null);
      synchronized (membersLock) {
        Set recipients = this.advisor.adviseNetSearch();
        if (recipients.isEmpty()) {
          return;
        }
        ArrayList list = new ArrayList(recipients);
        Collections.shuffle(list);
        sendSet = new HashSet(list);
        synchronized (this.pendingResponders) {
          this.pendingResponders.clear();
          this.pendingResponders.addAll(list);
        }
      }

      boolean useMulticast = region.getMulticastEnabled()
      && (region instanceof DistributedRegion)
      && ((DistributedRegion)region).getSystem().getConfig().getMcastPort() != 0;

      // moved outside the sync to fix bug 39458
      QueryMessage.sendMessage(this, this.regionName,this.key,useMulticast, sendSet, this.remainingTimeout,
          attrs.getEntryTimeToLive().getTimeout(),
          attrs.getEntryIdleTimeout().getTimeout());

      synchronized (this) {
        // moved this send back into sync to fix bug 37132
        //      QueryMessage.sendMessage(this, this.regionName,this.key,useMulticast, sendSet,this.remainingTimeout ,
        //                               attrs.getEntryTimeToLive().getTimeout(),
        //                               attrs.getEntryIdleTimeout().getTimeout());
        boolean done = false;
        do {
          //logFine("About to waitForObject2(" + this.remainingTimeout + "), startTimeSnapShot: " + this.startTimeSnapShot, this);
          waitForObject2(this.remainingTimeout);
          if (this.selectedNodeDead && remoteGetInProgress) {
            sendNetSearchRequest();
          }
          else
            done = true;
        } while (!done);

        if (this.result != null) {
          this.netSearch = true;
        }
        return;
      }
    } finally {
      stats.endNetsearch(start);
    }
  }

  private void load(EntryEventImpl event)
  throws CacheLoaderException, TimeoutException {
    Object obj = null;
    RegionAttributes attrs = this.region.getAttributes();
    Scope scope = attrs.getScope();
    CacheLoader loader = ((AbstractRegion)region).basicGetLoader();
    Assert.assertTrue(scope.isDistributed());

    if ((loader != null) && (!scope.isGlobal())) {
      obj = doLocalLoad(loader, false);
      event.setNewValue(obj);
      Assert.assertTrue(obj != Token.INVALID && obj != Token.LOCAL_INVALID);
      return;
    }

    if (scope.isGlobal()) {
      Assert.assertTrue(this.lock == null);
      Set loadCandidatesSet = advisor.adviseNetLoad();
      if ((loader == null) && (loadCandidatesSet.isEmpty())) {
        //no one has a data Loader. No point getting a lock
        return;
      }

      this.lock = region.getDistributedLock(this.key);
      boolean locked = false;
      try {
        final CancelCriterion stopper = region.getCancelCriterion();
        for (;;) {
          stopper.checkCancelInProgress(null);
          boolean interrupted = Thread.interrupted();
          try {
            locked = this.lock.tryLock(region.getCache().getLockTimeout(),
                TimeUnit.SECONDS);
            if (!locked) {
              throw new TimeoutException(LocalizedStrings.SearchLoadAndWriteProcessor_TIMED_OUT_LOCKING_0_BEFORE_LOAD.toLocalizedString(key));
            }
            break;
          }
          catch (InterruptedException e) {
            interrupted = true;
            region.getCancelCriterion().checkCancelInProgress(null);
            // continue;
          }
          finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        } // for
        if (loader == null) {
          this.localLoad = false;
          if (scope.isDistributed()) {
            this.isSerialized = false;
            obj = doNetLoad();
            Assert.assertTrue(obj != Token.INVALID && obj != Token.LOCAL_INVALID);
            if (this.isSerialized && obj != null) {
              event.setSerializedNewValue((byte[])obj);
            } else {
              event.setNewValue(obj);
            }
          }
        }
        else {
          obj = doLocalLoad(loader, false);
          Assert.assertTrue(obj != Token.INVALID && obj != Token.LOCAL_INVALID);
          event.setNewValue(obj);
        }
        return;
      }
      finally {
        // The lock will not actually be released until release is
        //   called on this processor
        if (!locked) this.lock = null;
      }
    }
    if (scope.isDistributed()) {
//      long start = System.currentTimeMillis();
      obj = doNetLoad();
      if (this.isSerialized  && obj != null) {
        event.setSerializedNewValue((byte[])obj);
      }
      else {
        Assert.assertTrue(obj != Token.INVALID && obj != Token.LOCAL_INVALID);
        event.setNewValue(obj);
      }
//      long end = System.currentTimeMillis();
    }
  }

  Object doNetLoad()
  throws CacheLoaderException, TimeoutException {
    if (this.netLoadDone) return null;
    this.netLoadDone = true;
    if (advisor != null) {
//      this.distributionManager.getLogger().fine("doNetLoad: advisor = " + advisor);
      Set loadCandidatesSet = advisor.adviseNetLoad();
      if (loadCandidatesSet.isEmpty()) {
//         LogWriter log = this.distributionManager.getLogger();
//         if (log.fineEnabled()) {
//           log.fine("empty set returned by advisor.adviseNetLoad in doNetLoad");
//         }
        return null;
      }
      CachePerfStats stats = region.getCachePerfStats();
      long start = stats.startNetload();
      ArrayList list = new ArrayList(loadCandidatesSet);
      Collections.shuffle(list);
      InternalDistributedMember[] loadCandidates = (InternalDistributedMember[])list.
      toArray(new InternalDistributedMember[list.size()]);
      initRemainingTimeout();

      RegionAttributes attrs = region.getAttributes();
      int index = 0;
      boolean stayInLoop = false; // never set to true!
      this.remoteLoadInProgress = true;
      try {
        do { // the only time this loop repeats is when continue is called
          InternalDistributedMember next = loadCandidates[index++];
          setSelectedNode(next);
          //log.info("SLWP: sending netload(" + key + ") to " + next);
          this.lastNotifySpot = 0;
          this.requestInProgress = true;
          if (this.remainingTimeout <= 0) { // @todo this looks wrong; why not a timeout exception?
            //log.info("SLWP: timed out (" + key + ")");
            break;
          }
          this.remoteException = null;
          NetLoadRequestMessage.sendMessage(this, this.regionName, this.key,
          this.aCallbackArgument, next,this.remainingTimeout,
          attrs.getEntryTimeToLive().getTimeout(),
          attrs.getEntryIdleTimeout().getTimeout());
          waitForObject2(this.remainingTimeout);
          if (this.remoteException == null) {
            //log.info("SLWP: result(" + key + ") is " + this.result);
            if (!this.requestInProgress) {
              // note even if result is null we are done; see bug 39738
              this.localLoad = false;
              if (this.result != null) {
                this.netLoad = true;
              }
              return this.result;
            }
            else {
              // Why does the following test for selectedNodeDead?
              // Seems like this will cause us to quit trying netLoad
              // even if we don't have a result yet and have not tried everyone.
              if ((this.selectedNodeDead) && (index < loadCandidates.length)) {
                continue;
              }
              // otherwise we are done
            }
          }
          else {
            Throwable cause;
            if (this.remoteException instanceof TryAgainException) {
              if (index < loadCandidates.length) {
                continue;
              } else {
                break;
              }
            }
            if (this.remoteException instanceof CacheLoaderException) {
              cause = this.remoteException.getCause();
            }
            else {
              cause = this.remoteException;
            }
            throw new CacheLoaderException(LocalizedStrings.SearchLoadAndWriteProcessor_WHILE_INVOKING_A_REMOTE_NETLOAD_0.toLocalizedString(cause), cause);
          }

        } while(stayInLoop);
      } finally {
        stats.endNetload(start);
      }

    }
    return null;
  }

  /**
   * This exception is just used in the class to tell the caller
   * that it should try again. InternalGemFireException used to be
   * used for this which seems dangerous.
   */
  private static class TryAgainException extends GemFireException {

    //////////////////////  Constructors  //////////////////////

    public TryAgainException() {
      super();
    }

    /**
     * Creates a new <code>TryAgainException</code>.
     */
    public TryAgainException(String message) {
      super(message);
    }
  }


  private Object doLocalLoad(CacheLoader loader, boolean netSearchAllowed)
  throws CacheLoaderException {
    Object obj = null;
//    logFine("SearchLoadAndWriteProcessor.doLocalLoad loader=", loader);
    if (loader != null && !this.attemptedLocalLoad) {
      this.attemptedLocalLoad = true;
      CachePerfStats stats = region.getCachePerfStats();
      LoaderHelper loaderHelper = this.region.loaderHelperFactory.createLoaderHelper(this.key, this.aCallbackArgument, netSearchAllowed, 
           true /*netLoadAllowed*/, this);
      long statStart = stats.startLoad();
      try {
        obj = loader.load(loaderHelper);
      }
      finally {
        stats.endLoad(statStart);
      }
      if (obj != null) {
        this.localLoad = true;
      }
    }
    return obj;
  }

  /**
   * Returns an event for listener notification.  The event's operation
   * may be altered to conform to the ConcurrentMap implementation specification
   * @param event the original event
   * @return the original event or a new event having a change in operation
   */
  private CacheEvent getEventForListener(CacheEvent event) {
    Operation op = event.getOperation();
    if (!op.isEntry()) {
      return event;
    } else {
      EntryEventImpl r = (EntryEventImpl)event;
      EntryEventImpl result = r;
      if (r.isSingleHop()) {
        // fix for bug #46130 - origin remote incorrect for one-hop operation in receiver
        result = new EntryEventImpl(r);
        result.setOriginRemote(true);
        // if this is from a non-replicate and it's not in a tx it should be seen as a Create
        // because that's what the sender would use in notifying listeners. bug #46955
        if (result.getOperation().isUpdate() && (result.getTransactionId() == null)) {
          result.makeCreate();
        }
      }
      if (op == Operation.REPLACE) {
        if (result == r) result = new EntryEventImpl(r);
        result.setOperation(Operation.UPDATE);
      } else if (op == Operation.PUT_IF_ABSENT) {
        if (result == r) result = new EntryEventImpl(r);
        result.setOperation(Operation.CREATE);
      } else if (op == Operation.REMOVE) {
        if (result == r) result = new EntryEventImpl(r);
        result.setOperation(Operation.DESTROY);
      }
      return result;
    }
  }

  private boolean doLocalWrite(CacheWriter writer,CacheEvent pevent,int paction)
  throws CacheWriterException {
    // Return if the inhibit all notifications flag is set
    if (pevent instanceof EntryEventImpl) {
      if (((EntryEventImpl)pevent).inhibitAllNotifications()){
        if (this.region.cache.getLoggerI18n().fineEnabled()) 
          this.region.cache.getLoggerI18n().fine("Notification inhibited for key " + pevent.toString());
        return false;
      }
    }
    CacheEvent event = getEventForListener(pevent);
    
    int action = paction;
    if (event.getOperation().isCreate() && action == BEFOREUPDATE) {
      action = BEFORECREATE;
    }
    try {
    switch(action) {
      case BEFORECREATE:
        writer.beforeCreate((EntryEvent)event);
        break;
      case BEFOREDESTROY:
        writer.beforeDestroy((EntryEvent)event);
        break;
      case BEFOREUPDATE:
        writer.beforeUpdate((EntryEvent)event);
        break;
      case BEFOREREGIONDESTROY:
        writer.beforeRegionDestroy((RegionEvent)event);
        break;
      case BEFOREREGIONCLEAR:
        writer.beforeRegionClear((RegionEvent)event);
        break;
      default:
        break;

    }
    } finally {
      if (event != pevent) {
        if (event instanceof EntryEventImpl) {
          ((EntryEventImpl) event).release();
        }
      }
    }
    this.localWrite = true;
    return true;

  }

  /** Return true if cache writer was invoked */
  private boolean netWrite(CacheEvent event, int action, Set writeCandidateSet)
  throws CacheWriterException, TimeoutException {

    // assert !writeCandidateSet.isEmpty();
    ArrayList list = new ArrayList(writeCandidateSet);
    Collections.shuffle(list);
    InternalDistributedMember[] writeCandidates = (InternalDistributedMember[])list.
      toArray(new InternalDistributedMember[list.size()]);
    initRemainingTimeout();
    int index =0;
    do {
      InternalDistributedMember next = writeCandidates[index++];
      Set set = new HashSet();
      set.add(next);
      this.netWriteSucceeded = false;
      this.requestInProgress = true;
      //logFine("DEBUG: sending NetWriteRequest to " + next, null);
      this.remoteException = null;
      NetWriteRequestMessage.sendMessage(this, this.regionName,this.remainingTimeout,
      event,set,action);
      if (this.remainingTimeout <= 0) { // @todo: should this throw a timeout exception?
        break;
      }
      waitForObject2(this.remainingTimeout);
      if (this.netWriteSucceeded) {
        this.netWrite = true;
        break;
      }
      if (this.remoteException != null) {
        Throwable cause;
        if (this.remoteException instanceof TryAgainException) {
          if (index < writeCandidates.length) {
            continue;
          } else {
            break;
          }
        }
        if (this.remoteException instanceof CacheWriterException &&
            this.remoteException.getCause() != null) {
          cause = this.remoteException.getCause();
        }
        else {
          cause = this.remoteException;
        }
        throw new CacheWriterException(LocalizedStrings.SearchLoadAndWriteProcessor_WHILE_INVOKING_A_REMOTE_NETWRITE_0.toLocalizedString(cause), cause);
      }
    } while(index < writeCandidates.length);

    return this.netWriteSucceeded;
  }


  /** process a QueryMessage netsearch response 
   * @param versionTag TODO*/
  protected synchronized void incomingResponse(Object obj,
                                               long lastModifiedTime,
                                               boolean isPresent,
                                               boolean serialized,
                                               final boolean requestorTimedOut,
                                               final InternalDistributedMember sender,
                                               DistributionManager dm, VersionTag versionTag) {
    // NOTE: keep this method efficient since it is optimized
    // by executing it in the p2p reader.
    // This is done with this line in DistributionMessage.java:
    // || c.equals(SearchLoadAndWriteProcessor.ResponseMessage.class)

    final LogWriterI18n log = dm.getLoggerI18n();

    // bug 35266 - don't pay attention to late breaking "get" responses
    if (this.remoteLoadInProgress) {
      if (log.fineEnabled()) {
        log.fine("Ignoring netsearch response from " + sender + " because we're now doing a netload");
      }
      return;
    }
    
    if (this.pendingResponders.isEmpty()) {
      return;
    }
    if (!this.pendingResponders.remove(sender)) {
      return;
    } else {
      if (log.fineEnabled()) {
        // only log the message if it's from a recipient we're waiting for.
        // it could have been multicast to all members, which would give us
        // one response per member
        String msg = " Processing response for processorId=" + this.processorId
          + ", isPresent is " + isPresent + ", sender is " + sender + " key is "
          + this.key + ", Value is " + serialized + ", version is " + versionTag;
        log.fine(msg);
      } // fineEnabled()
    }

    //Another thread got a response and that contained the value.
    // Ignore this response.
    if (this.result != null) {
      return;
    }

    if ( isPresent ) {
      if (obj != null) {
        Assert.assertTrue(obj != Token.INVALID && obj != Token.LOCAL_INVALID);
        synchronized(this) {
          this.result = obj;
          this.lastModified = lastModifiedTime;
          this.isSerialized = serialized;
          this.versionTag = versionTag;
          signalDone();
          return;
        }
      }
      else {
        if (!remoteGetInProgress) {
          // send a message to this responder asking for the value
          // do this on the waiting pool in case the send blocks
          try {
            dm.getWaitingThreadPool().execute(new Runnable() {
                public void run() {
                  sendValueRequest(sender, log);
                }
              });
            // need to do this here before releasing sync to fix bug 37132
            this.requestInProgress = true;
            this.remoteGetInProgress = true;
            setSelectedNode(sender);
            return; // sendValueRequest does the rest of the work
          }
          catch (RejectedExecutionException ex) {
            // just fall through since we must be shutting down.
          }
        }
        if (responseQueue == null) responseQueue = new LinkedList();
        if (log.fineEnabled()) {
          log.fine("Saving isPresent response, requestInProgress " + sender);
        }
        responseQueue.add(sender);
      }
    }
    if (requestorTimedOut) {
      signalTimedOut();
    }

    boolean endRequest = false;
    if (this.pendingResponders.isEmpty()
        && (!remoteGetInProgress)) {
      endRequest = true;
    }
    if (endRequest) {
      signalDone();
    }
  }

  protected synchronized void sendValueRequest(final InternalDistributedMember sender,
                                             final LogWriterI18n log) {
    // send a message to this responder asking for the value
    // do this on the waiting pool in case the send blocks
    // Always attempt to send the message to fix bug 37149
    RegionAttributes attrs = this.region.getAttributes();
    NetSearchRequestMessage.sendMessage(this, this.regionName, this.key,
                                        sender, this.remainingTimeout,
                                        attrs.getEntryTimeToLive().getTimeout(),
                                        attrs.getEntryIdleTimeout().getTimeout());
    // if it turns out that we can't send a message to this member then
    // our membership listener should save the day and schedule a send
    // to someone else or give up and report a failed search.
  }

  // @todo creation times need to be handled properly
  /**
   * This is the response from the accepted responder.
   * Grab the result and store it.Unlike 2.0 where the
   * the response was a 2 phase operation, here it is a
   * single phase operation.
   */
  protected void incomingNetLoadReply(Object obj, long lastModifiedTime,
      Object callbackArg, Exception e,
      boolean serialized, boolean requestorTimedOut) {
    synchronized (this) {
      if (requestorTimedOut) {
        signalTimedOut();
        return;
      }
      this.result = obj;
      this.lastModified = lastModifiedTime;
      this.remoteException = e;
      this.aCallbackArgument = callbackArg;
      computeRemainingTimeout();
      this.isSerialized = serialized;
      signalDone();
    }


  }
  @SuppressWarnings("hiding")
  protected synchronized void incomingNetSearchReply(byte[] value, long lastModifiedTime,
                                                   boolean serialized,
                                                   boolean requestorTimedOut,
                                                   boolean authorative,
                                                   VersionTag versionTag) {
    if (this.requestInProgress) {
      if (requestorTimedOut) {
        //Force a timeout exception.
        logFine("incomingNetSearchReply() - requestorTimedOut", this);
        signalTimedOut();
      }
      computeRemainingTimeout();
      if (value != null || authorative) {
        synchronized (this) {
          this.result = value;
          this.lastModified = lastModifiedTime;
          this.isSerialized = serialized;
          this.remoteGetInProgress = false;
          this.authorative = authorative;
          this.versionTag = versionTag;
          logFine("incomingNetSearchReply() - got obj", this);
          signalDone();
        }
      }
      else if(this.remainingTimeout <= 0) {
        this.remoteGetInProgress = false;
        logFine("incomingNetSearchReply() - null obj, no more time", this);
        signalDone(); // @todo: is this a bug? should call signalTimedOut?
      } 
      else {
        logFine("incomingNetSearchReply() - null obj, sendNetSearchRequest", this);
        sendNetSearchRequest();
      }
    } else {
      logFine("incomingNetSearchReply() - requestInProgress is false", this);
      // should we checkIfDone?
      // sure why not?
      checkIfDone();
    }
  }

  /**
   * Return the next responder on the responseQueue, or null if empty
   */
  private InternalDistributedMember nextAppropriateResponder() {
    if (responseQueue != null && responseQueue.size() > 0) {
      return (InternalDistributedMember)responseQueue.remove(0);
    } else {
      return null;
    }
  }

  private synchronized void sendNetSearchRequest() {
  InternalDistributedMember nextResponder = nextAppropriateResponder();
    if (nextResponder != null) {
      // Make a request to the next responder in the queue
      RegionAttributes attrs = this.region.getAttributes();
      setSelectedNode(nextResponder);
      this.requestInProgress = true;
      this.remoteGetInProgress = true;
      NetSearchRequestMessage.sendMessage(this, this.regionName, this.key,
                   nextResponder,this.remainingTimeout,
                   attrs.getEntryTimeToLive().getTimeout(),
                   attrs.getEntryIdleTimeout().getTimeout()
                   );

    }
    else {
      this.remoteGetInProgress = false;
      checkIfDone();

    }
  }
  /**
   * This is the response from the accepted responder.
   */
  protected void incomingNetWriteReply(boolean netWriteSuccessful,Exception e,
  boolean exe) {
    synchronized (this) {
      //logFine("In incomingReply, blob is ", blob);
      this.remoteException = e;
      this.netWriteSucceeded = netWriteSuccessful;
//      this.cacheWriterException = exe;
      computeRemainingTimeout();
      signalDone();
    }
  }

  private synchronized void initRemainingTimeout() {
    this.remainingTimeout = this.timeout * 1000;
    this.startTimeSnapShot = this.distributionManager.cacheTimeMillis();
  }
  
  private synchronized void computeRemainingTimeout() {
    if (this.startTimeSnapShot > 0) { // @todo this should be an assertion
      this.endTimeSnapShot = this.distributionManager.cacheTimeMillis();
      long delta = this.endTimeSnapShot - this.startTimeSnapShot;
      if (delta > 0) {
        this.remainingTimeout -= delta;
      }
      this.startTimeSnapShot = this.endTimeSnapShot;
    }
  }

  protected static void logFine(String msg, Object obj) {
    LogWriterI18n log = InternalDistributedSystem.getAnyInstance().getLogWriterI18n();
    if (log.fineEnabled()) {
      log.fine(msg + " " + (obj == null ? "" : obj.toString()));
    }
  }



  private synchronized void waitForObject2(final int timeoutMs)
  throws TimeoutException {
    if (this.requestInProgress) {
      try {
        final DM dm = this.region.cache.getDistributedSystem()
            .getDistributionManager();
        long waitTimeMs = timeoutMs;
        final long endTime = System.currentTimeMillis() + waitTimeMs;
        for (;;) {
          if (!this.requestInProgress) {
            return;
          }
          if (waitTimeMs <= 0) {
            throw new TimeoutException(LocalizedStrings.SearchLoadAndWriteProcessor_TIMED_OUT_WHILE_DOING_NETSEARCHNETLOADNETWRITE_PROCESSORID_0_KEY_IS_1.toLocalizedString(new Object[] {Integer.valueOf(this.processorId), this.key}));
          }
          
          boolean interrupted = Thread.interrupted();
          int lastNS = this.lastNotifySpot;
          try {
            {
              boolean done = (lastNS != 0);
              while (!done && waitTimeMs > 0) {
                this.region.getCancelCriterion().checkCancelInProgress(null);
                interrupted = Thread.interrupted() || interrupted;
                long wt = Math.min(RETRY_TIME, waitTimeMs);
//                this.advisor.getLogWriter().info(
//                    "DEBUG: netsearch id " + this.processorId
//                    + " in waitForObject2 waitTime=" + wt
//                    + "; pendingResponders="
//                    + new ArrayList(this.pendingResponders)
//                    + "; lastNS="
//                    + this.lastNotifySpot
//                    + "; interrupted="
//                    + Thread.currentThread().isInterrupted()
//                    );
                wait(wt); // spurious wakeup ok
                lastNS = this.lastNotifySpot;
                done = (lastNS != 0);
                if (!done) {
                  // calc remaing wait time to fix bug 37196
                  waitTimeMs = endTime - System.currentTimeMillis();
                }
              } // while
              if (done) {
                this.lastNotifySpot = 0;
              }
            }
            if (this.requestInProgress && !this.selectedNodeDead) {
              // added the test of "!this.selectedNodeDead" for bug 37196
              StringBuilder sb = new StringBuilder(200);
              sb.append("processorId=").append(this.processorId);
              sb.append(" Key is ").append(this.key);
              sb.append(" searchTimeoutMs ").append(timeoutMs);
              if (waitTimeMs > 0) {
                sb.append(" msRemaining=").append(waitTimeMs);
              }
              if (lastNS != 0) {
                sb.append(" lastNotifySpot=" + lastNS);
              }
              throw new TimeoutException(LocalizedStrings.SearchLoadAndWriteProcessor_TIMEOUT_DURING_NETSEARCHNETLOADNETWRITE_DETAILS_0.toLocalizedString(sb));
            }
            return;
          }
          catch (InterruptedException e) {
            interrupted = true;
            region.getCancelCriterion().checkCancelInProgress(null);
            // keep waiting until we are done
            waitTimeMs = endTime - System.currentTimeMillis();
            // continue
          }
          finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        } // for
      } finally {
        computeRemainingTimeout();
      }
    } // requestInProgress
  }

  private int getSearchTimeout() {
    return region.getCache().getSearchTimeout(); //CacheFactory.getInstance(((DistributedRegion)this.region).getSystem()).getSearchTimeout();
  }

  private void resetResults() {
    this.netSearch = false;
    this.netLoad = false;
    this.localLoad = false;
    this.localWrite = false;
    this.netWrite = false;
    this.lastModified = 0;
    this.isSerialized = false;
  }


//  private AcceptHelper getAcceptHelper(boolean ackPortInit) {
//    AcceptHelper  helper = null;
//    synchronized(availableAcceptHelperSet) {
//      if (availableAcceptHelperSet.size() <= 0) {
//        helper = new AcceptHelper();
//      }
//      else {
//        helper = (AcceptHelper)availableAcceptHelperSet.iterator().next();
//        availableAcceptHelperSet.remove(helper);
//      }
//    }
//    helper.reset(ackPortInit);
//    return helper;
//
//  }

//  private void releaseAcceptHelper(AcceptHelper helper) {
//    synchronized(availableAcceptHelperSet) {
//      if (!availableAcceptHelperSet.contains(helper))
//        availableAcceptHelperSet.add(helper);
//    }
//
//  }
  
  @Override  
  public String toString() {
    return super.toString() + " processorId " + this.processorId;
  }

  /**
   * methods to set/remove htree reference (Bug 40299).
   */
  protected static void setClearCountReference(LocalRegion rgn) {
    DiskRegion dr = rgn.getDiskRegion();
    if (dr != null) {
      dr.setClearCountReference();
    };
  }

  protected static void removeClearCountReference(LocalRegion rgn) {
    DiskRegion dr = rgn.getDiskRegion();
    if (dr != null) {
          dr.removeClearCountReference();
    };
  }

  /**
   * Test method for bug 40299.
   */
  @SuppressWarnings("synthetic-access")
  public void testNetSearchMessageDoGet(String theRegionName,
          Object theKey,
          int theTimeoutMs,
          int theTtl,
          int theIdleTime) {
    NetSearchRequestMessage nMsg = new NetSearchRequestMessage();
    nMsg.initialize(this, theRegionName, theKey, theTimeoutMs, theTtl, theIdleTime);
    nMsg.doGet((DistributionManager)this.distributionManager);
  }

  /*****************************************************************************
   * INNER CLASSES
   *****************************************************************************/



  /**
   * A QueryMessage is broadcast to every node that has the region defined,
   * to find out who has a valid copy of the requested object.
   */
  public static final class QueryMessage extends AbstractOperationMessage {

    /** The fully qualified name of the Region */
    private String regionName;

    /** The object name */
    private Object key;

    /** Amount of time to wait before giving up */
    private int timeoutMs;

    /** The originator's expiration criteria */
    private int ttl, idleTime;

    /** if true then always send back value even if it is large.
     * Added for bug 35942.
     */
    private boolean alwaysSendResult;

    // using flags otherwise unused by this message
    private static final short HAS_TTL = UNRESERVED_FLAGS_START;
    private static final short HAS_IDLE_TIME = (HAS_TTL << 1);
    private static final short ALWAYS_SEND_RESULT = (HAS_IDLE_TIME << 1);

    public QueryMessage() {
      super((TXStateInterface)null);
    }

    /**
     * Using a new or pooled message instance, create and send
     * the query to all nodes.
     */
    public static void sendMessage(SearchLoadAndWriteProcessor processor,
    String regionName,
    Object key,
    boolean multicast,
    Set recipients,
    int timeoutMs,
    int ttl,
    int idleTime) {

      // create a message
      QueryMessage msg = new QueryMessage();
      msg.initialize(processor, regionName, key, multicast, timeoutMs,ttl,idleTime);
      msg.setRecipients(recipients);
      if (!multicast && recipients.size() == 1) {
        // we are only searching one guy so tell him to send the value
        msg.alwaysSendResult = true;
      }
      processor.distributionManager.putOutgoing(msg);

    }

    private void initialize(SearchLoadAndWriteProcessor processor,
                              String theRegionName,
                              Object theKey,
                              boolean multicast,
                              int theTimeoutMs,
                              int theTtl,
                              int theIdleTime) {
      this.processorId = processor.processorId;
      this.regionName = theRegionName;
      setMulticast(multicast);
      this.key = theKey;
      this.timeoutMs = theTimeoutMs;
      this.ttl = theTtl;
      this.idleTime = theIdleTime;
      Assert.assertTrue(processor.region.getScope().isDistributed());
    }

    /**
     * This method execute's on the receiver's node, and
     * checks to see if the requested object exists in
     * shared memory on this node, and if so, sends back
     * a ResponseMessage.
     */
    @Override
    protected final void basicProcess(final DistributionManager dm) {
      doGet(dm);
    }

    public int getDSFID() {
      return QUERY_MESSAGE;
    }

    @Override
    public final int getProcessorType() {
      // don't use SERIAL_EXECUTOR if we may have to wait for a pending TX
      // else if may deadlock as the p2p msg reader thread will be blocked
      return this.pendingTXId == null ? DistributionManager.SERIAL_EXECUTOR
          : DistributionManager.STANDARD_EXECUTOR;
    }

    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);

      out.writeUTF(this.regionName);
      DataSerializer.writeObject(this.key,out);
      out.writeInt(this.timeoutMs);
      if (this.ttl != 0) {
        InternalDataSerializer.writeSignedVL(this.ttl, out);
      }
      if (this.idleTime != 0) {
        InternalDataSerializer.writeSignedVL(this.idleTime, out);
      }
    }

    @Override
    protected final short computeCompressedShort(short flags) {
      if (this.ttl != 0) {
        flags |= HAS_TTL;
      }
      if (this.idleTime != 0) {
        flags |= HAS_IDLE_TIME;
      }
      if (this.alwaysSendResult) {
        flags |= ALWAYS_SEND_RESULT;
      }
      return flags;
    }

    @Override
    public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.regionName = in.readUTF();
      this.key = DataSerializer.readObject(in);
      this.timeoutMs = in.readInt();
      if ((flags & HAS_TTL) != 0) {
        this.ttl = (int)InternalDataSerializer.readSignedVL(in);
      }
      if ((flags & HAS_IDLE_TIME) != 0) {
        this.idleTime = (int)InternalDataSerializer.readSignedVL(in);
      }
      this.alwaysSendResult = (flags & ALWAYS_SEND_RESULT) != 0;
    }

    @Override
    protected void appendFields(final StringBuilder sb) {
      sb.append("; key=").append(this.key);
      sb.append("; region=").append(this.regionName);
      sb.append("; timeoutMs=").append(this.timeoutMs);
      sb.append("; ttl=").append(this.ttl);
      sb.append("; idleTime=").append(this.idleTime);
    }

    private void doGet(DistributionManager dm) {
      long startTime = dm.cacheTimeMillis();
//      boolean retVal = true;
      boolean isPresent = false;
      boolean sendResult = false;
      boolean isSer = false;
      long lastModifiedCacheTime = 0;
      boolean requestorTimedOut = false;
      VersionTag tag = null;
      
      if (dm.getDMType() == DistributionManager.ADMIN_ONLY_DM_TYPE
          || getSender().equals(dm.getDistributionManagerId()) ) {
        // this was probably a multicast message
        //replyWithNull(dm); - bug 35266: don't send a reply
        return;
      }
      
      int oldLevel = LocalRegion.setThreadInitLevelRequirement(LocalRegion.BEFORE_INITIAL_IMAGE);
      try {
        // check to see if we would have to wait on initialization latch (if global)
        // if so abort and reply with null
        GemFireCacheImpl gfc = (GemFireCacheImpl)CacheFactory.getInstance(dm.getSystem());
        if (gfc.isGlobalRegionInitializing(this.regionName)) {          
          replyWithNull(dm);
          dm.getLoggerI18n().fine("Global Region not initialized yet");
          return;
        }
        
        LocalRegion region  = (LocalRegion)CacheFactory
          .getInstance(dm.getSystem()).getRegion(this.regionName);
        Object o = null;

        if (region != null)  {
	  setClearCountReference(region);	
	  try {
            RegionEntry entry = region.basicGetEntry(this.key);
            if (entry != null) {
              synchronized(entry) {
                assert region.isInitialized();
                {
                  if (dm.cacheTimeMillis() - startTime < timeoutMs) {
                    o = region.getNoLRU(this.key, false, true, true); // OFFHEAP: incrc, copy bytes, decrc
                    if (o != null && !Token.isInvalid(o) && !Token.isRemoved(o) &&
                        !region.isExpiredWithRegardTo(this.key,
                            this.ttl,
                            this.idleTime)) {
                      isPresent = true;
                      VersionStamp stamp = entry.getVersionStamp();
                      if (stamp != null && stamp.hasValidVersion()) {
                        tag = stamp.asVersionTag();
                      }
                      long lastModified = entry.getLastModified();
                      lastModifiedCacheTime = lastModified;
                      isSer = o instanceof CachedDeserializable;
                      if (isSer) {
                        o = ((CachedDeserializable)o).getSerializedValue();
                      }
                      if (isPresent
                          && (this.alwaysSendResult
                              || (ObjectSizer.DEFAULT.sizeof(o) < SMALL_BLOB_SIZE))) {
                        sendResult = true;
                      }
                    }
                  }
                  else {
                    requestorTimedOut = true;
                  }
                }
              }
            }
            else
              dm.getLoggerI18n().fine("Entry is null");
	  }
	  finally {
	    removeClearCountReference(region);
	  }
        }
        ResponseMessage.sendMessage( this.key, this.getSender(),processorId,
                                     (sendResult ? o : null),
                                     lastModifiedCacheTime, isPresent, isSer,
                                     requestorTimedOut, dm, tag);
      }
      catch (RegionDestroyedException rde) {
        dm.getLoggerI18n().fine("Region Destroyed Exception in QueryMessage doGet, null");
        replyWithNull(dm);
      }
      catch (CancelException cce) {
        dm.getLoggerI18n().fine("CacheClosedException in QueryMessage doGet, null");
        replyWithNull(dm);
      }
      catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        dm.getLoggerI18n().fine("Throwable in QueryMessage doGet, null");
        replyWithNull(dm);
      }
      finally {
        LocalRegion.setThreadInitLevelRequirement(oldLevel);
      }
    }

    private void replyWithNull(DistributionManager dm) {
      ResponseMessage.sendMessage(this.key,this.getSender(),processorId, null,
                                  0,false, false, false,dm, null);

    }

  }

  /********************* ResponseMessage ***************************************/


    /**
     * The ResponseMessage is a reply to a QueryMessage, and contains the
     * object's value, if it is below the byte limit, otherwise an indication
     * of whether the sender has the value.
     */
    public static final class ResponseMessage extends HighPriorityDistributionMessage {

      private Object key;

      /** The gemfire id of the SearchLoadAndWrite object waiting for response */
      private int processorId;

      /** The value being transferred */
      private Object result;

      /** Object creation time on remote node */
      private long lastModified;

      /** is the value present*/
      private boolean isPresent;


      /** Is blob serialized? */
      private boolean isSerialized;

      /** did the request time out at the sender*/
      private boolean requestorTimedOut;
      /** the version of the object being returned */
      private VersionTag versionTag;
      

      public ResponseMessage() {}

      public static void sendMessage(Object key,
                                    InternalDistributedMember recipient,
                                    int processorId,
                                    Object result, long lastModified,
                                    boolean isPresent, boolean isSerialized,
                                    boolean requestorTimedOut,
                                    DistributionManager distributionManager, VersionTag versionTag) {

        // create a message
        ResponseMessage msg = new ResponseMessage();
        msg.initialize(key,processorId, result, lastModified,isPresent,isSerialized,requestorTimedOut, versionTag);
        msg.setRecipient(recipient);
        distributionManager.putOutgoing(msg);

      }

      private void initialize(Object theKey, int theProcessorId,  Object theResult,
          long lastModifiedTime, boolean ispresent, boolean isserialized,
          boolean requestorTimedOutFlag, VersionTag versionTag) {
        this.key = theKey;
        this.processorId = theProcessorId;
        this.result = theResult;
        this.lastModified = lastModifiedTime;
        this.isPresent = ispresent;
        this.isSerialized = isserialized;
        this.requestorTimedOut = requestorTimedOutFlag;
        this.versionTag = versionTag;
      }

      /**
       * Invoked on the receiver - which, in this case, was the initiator of
       * the QueryMessage .
       */
      @Override  
      protected void process(DistributionManager dm) {
        // NOTE: keep this method efficient since it is optimized
        // by executing it in the p2p reader.
        // This is done with this line in DistributionMessage.java:
        // || c.equals(SearchLoadAndWriteProcessor.ResponseMessage.class)
        SearchLoadAndWriteProcessor processor = null;
        processor = (SearchLoadAndWriteProcessor)getProcessorKeeper().retrieve(this.processorId);
        if (processor == null)  {
          logFine("Response() SearchLoadAndWriteProcessor no longer exists", null);
          return;
        }
        long lastModifiedSystemTime = 0;
        if (this.lastModified != 0) {
          lastModifiedSystemTime = this.lastModified;
        }
        if (this.versionTag != null) {
          this.versionTag.replaceNullIDs(getSender());
        }

        processor.incomingResponse(this.result, lastModifiedSystemTime,
                                   this.isPresent, this.isSerialized,
                                   this.requestorTimedOut,
                                   this.getSender(),
                                   dm, versionTag);
      }
      
      @Override  
      public boolean getInlineProcess() {           // optimization for bug 37075
        return true;
      }

      public int getDSFID() {
        return RESPONSE_MESSAGE;
      }

      @Override  
      public void toData(DataOutput out) throws IOException  {
        super.toData(out);
        DataSerializer.writeObject(this.key, out);
        out.writeInt(this.processorId);
        DataSerializer.writeObject(this.result,out);
        out.writeLong(this.lastModified);
        out.writeBoolean(this.isPresent);
        out.writeBoolean(this.isSerialized);
        out.writeBoolean(this.requestorTimedOut);
        DataSerializer.writeObject(this.versionTag, out);
      }

      @Override  
      public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
        super.fromData(in);
        this.key = DataSerializer.readObject(in);
        this.processorId = in.readInt();
        this.result = DataSerializer.readObject(in);
        this.lastModified = in.readLong();
        this.isPresent = in.readBoolean();
        this.isSerialized = in.readBoolean();
        this.requestorTimedOut = in.readBoolean();
        this.versionTag = (VersionTag)DataSerializer.readObject(in);
      }

      @Override  
      public String toString() {
        return "SearchLoadAndWriteProcessor.ResponseMessage for processorId " +
        processorId + ", blob is " + this.result + ", isPresent is " + isPresent
        + ", requestorTimedOut is " + requestorTimedOut + ", version is " + versionTag;
      }

  }

  /********************* NetSearchRequestMessage ***************************************/

  public static final class NetSearchRequestMessage extends
      AbstractOperationMessage {

    /** The fully qualified name of the Region */
    private String regionName;

    /** The object name */
    private Object key;

    /** Amount of time to wait before giving up */
    private int timeoutMs;

    /** The originator's expiration criteria */
    private int ttl, idleTime;

    // using couple of flags not used otherwise
    private static final short HAS_TTL = UNRESERVED_FLAGS_START;
    private static final short HAS_IDLE_TIME = (HAS_TTL << 1);

    public NetSearchRequestMessage() {
      super((TXStateInterface)null);
    }

    /**
     * Using a new or pooled message instance, create and send
     * the request for object value to the specified node.
     */
    public static void sendMessage(SearchLoadAndWriteProcessor processor,
    String regionName,
    Object key,
    InternalDistributedMember recipient,
    int timeoutMs,
    int ttl,
    int idleTime) {

      // create a message
      NetSearchRequestMessage msg = new NetSearchRequestMessage();
      msg.initialize(processor, regionName, key,timeoutMs, ttl,idleTime);
      msg.setRecipient(recipient);
      processor.distributionManager.putOutgoing(msg);

    }

    private void initialize(SearchLoadAndWriteProcessor processor,
    String theRegionName,
    Object theKey,
    int timeoutMS,
    int ttlMS,
    int idleTimeMS) {
      this.processorId = processor.processorId;
      this.regionName = theRegionName;
      this.key = theKey;
      this.timeoutMs = timeoutMS;
      this.ttl = ttlMS;
      this.idleTime = idleTimeMS;
      Assert.assertTrue(processor.region.getScope().isDistributed());
    }

    /** Invoked on the node that has the object */
    @Override
    protected final void basicProcess(final DistributionManager dm) {
      doGet(dm);
    }

    public int getDSFID() {
      return NET_SEARCH_REQUEST_MESSAGE;
    }

    @Override
    public final int getProcessorType() {
      return DistributionManager.STANDARD_EXECUTOR;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);

      out.writeUTF(this.regionName);
      DataSerializer.writeObject(this.key,out);
      out.writeInt(this.timeoutMs);
      if (this.ttl != 0) {
        InternalDataSerializer.writeSignedVL(this.ttl, out);
      }
      if (this.idleTime != 0) {
        InternalDataSerializer.writeSignedVL(this.idleTime, out);
      }
    }

    @Override
    protected final short computeCompressedShort(short flags) {
      if (this.ttl != 0) {
        flags |= HAS_TTL;
      }
      if (this.idleTime != 0) {
        flags |= HAS_IDLE_TIME;
      }
      return flags;
    }

    @Override
    public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.regionName = in.readUTF();
      this.key = DataSerializer.readObject(in);
      this.timeoutMs = in.readInt();
      if ((flags & HAS_TTL) != 0) {
        this.ttl = (int)InternalDataSerializer.readSignedVL(in);
      }
      if ((flags & HAS_IDLE_TIME) != 0) {
        this.idleTime = (int)InternalDataSerializer.readSignedVL(in);
      }
    }

    @Override  
    protected void appendFields(final StringBuilder sb) {
      sb.append("; key=").append(this.key);
      sb.append("; region=").append(this.regionName);
      sb.append("; timeoutMs=").append(this.timeoutMs);
      sb.append("; ttl=").append(this.ttl);
      sb.append("; idleTime=").append(this.idleTime);
    }

    private void doGet(DistributionManager dm) {
      long startTime = dm.cacheTimeMillis();
//      boolean retVal = true;
      byte[] ebv = null;
      Object ebvObj = null;
      int ebvLen = 0;
      long lastModifiedCacheTime =0;
      boolean isSer = false;
      boolean requestorTimedOut = false;
      boolean authoritative = false;
      VersionTag versionTag = null;

      int oldLevel = LocalRegion.setThreadInitLevelRequirement(LocalRegion.BEFORE_INITIAL_IMAGE);
      try {
        LocalRegion region = (LocalRegion)CacheFactory
          .getInstance(dm.getSystem()).getRegion(this.regionName);
        if (region != null)  {
          setClearCountReference(region);		
	  try {
	    boolean initialized = region.isInitialized();
	    if(region.keyRequiresRegionContext()) {
	      ((KeyWithRegionContext)this.key).setRegionContext(region);
	    }
            RegionEntry entry = region.basicGetEntry(this.key);
            if (entry != null) {
              synchronized (entry) {
                // get the value and version under synchronization so they don't change
                VersionStamp versionStamp = entry.getVersionStamp();
                if (versionStamp != null) {
                  versionTag = versionStamp.asVersionTag();
                }
                Object eov = region.getNoLRU(this.key, false, true, true); // OFFHEAP: incrc, copy bytes, decrc
                if (eov != null) {
                  if (eov == Token.INVALID || eov == Token.LOCAL_INVALID) {
//                    ebv = null; (redundant assignment)
                  }
                  else if (dm.cacheTimeMillis() - startTime < timeoutMs) {
                    if (!region.isExpiredWithRegardTo(this.key, this.ttl, this.idleTime)) {
                      long lastModified = entry.getLastModified();
                      lastModifiedCacheTime = lastModified;
                      if (eov instanceof CachedDeserializable) {
                        StoredObject so;
                        if (eov instanceof StoredObject
                            && !(so = (StoredObject)eov).isSerialized()) {
                          isSer = false;
                          Object o = so.getDeserializedForReading();
                          if (o instanceof byte[]) {
                            ebv = (byte[])o;
                            ebvLen = ebv.length;
                          }
                          else {
                            ebvObj = o;
                          }
                        } else {
                          // don't serialize here if it is not already serialized
                          Object tmp = ((CachedDeserializable)eov).getValue();
                          if (tmp instanceof byte[]) {
                            byte[] bb = (byte[])tmp;
                            ebv = bb;
                            ebvLen = bb.length;
                          } else {
                            ebvObj = tmp;
                          }
                          isSer = true;
                        }
                      }
                      else if (!(eov instanceof byte[])) {
                        ebvObj = eov;
                        isSer = true;
                      }
                      else {
                        ebv = (byte[])eov;
                        ebvLen = ebv.length;
                      }
                    }
                  }
                  else {
                    requestorTimedOut = true;
                  }
                }
            }
            }
            authoritative = region.getDataPolicy().withReplication() && initialized && !region.isDestroyed;
	  }
	  finally {
	    removeClearCountReference(region);
	  }
        }
        NetSearchReplyMessage.sendMessage(NetSearchRequestMessage.this.getSender(),
                                          processorId, this.key, ebv, ebvObj, ebvLen,
                                          lastModifiedCacheTime, isSer,
                                          requestorTimedOut, authoritative, dm, versionTag);
      }
      catch (RegionDestroyedException rde) {
        replyWithNull(dm);

      }
      catch (CancelException cce) {
        replyWithNull(dm);

      }
      catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        dm.getLoggerI18n().warning(LocalizedStrings.SearchLoadAndWriteProcessor_UNEXPECTED_EXCEPTION, t);
        replyWithNull(dm);
      }
      finally {
        LocalRegion.setThreadInitLevelRequirement(oldLevel);
      }
    }

    private void replyWithNull(DistributionManager dm) {
      NetSearchReplyMessage.sendMessage(NetSearchRequestMessage.this.getSender(),
                                        processorId, this.key, null, null, 0, 0, false,
                                        false,false,dm, null);

  }

  }

  /*********************NetSearchReplyMessage ***************************************/

  /**
   * The NetSearchReplyMessage is a reply to a NetSearchRequestMessage, and contains the
   * object's value.
   */
  public static final class NetSearchReplyMessage extends HighPriorityDistributionMessage {
    private static final byte SERIALIZED = 0x01;
    private static final byte REQUESTOR_TIMEOUT = 0x02;
    private static final byte AUTHORATIVE = 0x04;
    private static final byte VERSIONED = 0x08;
    private static final byte PERSISTENT = 0x10;

    /** The gemfire id of the SearchLoadAndWrite object waiting for response */
    private int processorId;



    /** The object value being transferred */
    private byte[] value;

    private transient Object valueObj; // only used by toData
    private transient int valueLen; // only used by toData

    /** Object creation time on remote node */
    private long lastModified;

    /** Is blob serialized? */
    private boolean isSerialized;

    /** did the request time out at the sender*/
    private boolean requestorTimedOut;
    
    /** Does this member authoritatively know the value? This is used to distinguish
     * a null response indicating the region was missing vs. a null value. */
    private boolean authoritative;
    
    /** the version of the returned entry */
    private VersionTag versionTag;

    public NetSearchReplyMessage() {}

    public static void sendMessage(InternalDistributedMember recipient,
    int processorId,
    Object key,
    byte[] value, Object valueObj, int valueLen, long lastModified,
    boolean isSerialized,
    boolean requestorTimedOut,
    boolean authoritative,
    DistributionManager distributionManager, VersionTag versionTag
    ) {
      // create a message
      NetSearchReplyMessage msg = new NetSearchReplyMessage();
      msg.initialize(processorId, value, valueObj, valueLen, lastModified,isSerialized,requestorTimedOut, authoritative, versionTag);
      msg.setRecipient(recipient);
      distributionManager.putOutgoing(msg);

    }

    @SuppressWarnings("hiding")
    private void initialize(int procId,
        byte[] theValue, Object theValueObj, int valueObjLen,
        long lastModifiedTime ,boolean isserialized, boolean requestorTimedout,
        boolean authoritative, VersionTag versionTag) {
      this.processorId = procId;
      this.value = theValue;
      this.valueObj = theValueObj;
      this.valueLen = valueObjLen;
      this.lastModified = lastModifiedTime;
      this.isSerialized = isserialized;
      this.requestorTimedOut = requestorTimedout;
      this.authoritative = authoritative;
      this.versionTag = versionTag;
    }

    /**
     * Invoked on the receiver - which, in this case, was the initiator of
     * the NetSearchRequestMessage.  This concludes the net request, by
     * communicating an object value.
     */
    @Override  
    protected void process(DistributionManager dm) {
      SearchLoadAndWriteProcessor processor = null;
      processor = (SearchLoadAndWriteProcessor)getProcessorKeeper().retrieve(processorId);
      if (processor == null)  {
        logFine("NetSearchReplyMessage() SearchLoadAndWriteProcessor " + processorId + " no longer exists", null);
        return;
      }
      long lastModifiedSystemTime = 0;
      if (this.lastModified != 0) {
        lastModifiedSystemTime = this.lastModified;
      }
      if (this.versionTag != null) {
        this.versionTag.replaceNullIDs(getSender());
      }
      processor.incomingNetSearchReply(this.value, lastModifiedSystemTime,
                                       this.isSerialized, this.requestorTimedOut,
                                       this.authoritative, this.versionTag);
    }

    public int getDSFID() {
      return NET_SEARCH_REPLY_MESSAGE;
    }
    

    @Override  
    public void toData(DataOutput out) throws IOException  {
      super.toData(out);
      out.writeInt(this.processorId);
      if (this.valueObj != null) {
        DataSerializer.writeObjectAsByteArray(this.valueObj, out);
      } else {
        DataSerializer.writeByteArray(this.value, this.valueLen, out);
      }
      out.writeLong(this.lastModified);
      byte booleans = 0;
      if(this.isSerialized) booleans |= SERIALIZED;
      if(this.requestorTimedOut) booleans |= REQUESTOR_TIMEOUT;
      if(this.authoritative) booleans |= AUTHORATIVE;
      if (this.versionTag != null) booleans |= VERSIONED;
      if (this.versionTag instanceof DiskVersionTag) booleans |= PERSISTENT;
      out.writeByte(booleans);
      if (this.versionTag != null) {
        InternalDataSerializer.invokeToData(this.versionTag, out);
    }
    }

    @Override  
    public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.processorId = in.readInt();
      this.value = DataSerializer.readByteArray(in);
      if (this.value != null) {
        this.valueLen = this.value.length;
      }
      this.lastModified = in.readLong();
      byte booleans = in.readByte();
      
      this.isSerialized = (booleans & SERIALIZED )!= 0;
      this.requestorTimedOut = (booleans & REQUESTOR_TIMEOUT)!= 0;
      this.authoritative = (booleans & AUTHORATIVE)!= 0;
      if ((booleans & VERSIONED) != 0) {
        boolean persistentTag = (booleans & PERSISTENT) != 0;
        this.versionTag = VersionTag.create(persistentTag, in);
      }
    }

    @Override  
    public String toString() {
      return "SearchLoadAndWriteProcessor.NetSearchReplyMessage for processorId " +
      processorId + ", blob is " + 
//      this.value
      (this.value == null ? "null" : "(" + this.value.length + " bytes)") +
      " authorative=" + authoritative + " versionTag=" + this.versionTag
      ;
    }

  }


  /********************************NetLoadRequestMessage**********************/

  public static final class NetLoadRequestMessage extends PooledDistributionMessage {

    /**
     * The object id of the processor object on the
     * initiator node.  This will be communicated back in the response
     * to enable transferring the result to the initiating VM.
     */
    private int processorId;

    /** The fully qualified name of the Region */
    private String regionName;

    /** The object name */
    private Object key;

    /** Parameter to use when invoking loader */
    private Object aCallbackArgument;

    /** Amount of time to wait before giving up */
    private int timeoutMs;


    /** The originator's expiration criteria */
    private int ttl, idleTime;

    public NetLoadRequestMessage() {}

    /**
     * Using a new or pooled message instance, create and send
     * the request for object value to the specified node.
     */
    public static void sendMessage(SearchLoadAndWriteProcessor processor,
    String regionName,
    Object key,
    Object aCallbackArgument,
    InternalDistributedMember recipient,
    int timeoutMs,
    int ttl,
    int idleTime) {

      // create a message
      NetLoadRequestMessage msg = new NetLoadRequestMessage();
      msg.initialize(processor, regionName, key,aCallbackArgument,timeoutMs,ttl,idleTime);
      msg.setRecipient(recipient);
      
      try {
        processor.distributionManager.putOutgoingUserData(msg);
      } catch (NotSerializableException e) {
        throw new IllegalArgumentException(LocalizedStrings.SearchLoadAndWriteProcessor_MESSAGE_NOT_SERIALIZABLE.toLocalizedString());
      }
    }

    private void initialize(SearchLoadAndWriteProcessor processor,
        String theRegionName,
        Object theKey,
        Object callbackArgument,
        int timeoutMS,
        int ttlMS,
        int idleTimeMS) {
      this.processorId = processor.processorId;
      this.regionName = theRegionName;
      this.key = theKey;
      this.aCallbackArgument = callbackArgument;
      this.timeoutMs = timeoutMS;
      this.ttl = ttlMS;
      this.idleTime = idleTimeMS;
      Assert.assertTrue(processor.region.getScope().isDistributed());
    }

    /** Invoked on the node that has the object */
    @Override  
    protected void process(DistributionManager dm) {
      doLoad(dm);
    }

    public int getDSFID() {
      return NET_LOAD_REQUEST_MESSAGE;
    }

    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      out.writeInt(this.processorId);
      out.writeUTF(this.regionName);
      DataSerializer.writeObject(this.key,out);
      DataSerializer.writeObject(this.aCallbackArgument,out);
      out.writeInt(this.timeoutMs);
      out.writeInt(this.ttl);
      out.writeInt(this.idleTime);

    }

    @Override  
    public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.processorId = in.readInt();
      this.regionName = in.readUTF();
      this.key = DataSerializer.readObject(in);
      this.aCallbackArgument = DataSerializer.readObject(in);
      this.timeoutMs = in.readInt();
      this.ttl = in.readInt();
      this.idleTime = in.readInt();
    }

    @Override  
    public String toString() {
      return "SearchLoadAndWriteProcessor.NetLoadRequestMessage for \"" + this.key
      + "\" in region \"" + this.regionName + "\", processorId " + processorId;
    }



    private void doLoad(DistributionManager dm) {
      long startTime = dm.cacheTimeMillis();
      int oldLevel = LocalRegion.setThreadInitLevelRequirement(LocalRegion.BEFORE_INITIAL_IMAGE);
      try {
        GemFireCacheImpl gfc = (GemFireCacheImpl)CacheFactory.getInstance(dm.getSystem());
        LocalRegion region = (LocalRegion)gfc.getRegion(this.regionName);
        if (region != null
            && region.isInitialized()
            && (dm.cacheTimeMillis() - startTime < timeoutMs)) {
          CacheLoader loader = ((AbstractRegion)region).basicGetLoader();
          if (loader != null) {
              LoaderHelper loaderHelper = region.loaderHelperFactory.createLoaderHelper(this.key,
                  this.aCallbackArgument, false, false, null);
              CachePerfStats stats = region.getCachePerfStats();
              long start = stats.startLoad();
              try {
                Object o = loader.load(loaderHelper);
                Assert.assertTrue(o != Token.INVALID && o != Token.LOCAL_INVALID);
                NetLoadReplyMessage.sendMessage(NetLoadRequestMessage.this.getSender(),
                processorId, o,dm,loaderHelper.getArgument(),null,false, false);

              }
              catch(Exception e) {
                replyWithException(e,dm);
              }
              finally {
                stats.endLoad(start);
              }
          }
          else {
            replyWithException(new TryAgainException(LocalizedStrings.SearchLoadAndWriteProcessor_NO_LOADER_DEFINED_0.toLocalizedString()), dm);
          }

        }
        else {
          replyWithException(new TryAgainException(LocalizedStrings.SearchLoadAndWriteProcessor_TIMEOUT_EXPIRED_OR_REGION_NOT_READY_0.toLocalizedString()), dm);
        }

      }
      catch (RegionDestroyedException rde) {
        replyWithException(rde,dm);

      }
      catch (CancelException cce) {
        replyWithException(cce,dm);

      }
      catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        replyWithException(new InternalGemFireException(LocalizedStrings.SearchLoadAndWriteProcessor_ERROR_PROCESSING_REQUEST.toLocalizedString(), t), dm);
      }
      finally {
        LocalRegion.setThreadInitLevelRequirement(oldLevel);
      }

    }

    void replyWithException(Exception e, DistributionManager dm) {
      NetLoadReplyMessage.sendMessage(NetLoadRequestMessage.this.getSender(),
      processorId, null ,
      dm,this.aCallbackArgument,
      e,
      false,false);

    }


  }



  /********************* NetLoadReplyMessage ***************************************/

  /**
   * The NetLoadReplyMessage is a reply to a RequestMessage, and contains the
   * object's value.
   */
  public static final class NetLoadReplyMessage extends HighPriorityDistributionMessage {

    /** The gemfire id of the SearchLoadAndWrite object waiting for response */
    private int processorId;

    /** The object value being transferred */
    private Object result;


    /** Loader parameter returned to sender*/
    private Object aCallbackArgument;

    /** Exception thrown by remote node */
    private Exception e;

    /** Is blob serialized? */
    private boolean isSerialized;

    /** ??? */
    private boolean requestorTimedOut;

    private static final byte IS_SERIALIZED = 0x1;

    private static final byte AS_OBJECT = 0x2;

    private static final byte REQUEST_OR_TIMED_OUT = 0x4;

    public NetLoadReplyMessage() {}

    public static void sendMessage(InternalDistributedMember recipient,
    int processorId,
    Object obj,
    DistributionManager distributionManager,
    Object aCallbackArgument,
    Exception e,
    boolean isSerialized,
    boolean requestorTimedOut) {
      // create a message
      NetLoadReplyMessage msg = new NetLoadReplyMessage();
      msg.initialize(processorId, obj,aCallbackArgument,e, isSerialized,requestorTimedOut);
      msg.setRecipient(recipient);
      distributionManager.putOutgoing(msg);
    }

    private void initialize(int procId, Object obj,
        Object callbackArgument, Exception exe,
        boolean isserialized,
        boolean requestorTimedout) {
      this.processorId = procId;
      this.result = obj;
      this.e = exe;
      this.aCallbackArgument = callbackArgument;
      this.isSerialized = isserialized;
      this.requestorTimedOut = requestorTimedout;
    }

    /**
     * Invoked on the receiver - which, in this case, was the initiator of
     * the NetLoadRequestMessage.  This concludes the net request, by
     * communicating an object value.
     */
    @Override  
    protected void process(DistributionManager dm) {
      SearchLoadAndWriteProcessor processor = null;
      processor = (SearchLoadAndWriteProcessor)getProcessorKeeper().retrieve(processorId);
      if (processor == null) {
        logFine("NetLoadReplyMessage() SearchLoadAndWriteProcessor no longer exists", null);
        return;
      }
      processor.incomingNetLoadReply(this.result, 0,this.aCallbackArgument,
      this.e,this.isSerialized, this.requestorTimedOut);
    }

    public int getDSFID() {
      return NET_LOAD_REPLY_MESSAGE;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      out.writeInt(this.processorId);

      int flags = (this.requestorTimedOut ? REQUEST_OR_TIMED_OUT : 0);
      if (result instanceof byte[]) {
        if (this.isSerialized) {
          flags |= IS_SERIALIZED;
        }
        out.writeByte(flags);
        DataSerializer.writeByteArray((byte[])this.result, out);
      }
      else if (CachedDeserializableFactory.preferObject()) {
        out.writeByte(flags | AS_OBJECT);
        DataSerializer.writeObject(this.result, out);
      }
      else {
        out.writeByte(flags | IS_SERIALIZED);
        DataSerializer.writeObjectAsByteArray(this.result, out);
      }
      DataSerializer.writeObject(this.aCallbackArgument, out);
      DataSerializer.writeObject(this.e, out);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      this.processorId = in.readInt();

      final int flags = in.readByte();
      if ((flags & AS_OBJECT) != 0) {
        this.result = DataSerializer.readObject(in);
      }
      else {
        this.result = DataSerializer.readByteArray(in);
      }
      this.aCallbackArgument = DataSerializer.readObject(in);
      this.e = DataSerializer.readObject(in);
      this.isSerialized = (flags & IS_SERIALIZED) != 0;
      this.requestorTimedOut = (flags & REQUEST_OR_TIMED_OUT) != 0;
    }

    @Override  
    public String toString() {
      return "SearchLoadAndWriteProcessor.NetLoadReplyMessage for processorId " +
      processorId + ", blob is " + this.result;
    }
  }

  /********************* NetWriteRequestMessage *******************************/

  public static final class NetWriteRequestMessage extends PooledDistributionMessage {

    /**
     * The object id of the processor object on the
     * initiator node.  This will be communicated back in the response
     * to enable transferring the result to the initiating VM.
     */
    private int processorId;

    /** The fully qualified name of the Region */
    private String regionName;

    /** The event being sent over to the remote writer*/
    CacheEvent event;

    private int timeoutMs;
    /**Action requested by sender*/
    int action;

    public NetWriteRequestMessage() {}

    /**
     * Using a new or pooled message instance, create and send
     * the request for object value to the specified node.
     */
    public static void sendMessage(SearchLoadAndWriteProcessor processor,
    String regionName,int timeoutMs,
    CacheEvent event, Set recipients,
    int action) {

      NetWriteRequestMessage msg = new NetWriteRequestMessage();
      msg.initialize(processor, regionName,timeoutMs, event,action);
      msg.setRecipients(recipients);
      processor.distributionManager.putOutgoing(msg);

    }

    private void initialize(SearchLoadAndWriteProcessor processor,
        String theRegionName,int timeoutMS,
        CacheEvent theEvent, int actionType) {
      this.processorId = processor.processorId;
      this.regionName = theRegionName;
      this.timeoutMs = timeoutMS;
      this.event = theEvent;
      this.action = actionType;
      Assert.assertTrue(processor.region.getScope().isDistributed());
    }

    public int getDSFID() {
      return NET_WRITE_REQUEST_MESSAGE;
    }

    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      out.writeInt(this.processorId);
      out.writeUTF(this.regionName);
      out.writeInt(this.timeoutMs);
      DataSerializer.writeObject(this.event,out);
      out.writeInt(this.action);

    }

    @Override  
    public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.processorId = in.readInt();
      this.regionName = in.readUTF();
      this.timeoutMs = in.readInt();
      this.event = DataSerializer.readObject(in);
      this.action = in.readInt();
    }

    @Override  
    public String toString() {
      return "SearchLoadAndWriteProcessor.NetWriteRequestMessage " 
      + " for region \"" + this.regionName + "\", processorId " + processorId;
    }

    /** Invoked on the node that has the object */
    @Override  
    protected void process(DistributionManager dm) {
      long startTime = dm.cacheTimeMillis();
      int oldLevel = LocalRegion.setThreadInitLevelRequirement(LocalRegion.BEFORE_INITIAL_IMAGE);
      try {
        GemFireCacheImpl gfc = (GemFireCacheImpl)CacheFactory.getInstance(dm.getSystem());
        LocalRegion region = (LocalRegion)gfc.getRegion(this.regionName);
        if (region != null &&  region.isInitialized() &&
            (dm.cacheTimeMillis() - startTime < timeoutMs)) {
          CacheWriter writer = region.basicGetWriter();
          EntryEventImpl entryEvtImpl = null;
          RegionEventImpl regionEvtImpl = null;
          if (this.event instanceof EntryEventImpl) {
            entryEvtImpl = (EntryEventImpl) this.event;
            entryEvtImpl.region = region;
            Operation op = entryEvtImpl.getOperation();
            if (op == Operation.REPLACE) {
              entryEvtImpl.setOperation(Operation.UPDATE);
            } else if (op == Operation.PUT_IF_ABSENT) {
              entryEvtImpl.setOperation(Operation.CREATE);
            } else if (op == Operation.REMOVE) {
              entryEvtImpl.setOperation(Operation.DESTROY);
            }
            // [bruce] even if this event was transmitted from another VM, its operation may have
            //         originated in this VM.  PartitionedRegion.put() with a cacheWriter is one
            //         situation where this can occur
            entryEvtImpl.setOriginRemote(event.getDistributedMember() == null
                || !event.getDistributedMember().equals(dm.getDistributionManagerId()));
          }
          else if (this.event instanceof RegionEventImpl) {
            regionEvtImpl = (RegionEventImpl) this.event;
            regionEvtImpl.region = region;
            regionEvtImpl.originRemote = true;
          }

          if (writer != null) {
            try {
              switch(action) {
                case BEFORECREATE:
                  writer.beforeCreate(entryEvtImpl);
                  break;
                case BEFOREDESTROY:
                  writer.beforeDestroy(entryEvtImpl);
                  break;
                case BEFOREUPDATE:
                  writer.beforeUpdate(entryEvtImpl);
                  break;
                case BEFOREREGIONDESTROY:
                  writer.beforeRegionDestroy(regionEvtImpl);
                  break;
                case BEFOREREGIONCLEAR:
                  writer.beforeRegionClear(regionEvtImpl);
                  break;
                default:
                  break;

              }
              NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
              processorId,dm,true,null,false);
            }
            catch(CacheWriterException cwe) {
              NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
              processorId, dm,false,cwe,true);
            }
            catch(Exception e) {
              NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
              processorId, dm,false,e,false);
            }

          }
          else {
            NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
            processorId, dm,false,
            new TryAgainException(LocalizedStrings.SearchLoadAndWriteProcessor_NO_CACHEWRITER_DEFINED_0.toLocalizedString()), true);
          }

        }
        else {
          NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
                                            processorId, dm, false,
                                            new TryAgainException(LocalizedStrings.SearchLoadAndWriteProcessor_TIMEOUT_EXPIRED_OR_REGION_NOT_READY_0.toLocalizedString()), true);

        }
      }
      catch (RegionDestroyedException rde) {
        NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
        processorId, dm,false,null,false);

      }
      catch (DistributedSystemDisconnectedException e) {
        // shutdown condition
        throw e;
      }
      catch (CancelException cce) {
        dm.getCancelCriterion().checkCancelInProgress(cce); // TODO anyway to find the region or cache here?
        NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
            processorId, dm,false,null,false);
      }
      catch (Throwable t){
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        NetWriteReplyMessage.sendMessage(NetWriteRequestMessage.this.getSender(),
            processorId, dm,false,
            new InternalGemFireException(LocalizedStrings.SearchLoadAndWriteProcessor_ERROR_PROCESSING_REQUEST.toLocalizedString(), t), true);
      }
      finally {
        LocalRegion.setThreadInitLevelRequirement(oldLevel);
      }



    }


  }

  /********************* NetWriteReplyMessage *********************************/

  /**
   * The NetWriteReplyMessage is a reply to a NetWriteRequestMessage, and contains the
   * success code or exception that is propagated back to the requestor
   */
  public static final class NetWriteReplyMessage extends HighPriorityDistributionMessage {

    /** The gemfire id of the SearchLoadAndWrite object waiting for response */
    private int processorId;

    /** Indicates whether the write succeeded */
    private boolean netWriteSucceeded;


    /** Exception thrown by remote node */
    private Exception e;

    /** Is the exception a cacheLoaderException */
    private boolean cacheWriterException;

    public NetWriteReplyMessage() {}

    public static void sendMessage(InternalDistributedMember recipient,
    int processorId,
    DistributionManager distributionManager,
    boolean netWriteSucceeded,
    Exception e,
    boolean cacheWriterException) {
      // create a message
      NetWriteReplyMessage msg = new NetWriteReplyMessage();
      msg.initialize(processorId, netWriteSucceeded, e, cacheWriterException);
      msg.setRecipient(recipient);
      distributionManager.putOutgoing(msg);
    }

    private void initialize(int procId, boolean netwriteSucceeded,
    Exception except, boolean cacheWriterExcept) {
      this.processorId = procId;
      this.netWriteSucceeded = netwriteSucceeded;
      this.e = except;
      this.cacheWriterException = cacheWriterExcept;
    }

    /**
     * Invoked on the receiver - which, in this case, was the initiator of
     * the NetWriteRequestMessage.  This concludes the net write request, by
     * communicating an object value.
     */
    @Override  
    protected void process(DistributionManager dm) {
      SearchLoadAndWriteProcessor processor = null;
      processor = (SearchLoadAndWriteProcessor)getProcessorKeeper().retrieve(processorId);
      if (processor == null) {
        logFine("NetWriteReplyMessage() SearchLoadAndWriteProcessor no longer exists", null);
        return;
      }
      processor.incomingNetWriteReply(this.netWriteSucceeded, this.e,
      this.cacheWriterException);
    }

    public int getDSFID() {
      return NET_WRITE_REPLY_MESSAGE;
    }

    @Override  
    public void toData(DataOutput out) throws IOException  {
      super.toData(out);
      out.writeInt(this.processorId);
      out.writeBoolean(this.netWriteSucceeded);
      DataSerializer.writeObject(this.e,out);
      out.writeBoolean(this.cacheWriterException);
    }

    @Override  
    public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.processorId = in.readInt();
      this.netWriteSucceeded = in.readBoolean();
      this.e = DataSerializer.readObject(in);
      this.cacheWriterException = in.readBoolean();
    }

    @Override  
    public String toString() {
      return "SearchLoadAndWriteProcessor.NetWriteReplyMessage for processorId " +
      processorId ;
    }
  }
}
