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

package org.apache.druid.server.coordination;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.java.util.common.StringUtils;

/**
 * This enum represents types of druid services that hold segments.
 * <p>
 * These types are externally visible (e.g., from the output of {@link
 * org.apache.druid.server.http.ServersResource#makeSimpleServer}).
 * <p>
 * For backwards compatibility, when presenting these types externally, the toString() representation
 * of the enum should be used.
 * <p>
 * The toString() method converts the enum name() to lowercase and replaces underscores with hyphens,
 * which is the format expected for the server type string prior to the patch that introduced ServerType:
 * https://github.com/apache/druid/pull/4148
 *
 * This is a historical occasion that this enum is different from {@link NodeRole} because
 * they are essentially the same abstraction, but merging them could only increase the complexity and drop the code
 * safety, because they name the same types differently ("indexer-executor" - "peon" and "realtime" - "middleManager")
 * and both expose them via JSON APIs.
 *
 * These abstractions can probably be merged when Druid updates to Jackson 2.9 that supports JsonAliases, see
 * see https://github.com/apache/druid/issues/7152.
 */
public enum ServerType
{
  HISTORICAL {
    @Override
    public boolean isSegmentReplicationTarget()
    {
      return true;
    }

    @Override
    public boolean isSegmentServer()
    {
      return true;
    }
  },

  BRIDGE {
    @Override
    public boolean isSegmentReplicationTarget()
    {
      return true;
    }

    @Override
    public boolean isSegmentServer()
    {
      return true;
    }
  },

  INDEXER_EXECUTOR {
    @Override
    public boolean isSegmentReplicationTarget()
    {
      return false;
    }

    @Override
    public boolean isSegmentServer()
    {
      return true;
    }
  },

  REALTIME {
    @Override
    public boolean isSegmentReplicationTarget()
    {
      return false;
    }

    @Override
    public boolean isSegmentServer()
    {
      return true;
    }
  },

  BROKER {
    @Override
    public boolean isSegmentReplicationTarget()
    {
      return false;
    }

    @Override
    public boolean isSegmentServer()
    {
      return false;
    }
  };

  /**
   * Indicates this type of node is able to be a target of segment replication.
   *
   * @return true if it is available for replication
   *
   * @see org.apache.druid.server.coordinator.rules.LoadRule
   */
  public abstract boolean isSegmentReplicationTarget();

  /**
   * Indicates this type of node is able to be a target of segment broadcast.
   *
   * @return true if it is available for broadcast.
   */
  public boolean isSegmentBroadcastTarget()
  {
    return true;
  }

  /**
   * Indicates this type of node is serving segments that are meant to be the target of fan-out by a Broker.
   *
   * Nodes that return "true" here are often referred to as "data servers" or "data server processes".
   */
  public abstract boolean isSegmentServer();

  @JsonCreator
  public static ServerType fromString(String type)
  {
    return ServerType.valueOf(StringUtils.toUpperCase(type).replace('-', '_'));
  }

  public static ServerType fromNodeRole(NodeRole nodeRole)
  {
    // this doesn't actually check that the NodeRole is a typical data node
    if (nodeRole.equals(NodeRole.HISTORICAL)) {
      return HISTORICAL;
    } else if (nodeRole.equals(NodeRole.BROKER)) {
      return BROKER;
    } else {
      return INDEXER_EXECUTOR;
    }
  }

  @Override
  @JsonValue
  public String toString()
  {
    return StringUtils.toLowerCase(name()).replace('_', '-');
  }
}
