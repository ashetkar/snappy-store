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
package com.gemstone.gemfire.management.internal.cli.commands;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.shell.core.annotation.CliAvailabilityIndicator;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.Execution;
import com.gemstone.gemfire.cache.execute.FunctionInvocationTargetException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.execute.AbstractExecution;
import com.gemstone.gemfire.internal.lang.StringUtils;
import com.gemstone.gemfire.management.cli.CliMetaData;
import com.gemstone.gemfire.management.cli.ConverterHint;
import com.gemstone.gemfire.management.cli.Result;
import com.gemstone.gemfire.management.internal.cli.CliUtil;
import com.gemstone.gemfire.management.internal.cli.domain.IndexDetails;
import com.gemstone.gemfire.management.internal.cli.domain.IndexDetails.IndexStatisticsDetails;
import com.gemstone.gemfire.management.internal.cli.domain.IndexInfo;
import com.gemstone.gemfire.management.internal.cli.functions.CliFunctionResult;
import com.gemstone.gemfire.management.internal.cli.functions.CreateIndexFunction;
import com.gemstone.gemfire.management.internal.cli.functions.DestroyIndexFunction;
import com.gemstone.gemfire.management.internal.cli.functions.ListIndexFunction;
import com.gemstone.gemfire.management.internal.cli.i18n.CliStrings;
import com.gemstone.gemfire.management.internal.cli.result.CommandResultException;
import com.gemstone.gemfire.management.internal.cli.result.ErrorResultData;
import com.gemstone.gemfire.management.internal.cli.result.InfoResultData;
import com.gemstone.gemfire.management.internal.cli.result.ResultBuilder;
import com.gemstone.gemfire.management.internal.cli.result.TabularResultData;

/**
 * The IndexCommands class encapsulates all GemFire shell (Gfsh) commands related to indexes defined in GemFire.
 * </p>
 * @author John Blum
 * @see com.gemstone.gemfire.management.internal.cli.commands.AbstractCommandsSupport
 * @see com.gemstone.gemfire.management.internal.cli.domain.IndexDetails
 * @see com.gemstone.gemfire.management.internal.cli.functions.ListIndexFunction
 * @since 7.0
 */
@SuppressWarnings("unused")
public class IndexCommands extends AbstractCommandsSupport {
  
  private static final CreateIndexFunction createIndexFunction = new CreateIndexFunction();
  private static final DestroyIndexFunction destroyIndexFunction = new DestroyIndexFunction();
  @Override
  protected Set<DistributedMember> getMembers(final Cache cache) {
    // TODO determine what this does (as it is untested and unmockable!)
    return CliUtil.getAllMembers(cache);
  }

  @CliCommand(value = CliStrings.LIST_INDEX, help = CliStrings.LIST_INDEX__HELP)
  @CliMetaData(shellOnly = false, relatedTopic={CliStrings.TOPIC_GEMFIRE_REGION, CliStrings.TOPIC_GEMFIRE_DATA})
  public Result listIndex(@CliOption(key = CliStrings.LIST_INDEX__STATS,
                                     mandatory = false,
                                     specifiedDefaultValue = "true",
                                     unspecifiedDefaultValue = "false",
                                     help = CliStrings.LIST_INDEX__STATS__HELP)
                            final boolean showStats) {
    try {
      return toTabularResult(getIndexListing(), showStats);
    }
    catch (FunctionInvocationTargetException ignore) {
      return ResultBuilder.createGemFireErrorResult(CliStrings.format(CliStrings.COULD_NOT_EXECUTE_COMMAND_TRY_AGAIN,
        CliStrings.LIST_INDEX));
    }
    catch (VirtualMachineError e) {
      SystemFailure.initiateFailure(e);
      throw e;
    }
    catch (Throwable t) {
      SystemFailure.checkFailure();
      getCache().getLogger().error(t);
      return ResultBuilder.createGemFireErrorResult(String.format(CliStrings.LIST_INDEX__ERROR_MESSAGE,
        toString(t, isDebugging())));
    }
  }

  @SuppressWarnings("unchecked")
  protected List<IndexDetails> getIndexListing() {
    final Execution functionExecutor = getMembersFunctionExecutor(getMembers(getCache()));

    if (functionExecutor instanceof AbstractExecution) {
      ((AbstractExecution) functionExecutor).setIgnoreDepartedMembers(true);
    }

    final ResultCollector<?, ?> resultsCollector = functionExecutor.execute(new ListIndexFunction());

    final List<?> results = (List<?>) resultsCollector.getResult();
    final List<IndexDetails> indexDetailsList = new ArrayList<IndexDetails>(results.size());

    for (Object result : results) {
      if (result instanceof Set) { // ignore FunctionInvocationTargetExceptions and other Exceptions
        indexDetailsList.addAll((Set<IndexDetails>) result);
      }
    }

    Collections.sort(indexDetailsList);

    return indexDetailsList;
  }

  protected Result toTabularResult(final List<IndexDetails> indexDetailsList, final boolean showStats) {
    if (!indexDetailsList.isEmpty()) {
      final TabularResultData indexData = ResultBuilder.createTabularResultData();

      for (final IndexDetails indexDetails : indexDetailsList) {
        indexData.accumulate("Member Name", StringUtils.valueOf(indexDetails.getMemberName(), ""));
        indexData.accumulate("Member ID", indexDetails.getMemberId());
        indexData.accumulate("Region Path", indexDetails.getRegionPath());
        indexData.accumulate("Name", indexDetails.getIndexName());
        indexData.accumulate("Type", StringUtils.valueOf(indexDetails.getIndexType(), ""));
        indexData.accumulate("Indexed Expression", indexDetails.getIndexedExpression());
        indexData.accumulate("From Clause", indexDetails.getFromClause());

        if (showStats) {
          final IndexStatisticsDetailsAdapter adapter = new IndexStatisticsDetailsAdapter(
            indexDetails.getIndexStatisticsDetails());

          indexData.accumulate("Uses", adapter.getTotalUses());
          indexData.accumulate("Updates", adapter.getNumberOfUpdates());
          indexData.accumulate("Update Time", adapter.getTotalUpdateTime());
          indexData.accumulate("Keys", adapter.getNumberOfKeys());
          indexData.accumulate("Values", adapter.getNumberOfValues());
        }
      }

      return ResultBuilder.buildResult(indexData);
    }
    else {
      return ResultBuilder.createInfoResult(CliStrings.LIST_INDEX__INDEXES_NOT_FOUND_MESSAGE);
    }
  }

  @CliCommand(value = CliStrings.CREATE_INDEX, help = CliStrings.CREATE_INDEX__HELP)
  @CliMetaData(shellOnly = false, relatedTopic={CliStrings.TOPIC_GEMFIRE_REGION, CliStrings.TOPIC_GEMFIRE_DATA})
  //TODO : Add optionContext for indexName
  public Result createIndex(
      @CliOption (key = CliStrings.CREATE_INDEX__NAME,
      mandatory=true,
      help = CliStrings.CREATE_INDEX__NAME__HELP) final String indexName,

      @CliOption (key = CliStrings.CREATE_INDEX__EXPRESSION,
      mandatory = true,
      help = CliStrings.CREATE_INDEX__EXPRESSION__HELP) final String indexedExpression,

      @CliOption(key = CliStrings.CREATE_INDEX__REGION,
      mandatory = true,
      optionContext = ConverterHint.REGIONPATH,
      help = CliStrings.CREATE_INDEX__REGION__HELP) String regionPath,

      @CliOption(key = CliStrings.CREATE_INDEX__MEMBER,
      mandatory = false,
      optionContext = ConverterHint.MEMBERIDNAME,
      help = CliStrings.CREATE_INDEX__MEMBER__HELP) final String memberNameOrID,

      @CliOption(key = CliStrings.CREATE_INDEX__TYPE,
      mandatory = false,
      unspecifiedDefaultValue = "range",
      optionContext = ConverterHint.INDEX_TYPE,
      help = CliStrings.CREATE_INDEX__TYPE__HELP) final String indexType,

      @CliOption (key = CliStrings.CREATE_INDEX__GROUP,
      mandatory = false,
      optionContext = ConverterHint.MEMBERGROUP, 
      help = CliStrings.CREATE_INDEX__GROUP__HELP) final String group) {

    Result result = null;
    try {
      final Cache cache = CacheFactory.getAnyInstance();

      int idxType = IndexInfo.RANGE_INDEX;

      //Index type check
        if ("range".equalsIgnoreCase(indexType)) {
          idxType = IndexInfo.RANGE_INDEX;
        } else if ("hash".equalsIgnoreCase(indexType)) {
          idxType = IndexInfo.HASH_INDEX;
        } else if ("key".equalsIgnoreCase(indexType)) {
          idxType = IndexInfo.KEY_INDEX;
        } else {
          return ResultBuilder.createUserErrorResult(CliStrings.CREATE_INDEX__INVALID__INDEX__TYPE__MESSAGE);
        }

      if (indexName == null || indexName.isEmpty()) {
        return ResultBuilder.createUserErrorResult(CliStrings.CREATE_INDEX__INVALID__INDEX__NAME);
      }

      if (indexedExpression == null || indexedExpression.isEmpty()) {
        return ResultBuilder.createUserErrorResult(CliStrings.CREATE_INDEX__INVALID__EXPRESSION);
      }

      if (StringUtils.isBlank(regionPath) || regionPath.equals(Region.SEPARATOR)) {
        return ResultBuilder.createUserErrorResult(CliStrings.CREATE_INDEX__INVALID__REGIONPATH);
      }
      
      if (!regionPath.startsWith(Region.SEPARATOR)) {
        regionPath = Region.SEPARATOR +regionPath;
      }
      
      IndexInfo indexInfo = new IndexInfo(indexName, indexedExpression, regionPath, idxType);

      final Set<DistributedMember> targetMembers = CliUtil.findAllMatchingMembers(group, memberNameOrID);
      final ResultCollector<?,?> rc = CliUtil.executeFunction(createIndexFunction, indexInfo, targetMembers);

      final List<Object> funcResults = (List<Object>) rc.getResult();
      final Set<String> successfulMembers = new TreeSet<String>();
      final Map<String, Set<String>> indexOpFailMap = new HashMap<String, Set<String>>();

      for (final Object funcResult : funcResults) {
        if (funcResult instanceof CliFunctionResult) {
          final CliFunctionResult cliFunctionResult = (CliFunctionResult) funcResult;

          if (cliFunctionResult.isSuccessful()) {
            successfulMembers.add(cliFunctionResult.getMemberIdOrName());
          } else {
            final String exceptionMessage  = cliFunctionResult.getMessage();
            Set<String> failedMembers = indexOpFailMap.get(exceptionMessage);

            if (failedMembers == null) {
              failedMembers = new TreeSet<String>();
            }
            failedMembers.add(cliFunctionResult.getMemberIdOrName());
            indexOpFailMap.put(exceptionMessage, failedMembers);
          }
        }
      }

      if (!successfulMembers.isEmpty()) {

        final InfoResultData infoResult = ResultBuilder.createInfoResultData();
        infoResult.addLine(CliStrings.CREATE_INDEX__SUCCESS__MSG);
        infoResult.addLine(CliStrings.format(CliStrings.CREATE_INDEX__NAME__MSG, indexName));
        infoResult.addLine(CliStrings.format(CliStrings.CREATE_INDEX__EXPRESSION__MSG, indexedExpression));
        infoResult.addLine(CliStrings.format(CliStrings.CREATE_INDEX__REGIONPATH__MSG, regionPath));
        infoResult.addLine(CliStrings.CREATE_INDEX__MEMBER__MSG);

        int num = 0;

        for (final String memberId : successfulMembers) {
          ++num;
          infoResult.addLine(CliStrings.format(CliStrings.CREATE_INDEX__NUMBER__AND__MEMBER, num, memberId));
        }
        result = ResultBuilder.buildResult(infoResult);

      } else {
        //Group members by the exception thrown.
        final ErrorResultData erd = ResultBuilder.createErrorResultData();
        erd.addLine(CliStrings.format(CliStrings.CREATE_INDEX__FAILURE__MSG, indexName));

        final Set<String> exceptionMessages = indexOpFailMap.keySet();

        for (final String exceptionMessage : exceptionMessages) {
          erd.addLine(exceptionMessage);
          erd.addLine(CliStrings.CREATE_INDEX__EXCEPTION__OCCURRED__ON);
          final Set<String> memberIds = indexOpFailMap.get(exceptionMessage);

          int num = 0;
          for (final String memberId : memberIds) {
            ++num;
            erd.addLine(CliStrings.format(CliStrings.CREATE_INDEX__NUMBER__AND__MEMBER, num, memberId));
          }
        }
        result = ResultBuilder.buildResult(erd);
      }
    } catch (CommandResultException crex) {
      result = crex.getResult();
    } catch (Exception e) {
      result = ResultBuilder.createGemFireErrorResult(e.getMessage());
    }
    return result;
  }

  @CliCommand(value = CliStrings.DESTROY_INDEX, help = CliStrings.DESTROY_INDEX__HELP)
  @CliMetaData(shellOnly = false, relatedTopic={CliStrings.TOPIC_GEMFIRE_REGION, CliStrings.TOPIC_GEMFIRE_DATA})
  //TODO : Add optioncontext for the index name. 
  public Result destroyIndex(
      @CliOption(
      key = CliStrings.DESTROY_INDEX__NAME,
      mandatory = false,
      unspecifiedDefaultValue = "",
      help = CliStrings.DESTROY_INDEX__NAME__HELP) final String indexName,

      @CliOption(key = CliStrings.DESTROY_INDEX__REGION,
      mandatory = false,
      optionContext = ConverterHint.REGIONPATH,
      help = CliStrings.DESTROY_INDEX__REGION__HELP) final String regionName,

      @CliOption(key = CliStrings.DESTROY_INDEX__MEMBER,
      mandatory = false,
      optionContext = ConverterHint.MEMBERIDNAME,
      help = CliStrings.DESTROY_INDEX__MEMBER__HELP) final String memberNameOrID,

      @CliOption (key = CliStrings.DESTROY_INDEX__GROUP,
      mandatory = false,
      optionContext = ConverterHint.MEMBERGROUP,
      help = CliStrings.DESTROY_INDEX__GROUP__HELP) final String group) {

    Result result = null;

    try {

      if (StringUtils.isBlank(indexName)
          && StringUtils.isBlank(regionName)
          && StringUtils.isBlank(memberNameOrID)
          && StringUtils.isBlank(group)) {
        return ResultBuilder.createUserErrorResult(CliStrings.format(CliStrings.PROVIDE_ATLEAST_ONE_OPTION, CliStrings.DESTROY_INDEX));
      }

      final Cache cache = CacheFactory.getAnyInstance();
      IndexInfo indexInfo = new IndexInfo(indexName, regionName);

      Set<DistributedMember> targetMembers = CliUtil.findAllMatchingMembers(group, memberNameOrID);

      ResultCollector rc = CliUtil.executeFunction(destroyIndexFunction, indexInfo, targetMembers);
      List<Object> funcResults = (List<Object>) rc.getResult();

      Set<String> successfulMembers = new TreeSet<String>();
      Map<String, Set<String>> indexOpFailMap = new HashMap<String, Set<String>>();

      for (Object funcResult : funcResults){

        if (funcResult instanceof CliFunctionResult) {
          CliFunctionResult cliFunctionResult = (CliFunctionResult) funcResult;

          if (cliFunctionResult.isSuccessful()) {
            successfulMembers.add(cliFunctionResult.getMemberIdOrName());
          } else {
            String exceptionMessage  = cliFunctionResult.getMessage();
            Set<String> failedMembers = indexOpFailMap.get(exceptionMessage);

            if (failedMembers == null) {
              failedMembers = new TreeSet<String>();
            }
            failedMembers.add(cliFunctionResult.getMemberIdOrName());
            indexOpFailMap.put(exceptionMessage, failedMembers);
          }
        }
      }

      if (!successfulMembers.isEmpty()) {
        InfoResultData infoResult = ResultBuilder.createInfoResultData();

        if (!StringUtils.isBlank(indexName)) {
          if (!StringUtils.isBlank(regionName)) {
            infoResult.addLine(CliStrings.format(CliStrings.DESTROY_INDEX__ON__REGION__SUCCESS__MSG, indexName, regionName));
          } else {
            infoResult.addLine(CliStrings.format(CliStrings.DESTROY_INDEX__SUCCESS__MSG, indexName));
          }
        } else {
           if (!StringUtils.isBlank(regionName)) {
             infoResult.addLine(CliStrings.format(CliStrings.DESTROY_INDEX__ON__REGION__ONLY__SUCCESS__MSG, regionName));
           } else {
             infoResult.addLine(CliStrings.DESTROY_INDEX__ON__MEMBERS__ONLY__SUCCESS__MSG);
           }
        }

        int num = 0;
        for (String memberId : successfulMembers) {
          infoResult.addLine(CliStrings.format(CliStrings.format(CliStrings.DESTROY_INDEX__NUMBER__AND__MEMBER, ++num, memberId)));;
        }
        result = ResultBuilder.buildResult(infoResult);

      } else {

        ErrorResultData erd = ResultBuilder.createErrorResultData();
        if (!StringUtils.isBlank(indexName)) {
          erd.addLine(CliStrings.format(CliStrings.DESTROY_INDEX__FAILURE__MSG, indexName));
        } else {
          erd.addLine("Indexes could not be destroyed for following reasons");
        }

        Set<String> exceptionMessages = indexOpFailMap.keySet();

        for (String exceptionMessage : exceptionMessages) {
          erd.addLine(CliStrings.format(CliStrings.DESTROY_INDEX__REASON_MESSAGE, exceptionMessage));
          erd.addLine(CliStrings.DESTROY_INDEX__EXCEPTION__OCCURRED__ON);

          Set<String> memberIds = indexOpFailMap.get(exceptionMessage);
          int num = 0;

          for (String memberId : memberIds) {
            erd.addLine(CliStrings.format(CliStrings.format(CliStrings.DESTROY_INDEX__NUMBER__AND__MEMBER, ++num, memberId)));
          }
          erd.addLine("");
        }
        result = ResultBuilder.buildResult(erd);
      }
    } catch (CommandResultException crex) {
      result = crex.getResult();
    } catch (Exception e) {
      result = ResultBuilder.createGemFireErrorResult(e.getMessage());
    }
    return result;
  }

  @CliAvailabilityIndicator({CliStrings.LIST_INDEX, CliStrings.CREATE_INDEX, CliStrings.DESTROY_INDEX})
  public boolean indexCommandsAvailable() {
    return (!CliUtil.isGfshVM() || (getGfsh() != null && getGfsh().isConnectedAndReady()));
  }

  protected static class IndexStatisticsDetailsAdapter {

    private final IndexStatisticsDetails indexStatisticsDetails;

    protected IndexStatisticsDetailsAdapter(final IndexStatisticsDetails indexStatisticsDetails) {
      this.indexStatisticsDetails = indexStatisticsDetails;
    }

    public IndexStatisticsDetails getIndexStatisticsDetails() {
      return indexStatisticsDetails;
    }

    public String getNumberOfKeys() {
      return (getIndexStatisticsDetails() != null ? StringUtils.valueOf(getIndexStatisticsDetails()
        .getNumberOfKeys(), "") : "");
    }

    public String getNumberOfUpdates() {
      return (getIndexStatisticsDetails() != null ? StringUtils.valueOf(getIndexStatisticsDetails().getNumberOfUpdates(), "") : "");
    }

    public String getNumberOfValues() {
      return (getIndexStatisticsDetails() != null ? StringUtils.valueOf(getIndexStatisticsDetails().getNumberOfValues(), "") : "");
    }

    public String getTotalUpdateTime() {
      return (getIndexStatisticsDetails() != null ? StringUtils.valueOf(getIndexStatisticsDetails().getTotalUpdateTime(), "") : "");
    }

    public String getTotalUses() {
      return (getIndexStatisticsDetails() != null ? StringUtils.valueOf(getIndexStatisticsDetails().getTotalUses(), "") : "");
    }
  }

}
