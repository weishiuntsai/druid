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

package org.apache.druid.data.input.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.segment.DimensionHandler;
import org.apache.druid.segment.FloatDimensionHandler;
import org.apache.druid.segment.column.ColumnType;

public class FloatDimensionSchema extends DimensionSchema
{
  @JsonCreator
  public FloatDimensionSchema(
      @JsonProperty("name") String name
  )
  {
    super(name, null, false);
  }

  @Override
  public String getTypeName()
  {
    return FLOAT_TYPE_NAME;
  }

  @Override
  @JsonIgnore
  public ColumnType getColumnType()
  {
    return ColumnType.FLOAT;
  }

  @Override
  public DimensionHandler getDimensionHandler()
  {
    return new FloatDimensionHandler(getName());
  }
}
