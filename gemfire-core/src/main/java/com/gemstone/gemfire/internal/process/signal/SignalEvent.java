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

package com.gemstone.gemfire.internal.process.signal;

import java.util.EventObject;

/**
 * The SignalEvent class...
 * </p>
 * @author John Blum
 * @see java.util.EventObject
 * @since 7.0
 */
@SuppressWarnings("unused")
public class SignalEvent extends EventObject {

  private final Signal signal;

  public SignalEvent(final Object source, final Signal signal) {
    super(source);
    assert signal != null : "The Signal generating this event cannot be null!";
    this.signal = signal;
  }

  public Signal getSignal() {
    return this.signal;
  }

  @Override
  public String toString() {
    final StringBuilder buffer = new StringBuilder(getClass().getSimpleName());
    buffer.append("{ signal = ").append(getSignal());
    buffer.append(", source = ").append(getSource());
    buffer.append("}");
    return buffer.toString();
  }

}
