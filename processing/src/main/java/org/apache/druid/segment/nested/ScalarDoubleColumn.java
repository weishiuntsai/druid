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

package org.apache.druid.segment.nested;

import org.apache.druid.collections.bitmap.BitmapFactory;
import org.apache.druid.collections.bitmap.ImmutableBitmap;
import org.apache.druid.common.semantic.SemanticUtils;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.DoubleColumnSelector;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.data.ColumnarDoubles;
import org.apache.druid.segment.data.ColumnarInts;
import org.apache.druid.segment.data.FixedIndexed;
import org.apache.druid.segment.data.Indexed;
import org.apache.druid.segment.data.ReadableOffset;
import org.apache.druid.segment.vector.BaseDoubleVectorValueSelector;
import org.apache.druid.segment.vector.ReadableVectorInspector;
import org.apache.druid.segment.vector.ReadableVectorOffset;
import org.apache.druid.segment.vector.VectorSelectorUtils;
import org.apache.druid.segment.vector.VectorValueSelector;
import org.roaringbitmap.PeekableIntIterator;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link NestedCommonFormatColumn} for {@link ColumnType#DOUBLE}
 */
public class ScalarDoubleColumn implements NestedCommonFormatColumn
{
  private static final Map<Class<?>, Function<ScalarDoubleColumn, ?>> AS_MAP =
      SemanticUtils.makeAsMap(ScalarDoubleColumn.class);

  private final FixedIndexed<Double> doubleDictionary;
  private final Supplier<ColumnarInts> encodedValuesSupplier;
  private final ColumnarDoubles valueColumn;
  private final ImmutableBitmap nullValueIndex;
  private final BitmapFactory bitmapFactory;

  public ScalarDoubleColumn(
      FixedIndexed<Double> doubleDictionary,
      Supplier<ColumnarInts> encodedValuesSupplier,
      ColumnarDoubles valueColumn,
      ImmutableBitmap nullValueIndex,
      BitmapFactory bitmapFactory
  )
  {
    this.doubleDictionary = doubleDictionary;
    this.encodedValuesSupplier = encodedValuesSupplier;
    this.valueColumn = valueColumn;
    this.nullValueIndex = nullValueIndex;
    this.bitmapFactory = bitmapFactory;
  }

  @Override
  public Indexed<Double> getDoubleDictionary()
  {
    return doubleDictionary;
  }

  @Override
  public ColumnType getLogicalType()
  {
    return ColumnType.DOUBLE;
  }

  @Override
  public ColumnValueSelector<?> makeColumnValueSelector(ReadableOffset offset)
  {
    return new DoubleColumnSelector()
    {
      private PeekableIntIterator nullIterator = nullValueIndex.peekableIterator();
      private int nullMark = -1;
      private int offsetMark = -1;

      @Override
      public double getDouble()
      {
        return valueColumn.get(offset.getOffset());
      }

      @Override
      public void inspectRuntimeShape(RuntimeShapeInspector inspector)
      {
        inspector.visit("doubleColumn", valueColumn);
        inspector.visit("nullBitmap", nullValueIndex);
      }

      @Override
      public boolean isNull()
      {
        final int i = offset.getOffset();
        if (i < offsetMark) {
          // offset was reset, reset iterator state
          nullMark = -1;
          nullIterator = nullValueIndex.peekableIterator();
        }
        offsetMark = i;
        if (nullMark < i) {
          nullIterator.advanceIfNeeded(offsetMark);
          if (nullIterator.hasNext()) {
            nullMark = nullIterator.next();
          }
        }
        return nullMark == offsetMark;
      }
    };
  }

  @Override
  public VectorValueSelector makeVectorValueSelector(ReadableVectorOffset offset)
  {
    return new BaseDoubleVectorValueSelector(offset)
    {
      private final double[] valueVector = new double[offset.getMaxVectorSize()];
      @Nullable
      private boolean[] nullVector = null;
      private int id = ReadableVectorInspector.NULL_ID;

      @Nullable
      private PeekableIntIterator nullIterator = nullValueIndex != null ? nullValueIndex.peekableIterator() : null;
      private int offsetMark = -1;

      @Override
      public double[] getDoubleVector()
      {
        computeVectorsIfNeeded();
        return valueVector;
      }

      @Nullable
      @Override
      public boolean[] getNullVector()
      {
        computeVectorsIfNeeded();
        return nullVector;
      }

      private void computeVectorsIfNeeded()
      {
        if (id == offset.getId()) {
          return;
        }

        if (offset.isContiguous()) {
          if (offset.getStartOffset() < offsetMark) {
            nullIterator = nullValueIndex.peekableIterator();
          }
          offsetMark = offset.getStartOffset() + offset.getCurrentVectorSize();
          valueColumn.get(valueVector, offset.getStartOffset(), offset.getCurrentVectorSize());
        } else {
          final int[] offsets = offset.getOffsets();
          if (offsets[offsets.length - 1] < offsetMark) {
            nullIterator = nullValueIndex.peekableIterator();
          }
          offsetMark = offsets[offsets.length - 1];
          valueColumn.get(valueVector, offsets, offset.getCurrentVectorSize());
        }

        nullVector = VectorSelectorUtils.populateNullVector(nullVector, offset, nullIterator);

        id = offset.getId();
      }
    };
  }

  @Override
  public void close()
  {
    valueColumn.close();
  }

  @SuppressWarnings("unchecked")
  @Nullable
  @Override
  public <T> T as(Class<? extends T> clazz)
  {
    //noinspection ReturnOfNull
    return (T) AS_MAP.getOrDefault(clazz, arg -> null).apply(this);
  }
}
