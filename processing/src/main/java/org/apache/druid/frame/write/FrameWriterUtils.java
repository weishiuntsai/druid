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

package org.apache.druid.frame.write;

import org.apache.datasketches.memory.WritableMemory;
import org.apache.druid.frame.FrameType;
import org.apache.druid.frame.key.KeyColumn;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.segment.BaseObjectColumnValueSelector;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.DimensionDictionarySelector;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.data.IndexedInts;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility methods used by {@link FrameWriter} implementations.
 */
public class FrameWriterUtils
{
  public static final byte NULL_STRING_MARKER = (byte) 0xFF; /* cannot appear in a valid utf-8 byte sequence */
  public static final byte[] NULL_STRING_MARKER_ARRAY = new byte[]{NULL_STRING_MARKER};

  public static final String RESERVED_FIELD_PREFIX = "___druid";

  /**
   * Writes a frame header to a memory locations.
   */
  public static long writeFrameHeader(
      final WritableMemory memory,
      final long startPosition,
      final FrameType frameType,
      final long totalSize,
      final int numRows,
      final int numRegions,
      final boolean permuted
  )
  {
    long currentPosition = startPosition;

    memory.putByte(currentPosition, frameType.version());
    currentPosition += Byte.BYTES;

    memory.putLong(currentPosition, totalSize);
    currentPosition += Long.BYTES;

    memory.putInt(currentPosition, numRows);
    currentPosition += Integer.BYTES;

    memory.putInt(currentPosition, numRegions);
    currentPosition += Integer.BYTES;

    memory.putByte(currentPosition, permuted ? (byte) 1 : (byte) 0);
    currentPosition += Byte.BYTES;

    return currentPosition - startPosition;
  }

  /**
   * Retrieves UTF-8 byte buffers from a {@link DimensionSelector}, which is expected to be the kind of
   * selector you get for an {@code STRING} column.
   *
   * Null strings are returned as {@link #NULL_STRING_MARKER_ARRAY}.
   *
   * @param selector   the selector
   * @param multiValue if true, return an array that corresponds exactly to {@link DimensionSelector#getRow()}.
   *                   if false, always return a single-valued array. In particular, this means [] is
   *                   returned as [NULL_STRING_MARKER_ARRAY].
   *
   * @return UTF-8 strings. The list itself is never null.
   */
  public static List<ByteBuffer> getUtf8ByteBuffersFromStringSelector(
      final DimensionSelector selector,
      final boolean multiValue
  )
  {
    final IndexedInts row = selector.getRow();
    final int size = row.size();

    if (multiValue) {
      final List<ByteBuffer> retVal = new ArrayList<>(size);

      for (int i = 0; i < size; i++) {
        retVal.add(getUtf8ByteBufferFromStringSelector(selector, row.get(i)));
      }

      return retVal;
    } else {
      // If !multivalue, always return exactly one buffer.
      if (size == 0) {
        return Collections.singletonList(ByteBuffer.wrap(NULL_STRING_MARKER_ARRAY));
      } else if (size == 1) {
        return Collections.singletonList(getUtf8ByteBufferFromStringSelector(selector, row.get(0)));
      } else {
        throw new ISE("Encountered unexpected multi-value row");
      }
    }
  }

  /**
   * Retrieves UTF-8 byte buffers from a {@link ColumnValueSelector}, which is expected to be the kind of
   * selector you get for an {@code ARRAY<STRING>} column.
   *
   * Null strings are returned as {@code null}.
   *
   * If the entire array returned by {@link BaseObjectColumnValueSelector#getObject()} is null, returns either
   * null or {@link #NULL_STRING_MARKER_ARRAY} depending on the value of "useNullArrays".
   *
   * @param selector array selector
   *
   * @return UTF-8 strings. The list itself may be null.
   */
  @Nullable
  public static List<ByteBuffer> getUtf8ByteBuffersFromStringArraySelector(
      @SuppressWarnings("rawtypes") final BaseObjectColumnValueSelector selector
  )
  {
    final Object[] row = (Object[]) selector.getObject();
    if (row == null) {
      return null;
    } else {
      final List<ByteBuffer> retVal = new ArrayList<>();
      for (Object value : row) {
        retVal.add(getUtf8ByteBufferFromString((String) value));
      }
      return retVal;
    }
  }

  /**
   * Checks the provided signature for any disallowed field names. Returns any that are found.
   */
  public static Set<String> findDisallowedFieldNames(final RowSignature signature)
  {
    return signature.getColumnNames()
                    .stream()
                    .filter(s -> s.startsWith(RESERVED_FIELD_PREFIX))
                    .collect(Collectors.toSet());
  }

  /**
   * Verifies whether the provided sortColumns are all sortable, and are a prefix of the signature. This is required
   * because it allows us to treat the sort key as a chunk of bytes.
   *
   * Exits quietly if the sort columns are OK. Throws an exception if there is a problem.
   *
   * @throws IllegalArgumentException if there is a problem
   */
  public static void verifySortColumns(
      final List<KeyColumn> keyColumns,
      final RowSignature signature
  )
  {
    if (!areKeyColumnsPrefixOfSignature(keyColumns, signature)) {
      throw new IAE(
          "Sort column [%s] must be a prefix of the signature",
          keyColumns.stream().map(KeyColumn::columnName).collect(Collectors.joining(", "))
      );
    }

    // Verify that all sort columns are comparable.
    for (final KeyColumn keyColumn : keyColumns) {
      final ColumnType columnType = signature.getColumnType(keyColumn.columnName()).orElse(null);

      if (columnType == null) {
        throw new IAE("Sort column [%s] type is unknown", keyColumn.columnName());
      }
    }
  }

  /**
   * Copies {@code src} to {@code dst} without making any modification to the source data.
   */
  public static void copyByteBufferToMemoryAllowingNullBytes(
      final ByteBuffer src,
      final WritableMemory dst,
      final long dstPosition,
      final int len
  )
  {
    copyByteBufferToMemory(src, dst, dstPosition, len, true, false);
  }

  /**
   * Copies {@code src} to {@code dst}, disallowing null bytes to be written to the destination. If {@code removeNullBytes}
   * is true, the method will drop the null bytes, and if it is false, the method will throw an exception. The written bytes
   * can be less than "len" if the null bytes are dropped, and the callers must evaluate the return value to see the actual
   * length of the buffer that is copied
   */
  public static int copyByteBufferToMemoryDisallowingNullBytes(
      final ByteBuffer src,
      final WritableMemory dst,
      final long dstPosition,
      final int len,
      final boolean removeNullBytes
  )
  {
    return copyByteBufferToMemory(src, dst, dstPosition, len, false, removeNullBytes);
  }

  /**
   * Tries to copy "len" bytes from {@code src.position()} to "dstPosition" in "memory". If removeNullBytes is set to true,
   * it will remove the U+0000 bytes from the src buffer, and the written bytes will be less than "len". It is imperative that the
   * callers check the number of written bytes when "removeNullBytes" can be set to true, i.e. this method is invoked via
   * {@link #copyByteBufferToMemoryDisallowingNullBytes}
   * <p>
   * Does not update the position of src.
   * <p>
   * Whenever "allowNullBytes" is true, "removeNullBytes" must be false. Use the methods {@link #copyByteBufferToMemoryAllowingNullBytes}
   * and {@link #copyByteBufferToMemoryDisallowingNullBytes} to copy between the memory
   * <p>
   *
   * @throws InvalidNullByteException if "allowNullBytes" and "removeNullBytes" is false and a null byte is encountered
   */
  private static int copyByteBufferToMemory(
      final ByteBuffer src,
      final WritableMemory dst,
      final long dstPosition,
      final int len,
      final boolean allowNullBytes,
      final boolean removeNullBytes
  )
  {
    if (src.remaining() < len) {
      throw new ISE("Insufficient source space available");
    }
    if (dst.getCapacity() - dstPosition < len) {
      throw new ISE("Insufficient destination space available");
    }

    final int srcEnd = src.position() + len;
    int writtenLength = 0;

    if (allowNullBytes) {
      if (src.hasArray()) {
        // Null bytes are ignored and the src buffer is backed by an array. Bulk copying to the destination would be the fastest
        dst.putByteArray(dstPosition, src.array(), src.arrayOffset() + src.position(), len);
      } else {
        // Null bytes are ignored and the src buffer is not backed by an array. We can copy the byte buffer to the destination individually
        long q = dstPosition;
        for (int p = src.position(); p < srcEnd; p++, q++) {
          final byte b = src.get(p);
          dst.putByte(q, b);
        }
      }
      // The method does not alter the length of the memory copied if null bytes are allowed
      writtenLength = len;
    } else {
      long q = dstPosition;
      for (int p = src.position(); p < srcEnd; p++) {
        final byte b = src.get(p);

        if (b == 0) {
          if (!removeNullBytes) {
            // Cannot ignore the null byte, but cannot remove them as well. Therefore, throw an error.
            ByteBuffer duplicate = src.duplicate();
            duplicate.limit(srcEnd);
            throw InvalidNullByteException.builder()
                                          .value(StringUtils.fromUtf8(duplicate))
                                          .position(p - src.position())
                                          .build();
          }
        } else {
          dst.putByte(q, b);
          q++;
          writtenLength++;
        }
      }
    }
    return writtenLength;
  }

  /**
   * Extracts a ByteBuffer from the provided dictionary selector.
   *
   * Null strings are returned as {@link #NULL_STRING_MARKER_ARRAY}.
   */
  private static ByteBuffer getUtf8ByteBufferFromStringSelector(
      final DimensionDictionarySelector selector,
      final int dictionaryId
  )
  {
    if (selector.supportsLookupNameUtf8()) {
      final ByteBuffer buf = selector.lookupNameUtf8(dictionaryId);

      if (buf == null) {
        return ByteBuffer.wrap(NULL_STRING_MARKER_ARRAY);
      } else {
        return buf;
      }
    } else {
      return FrameWriterUtils.getUtf8ByteBufferFromString(selector.lookupName(dictionaryId));
    }
  }

  /**
   * Extracts a ByteBuffer from the string. Null strings are returned as {@link #NULL_STRING_MARKER_ARRAY}.
   */
  private static ByteBuffer getUtf8ByteBufferFromString(@Nullable final String data)
  {
    if (data == null) {
      return ByteBuffer.wrap(NULL_STRING_MARKER_ARRAY);
    } else {
      return ByteBuffer.wrap(StringUtils.toUtf8(data));
    }
  }

  /**
   * Returns whether the provided key columns are all a prefix of the signature.
   */
  private static boolean areKeyColumnsPrefixOfSignature(
      final List<KeyColumn> keyColumns,
      final RowSignature signature
  )
  {
    if (keyColumns.size() > signature.size()) {
      return false;
    }

    for (int i = 0; i < keyColumns.size(); i++) {
      if (!keyColumns.get(i).columnName().equals(signature.getColumnName(i))) {
        return false;
      }
    }

    return true;
  }

  public static RowSignature replaceUnknownTypesWithNestedColumns(final RowSignature rowSignature)
  {
    RowSignature.Builder retBuilder = RowSignature.builder();
    for (int i = 0; i < rowSignature.size(); ++i) {
      String columnName = rowSignature.getColumnName(i);
      ColumnType columnType = rowSignature.getColumnType(i).orElse(ColumnType.NESTED_DATA);
      retBuilder.add(columnName, columnType);
    }
    return retBuilder.build();
  }
}
