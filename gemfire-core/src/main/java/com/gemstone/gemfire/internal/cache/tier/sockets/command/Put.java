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
/**
 * 
 */
package com.gemstone.gemfire.internal.cache.tier.sockets.command;

import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.tier.CachedRegionHelper;
import com.gemstone.gemfire.internal.cache.tier.Command;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.tier.sockets.*;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.security.AuthorizeRequest;
import com.gemstone.gemfire.security.GemFireSecurityException;
import com.gemstone.gemfire.cache.DynamicRegionFactory;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.ResourceException;
import com.gemstone.gemfire.cache.operations.PutOperationContext;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.org.jgroups.util.StringId;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Put extends BaseCommand {

  private final static Put singleton = new Put();

  public static Command getCommand() {
    return singleton;
  }

  private Put() {
  }

  @Override
  public void cmdExecute(Message msg, ServerConnection servConn, long start)
      throws IOException, InterruptedException {
    Part regionNamePart = null, keyPart = null, valuePart = null, callbackArgPart = null;
    String regionName = null;
    Object callbackArg = null, key = null;
    Part eventPart = null;
    String errMessage = "";
    CachedRegionHelper crHelper = servConn.getCachedRegionHelper();
    CacheServerStats stats = servConn.getCacheServerStats();
    if (crHelper.emulateSlowServer() > 0) {
      // this.logger.fine("SlowServer", new Exception());
      boolean interrupted = Thread.interrupted();
      try {
        Thread.sleep(crHelper.emulateSlowServer());
      }
      catch (InterruptedException ugh) {
        interrupted = true;
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      ;
    }

    // requiresResponse = true;
    servConn.setAsTrue(REQUIRES_RESPONSE);
    {
      long oldStart = start;
      start = DistributionStats.getStatTime();
      stats.incReadPutRequestTime(start - oldStart);
    }
    // Retrieve the data from the message parts
    regionNamePart = msg.getPart(0);
    keyPart = msg.getPart(1);
    valuePart = msg.getPart(2);
    eventPart = msg.getPart(3);
//    callbackArgPart = null; (redundant assignment)
    if (msg.getNumberOfParts() > 4) {
      callbackArgPart = msg.getPart(4);
      try {
        callbackArg = callbackArgPart.getObject();
      }
      catch (Exception e) {
        writeException(msg, e, false, servConn);
        servConn.setAsTrue(RESPONDED);
        return;
      }
    }
    regionName = regionNamePart.getString();

    try {
      key = keyPart.getStringOrObject();
    }
    catch (Exception e) {
      writeException(msg, e, false, servConn);
      servConn.setAsTrue(RESPONDED);
      return;
    }

    if (logger.finestEnabled()) {
      logger.finest(servConn.getName() + ": Received put request ("
          + msg.getPayloadLength() + " bytes) from "
          + servConn.getSocketString() + " for region " + regionName + " key "
          + key + " value " + valuePart);
    }

    // Process the put request
    if (key == null || regionName == null) {
      if (key == null) {
        if (logger.warningEnabled()) {
          logger.warning(
            LocalizedStrings.Put_0_THE_INPUT_KEY_FOR_THE_PUT_REQUEST_IS_NULL,
            servConn.getName());
        }
        errMessage = LocalizedStrings.Put_THE_INPUT_KEY_FOR_THE_PUT_REQUEST_IS_NULL.toLocalizedString();
      }
      if (regionName == null) {
        if (logger.warningEnabled()) {
          logger.warning(
            LocalizedStrings.Put_0_THE_INPUT_REGION_NAME_FOR_THE_PUT_REQUEST_IS_NULL,
            servConn.getName());
        }
        errMessage = LocalizedStrings.Put_THE_INPUT_REGION_NAME_FOR_THE_PUT_REQUEST_IS_NULL.toLocalizedString();
      }
      writeErrorResponse(msg, MessageType.PUT_DATA_ERROR,
          errMessage.toString(), servConn);
      servConn.setAsTrue(RESPONDED);
    }
    else {
      LocalRegion region = (LocalRegion)crHelper.getRegion(regionName);
      if (region == null) {
        String reason = LocalizedStrings.Put_REGION_WAS_NOT_FOUND_DURING_PUT_REQUEST.toLocalizedString();
        writeRegionDestroyedEx(msg, regionName, reason, servConn);
        servConn.setAsTrue(RESPONDED);
      }
      else if (valuePart.isNull() && region.containsKey(key)) {
        // Invalid to 'put' a null value in an existing key
        if (logger.infoEnabled()) {
          logger.info(
            LocalizedStrings.Put_0_ATTEMPTED_TO_PUT_A_NULL_VALUE_FOR_EXISTING_KEY_1,
            new Object[] {servConn.getName(), key});
        }
        errMessage = LocalizedStrings.Put_ATTEMPTED_TO_PUT_A_NULL_VALUE_FOR_EXISTING_KEY_0.toLocalizedString();
        writeErrorResponse(msg, MessageType.PUT_DATA_ERROR, errMessage,
            servConn);
        servConn.setAsTrue(RESPONDED);
      }
      else {
          // try {
        // this.eventId = (EventID)eventPart.getObject();
        ByteBuffer eventIdPartsBuffer = ByteBuffer.wrap(eventPart
            .getSerializedForm());
        long threadId = EventID
            .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);
        long sequenceId = EventID
            .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);
        EventID eventId = new EventID(servConn.getEventMemberIDByteArray(),
            threadId, sequenceId);
        // } catch (Exception e) {
        // writeException(msg, e, false);
        // responded = true;
        // continue;
        // }
        try {
          byte[] value = valuePart.getSerializedForm();
          boolean isObject = valuePart.isObject();
          AuthorizeRequest authzRequest = servConn.getAuthzRequest();
          if (authzRequest != null) {
            // TODO SW: This is to handle DynamicRegionFactory create
            // calls. Rework this when the semantics of DynamicRegionFactory are
            // cleaned up.
            if (DynamicRegionFactory.regionIsDynamicRegionList(regionName)) {
              authzRequest.createRegionAuthorize((String)key);
            }
            // Allow PUT operations on meta regions (bug #38961)
            else if (!region.isUsedForMetaRegion()) {
              PutOperationContext putContext = authzRequest.putAuthorize(
                  regionName, key, value, isObject, callbackArg);
              value = putContext.getSerializedValue();
              isObject = putContext.isObject();
              callbackArg = putContext.getCallbackArg();
            }
          }
          // If the value is 1 byte and the byte represents null,
          // attempt to create the entry. This test needs to be
          // moved to DataSerializer or DataSerializer.NULL needs
          // to be publicly accessible.
          boolean result = false;
          if (value == null) {
            // Create the null entry. Since the value is null, the value of the
            // isObject
            // the true after null doesn't matter and is not used.
            result = region.basicBridgeCreate(key, null, true, callbackArg,
                servConn.getProxyID(), true, new EntryEventImpl(eventId), false);
          }
          else {
            // Put the entry
            result = region.basicBridgePut(key, value, null, isObject, callbackArg,
                servConn.getProxyID(), true,
                servConn.isGemFireXDSystem(), new EntryEventImpl(eventId));
          }
          if (result) {
            servConn.setModificationInfo(true, regionName, key);
          }
          else {
            StringId message = LocalizedStrings.PUT_0_FAILED_TO_PUT_ENTRY_FOR_REGION_1_KEY_2_VALUE_3;
            Object[] messageArgs = new Object[] {servConn.getName(), regionName, key, valuePart};
                
            if (logger.infoEnabled()) {
              logger.info(message, messageArgs);
            }
            throw new Exception(message.toLocalizedString(messageArgs));
          }
        }
        catch (RegionDestroyedException rde) {
          writeException(msg, rde, false, servConn);
          servConn.setAsTrue(RESPONDED);
          return;
        }
        catch (ResourceException re) {
          writeException(msg, re, false, servConn);
          servConn.setAsTrue(RESPONDED);
          return;
        }
        catch (Exception ce) {
          // If an interrupted exception is thrown , rethrow it
          checkForInterrupt(servConn, ce);

          // If an exception occurs during the put, preserve the connection
          writeException(msg, ce, false, servConn);
          servConn.setAsTrue(RESPONDED);
          if (ce instanceof GemFireSecurityException) {
            // Fine logging for security exceptions since these are already
            // logged by the security logger
            if (logger.fineEnabled()) {
              logger.fine(servConn.getName()
                  + ": Unexpected Security exception", ce);
            }
          }
          else if (logger.warningEnabled()) {
            logger.warning(
              LocalizedStrings.PUT_0_UNEXPECTED_EXCEPTION,
              servConn.getName(), ce);
          }
          return;
        }
        finally {
          long oldStart = start;
          start = DistributionStats.getStatTime();
          stats.incProcessPutTime(start - oldStart);
        }

        // Increment statistics and write the reply
        writeReply(msg, servConn);
        
        servConn.setAsTrue(RESPONDED);
        if (logger.fineEnabled()) {
          logger.fine(servConn.getName() + ": Sent put response back to "
              + servConn.getSocketString() + " for region " + regionName
              + " key " + key + " value " + valuePart);
        }
        stats.incWritePutResponseTime(DistributionStats.getStatTime() - start);
      }
    }

  }

}
