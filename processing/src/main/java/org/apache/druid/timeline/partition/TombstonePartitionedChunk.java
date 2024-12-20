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

package org.apache.druid.timeline.partition;

import com.google.common.base.Objects;

public class TombstonePartitionedChunk<T> implements PartitionChunk<T>
{
  private final T object;

  public static <T> TombstonePartitionedChunk<T> make(T obj)
  {
    return new TombstonePartitionedChunk<>(obj);
  }

  public TombstonePartitionedChunk(T object)
  {
    this.object = object;
  }

  @Override
  public T getObject()
  {
    return object;
  }

  @Override
  public boolean abuts(final PartitionChunk<T> other)
  {
    return false;
  }

  @Override
  public boolean isStart()
  {
    return true;
  }

  @Override
  public boolean isEnd()
  {
    return true;
  }

  @Override
  public int getChunkNumber()
  {
    return 0;
  }

  @Override
  public int compareTo(PartitionChunk<T> other)
  {
    if (other instanceof TombstonePartitionedChunk) {
      return 0;
    } else {
      throw new IllegalArgumentException("Cannot compare against something that is not a TombstonePartitionedChunk.");
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    return compareTo((TombstonePartitionedChunk<T>) o) == 0;
  }

  @Override
  public int hashCode()
  {
    return Objects.hashCode(0);
  }

  @Override
  public String toString()
  {
    return "TombstonePartitionedChunk{" +
           "chunkNumber=" + 0 +
           ", chunks=" + 1 +
           ", object=" + object +
           '}';
  }
}
