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

package com.gemstone.gemfire.cache;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;

/**
 * Indicates a low memory condition either on the local or a remote {@link Cache}.
 * The {@link ResourceManager} monitors local tenured memory consumption and determines when operations are rejected.
 * 
 * @see ResourceManager#setCriticalHeapPercentage(float)
 * @see Region#put(Object, Object)
 * 
 * @author sbawaska
 * @since 6.0
 */
public class LowMemoryException extends ResourceException {

  private static final long serialVersionUID = 6585765466722883168L;
  private final Set<DistributedMember> critMems;

  /**
   * Creates a new instance of <code>LowMemoryException</code>.
   */
  public LowMemoryException() {
    this.critMems = Collections.emptySet();
  }

  /**
   * Constructs an instance of <code>LowMemoryException</code> with the specified detail message.
   * @param msg the detail message
   * @param criticalMembers the member(s) which are/were in a critical state
   */
  public LowMemoryException(String msg, final Set<DistributedMember> criticalMembers) {
    super(msg);
    this.critMems = Collections.unmodifiableSet(criticalMembers);
    CallbackFactoryProvider.getStoreCallbacks().logMemoryStats();
  }

  /**
   * Constructs an instance of <code>LowMemoryException</code> with the specified cause.
   * @param cause
   */
  public LowMemoryException(Throwable cause) {
    super(cause);
    this.critMems = Collections.emptySet();
    CallbackFactoryProvider.getStoreCallbacks().logMemoryStats();
  }

  /**
   * Get a read-only set of members in a critical state at the time this
   * exception was constructed.
   * @return the critical members
   */
  public Set<DistributedMember> getCriticalMembers() {
    return this.critMems;
  }
}
