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

/** Thrown when a cache transaction fails to register with the
 * <code>UserTransaction</code> (aka JTA transaction), most likely the
 * cause of the <code>UserTransaction</code>'s
 * <code>javax.transaction.Status#STATUS_MARKED_ROLLBACK</code>
 * status.
 *
 * @author Mitch Thomas
 *
 * @see javax.transaction.UserTransaction#setRollbackOnly
 * @see javax.transaction.Transaction#registerSynchronization
 * @see javax.transaction.Status
 * @since 4.0
 */
public class FailedSynchronizationException extends CacheRuntimeException {
private static final long serialVersionUID = -6225053492344591496L;
  /**
   * Constructs an instance of
   * <code>FailedSynchronizationException</code> with the
   * specified detail message.
   * @param msg the detail message
   */
  public FailedSynchronizationException(String msg) {
    super(msg);
  }
  
  /**
   * Constructs an instance of
   * <code>FailedSynchronizationException</code> with the
   * specified detail message and cause.
   * @param msg the detail message
   * @param cause the causal Throwable
   */
  public FailedSynchronizationException(String msg, Throwable cause) {
    super(msg, cause);
  }
}
