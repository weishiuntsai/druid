/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.seekablestream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import org.apache.druid.common.config.Configs;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.segment.indexing.IOConfig;
import org.joda.time.DateTime;

import javax.annotation.Nullable;

public abstract class SeekableStreamIndexTaskIOConfig<PartitionIdType, SequenceOffsetType> implements IOConfig
{
  private static final boolean DEFAULT_USE_TRANSACTION = true;

  private final Integer taskGroupId;
  private final String baseSequenceName;
  private final SeekableStreamStartSequenceNumbers<PartitionIdType, SequenceOffsetType> startSequenceNumbers;
  private final SeekableStreamEndSequenceNumbers<PartitionIdType, SequenceOffsetType> endSequenceNumbers;
  private final boolean useTransaction;
  private final DateTime minimumMessageTime;
  private final DateTime maximumMessageTime;
  private final InputFormat inputFormat;
  private final Long refreshRejectionPeriodsInMinutes;

  public SeekableStreamIndexTaskIOConfig(
      @Nullable final Integer taskGroupId, // can be null for backward compabitility
      final String baseSequenceName,
      final SeekableStreamStartSequenceNumbers<PartitionIdType, SequenceOffsetType> startSequenceNumbers,
      final SeekableStreamEndSequenceNumbers<PartitionIdType, SequenceOffsetType> endSequenceNumbers,
      @Nullable final Boolean useTransaction,
      @Nullable final DateTime minimumMessageTime,
      @Nullable final DateTime maximumMessageTime,
      @Nullable final InputFormat inputFormat,
      @Nullable final Long refreshRejectionPeriodsInMinutes // can be null for backward compabitility
  )
  {
    this.taskGroupId = taskGroupId;
    this.baseSequenceName = Preconditions.checkNotNull(baseSequenceName, "baseSequenceName");
    this.startSequenceNumbers = Preconditions.checkNotNull(startSequenceNumbers, "startSequenceNumbers");
    this.endSequenceNumbers = Preconditions.checkNotNull(endSequenceNumbers, "endSequenceNumbers");
    this.useTransaction = Configs.valueOrDefault(useTransaction, DEFAULT_USE_TRANSACTION);
    this.minimumMessageTime = minimumMessageTime;
    this.maximumMessageTime = maximumMessageTime;
    this.inputFormat = inputFormat;
    this.refreshRejectionPeriodsInMinutes = refreshRejectionPeriodsInMinutes;

    Preconditions.checkArgument(
        startSequenceNumbers.getStream().equals(endSequenceNumbers.getStream()),
        "start topic/stream and end topic/stream must match"
    );

    Preconditions.checkArgument(
        startSequenceNumbers.getPartitionSequenceNumberMap()
                            .keySet()
                            .equals(endSequenceNumbers.getPartitionSequenceNumberMap().keySet()),
        "start partition set and end partition set must match"
    );
  }

  @Nullable
  @JsonProperty
  public Integer getTaskGroupId()
  {
    return taskGroupId;
  }

  @JsonProperty
  public String getBaseSequenceName()
  {
    return baseSequenceName;
  }

  @JsonProperty
  public SeekableStreamStartSequenceNumbers<PartitionIdType, SequenceOffsetType> getStartSequenceNumbers()
  {
    return startSequenceNumbers;
  }

  @JsonProperty
  public SeekableStreamEndSequenceNumbers<PartitionIdType, SequenceOffsetType> getEndSequenceNumbers()
  {
    return endSequenceNumbers;
  }

  @JsonProperty
  public boolean isUseTransaction()
  {
    return useTransaction;
  }

  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public DateTime getMaximumMessageTime()
  {
    return maximumMessageTime;
  }

  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public DateTime getMinimumMessageTime()
  {
    return minimumMessageTime;
  }

  @Nullable
  @JsonProperty("inputFormat")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private InputFormat getGivenInputFormat()
  {
    return inputFormat;
  }

  @Nullable
  public InputFormat getInputFormat()
  {
    return inputFormat;
  }

  @Nullable
  @JsonProperty
  public Long getRefreshRejectionPeriodsInMinutes()
  {
    return refreshRejectionPeriodsInMinutes;
  }
}
