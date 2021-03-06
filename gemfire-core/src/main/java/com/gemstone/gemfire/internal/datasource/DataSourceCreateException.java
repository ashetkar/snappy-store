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
package com.gemstone.gemfire.internal.datasource;

import com.gemstone.gemfire.GemFireCheckedException;

/**
 * Exception thrown from DataSource factory.
 * 
 * @author tnegi
 */
public class DataSourceCreateException extends GemFireCheckedException  {
private static final long serialVersionUID = 8759147832954825309L;

  public Exception excep;

  /** Creates a new instance of CreateConnectionException */
  public DataSourceCreateException() {
    super();
  }

  /**
   * @param message
   */
  public DataSourceCreateException(String message) {
    super(message);
  }

  /**
   * Single Argument constructor to construct a new exception with the specified
   * detail message. Calls Exception class constructor.
   * 
   * @param message The detail message. The detail message is saved for later
   *          retrieval.
   */
  public DataSourceCreateException(String message, Exception ex) {
    super(message);
    this.excep = ex;
  }

  /**
   * @return ???
   */
  @Override
  public StackTraceElement[] getStackTrace() {
    return excep.getStackTrace();
  }
}
