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
package com.gemstone.gemfire.internal.process;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.AbstractLauncher.ServiceState;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.PureLogWriter;
import com.gemstone.gemfire.internal.process.ControlFileWatchdog.ControlRequestHandler;

/**
 * Exists inside a process launched by ServerLauncher or LocatorLauncher. 
 * Creates the PID file and ControlFileWatchdogs to monitor working directory
 * for creation of stop or status request files.
 * 
 * @author Kirk Lund
 * @since 8.0
 */
public final class ControllableProcess {
  
  private final File workingDir;
  private final File pidFile;
  private final LocalProcessLauncher launcher;
  private final ControlFileWatchdog stopRequestFileWatchdog;
  private final ControlFileWatchdog statusRequestFileWatchdog;
  
  public ControllableProcess(final ControlNotificationHandler handler, final File workingDir, final ProcessType processType, boolean force) throws FileAlreadyExistsException, IOException, PidUnavailableException {
    this.workingDir = workingDir;
    this.pidFile = new File(this.workingDir, processType.getPidFileName());
    
    deleteFiles(this.workingDir, processType);
    
    this.launcher = new LocalProcessLauncher(this.pidFile, force);
    
    final ControlRequestHandler stopHandler = new ControlRequestHandler() {
      @Override
      public void handleRequest() {
        handler.handleStop();
      }
    };
    final ControlRequestHandler statusHandler = new ControlRequestHandler() {
      @Override
      public void handleRequest() throws IOException {
        final ServiceState<?> state = handler.handleStatus();
        final File statusFile = new File(workingDir, processType.getStatusFileName());
        if (statusFile.exists()) {
          statusFile.delete();
        }
        boolean created = statusFile.createNewFile();
        assert created;
        final FileWriter writer = new FileWriter(statusFile);
        writer.write(state.toJson());
        writer.flush();
        writer.close();
      }
    };
    
    this.stopRequestFileWatchdog = new ControlFileWatchdog(workingDir, processType.getStopRequestFileName(), stopHandler, false);
    this.stopRequestFileWatchdog.start();
    this.statusRequestFileWatchdog = new ControlFileWatchdog(workingDir, processType.getStatusRequestFileName(), statusHandler, false);
    this.statusRequestFileWatchdog.start();
  }
  
  /**
   * Returns the process id (PID).
   * 
   * @return the process id (PID)
   */
  public int getPid() {
    return this.launcher.getPid();
  }
  
  /**
   * Returns the PID file.
   * 
   * @return the PID file
   */
  public File getPidFile() {
    return this.launcher.getPidFile();
  }

  public void stop() {
    try {
      this.statusRequestFileWatchdog.stop();
    } catch (InterruptedException e) {
      getOrCreateLogWriter().warning("Interrupted while stopping status handler for controllable process.", e);
    } finally {
      try {
        this.stopRequestFileWatchdog.stop();
      } catch (InterruptedException e) {
        getOrCreateLogWriter().warning("Interrupted while stopping stop handler for controllable process.", e);
      }
      this.launcher.close();
    }
  }
  
  protected File getWorkingDir() {
    return this.workingDir;
  }
  
  public static LogWriter getOrCreateLogWriter() {
    final InternalDistributedSystem ids = InternalDistributedSystem.getAnyInstance();
    if (ids == null || ids.getLogWriter() == null) {
      return new PureLogWriter(LogWriterImpl.levelNameToCode("config"));
    } else {
      return ids.getLogWriter();
    }
  }
  
  private static void deleteFiles(final File workingDir, final ProcessType processType) {
    deleteFile(workingDir, processType.getStatusRequestFileName());
    deleteFile(workingDir, processType.getStatusFileName());
    deleteFile(workingDir, processType.getStopRequestFileName());
  }
  
  private static void deleteFile(final File workingDir, final String fileName) {
    final File file = new File(workingDir, fileName);
    if (file.exists()) {
      file.delete();
    }
  }
}
