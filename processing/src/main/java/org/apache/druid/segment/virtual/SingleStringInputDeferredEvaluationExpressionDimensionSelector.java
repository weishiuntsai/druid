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

package org.apache.druid.segment.virtual;

import com.google.common.base.Preconditions;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExpressionType;
import org.apache.druid.query.filter.DruidPredicateFactory;
import org.apache.druid.query.filter.ValueMatcher;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.DimensionSelectorUtils;
import org.apache.druid.segment.IdLookup;
import org.apache.druid.segment.data.IndexedInts;

import javax.annotation.Nullable;

/**
 * A {@link DimensionSelector} decorator that directly exposes the underlying dictionary id in {@link #getRow},
 * saving expression computation until {@link #lookupName} is called. This allows for performing operations like
 * grouping on the native dictionary ids, and deferring expression evaluation until after which can dramatically
 * reduce the total number of evaluations.
 *
 * @see ExpressionSelectors for details on how expression selectors are constructed.
 *
 * @see SingleStringInputDeferredEvaluationExpressionDimensionVectorSelector for the vectorized version of
 * this selector.
 */
public class SingleStringInputDeferredEvaluationExpressionDimensionSelector implements DimensionSelector
{
  private final DimensionSelector selector;
  private final Expr expression;
  private final SingleInputBindings bindings = new SingleInputBindings(ExpressionType.STRING);

  public SingleStringInputDeferredEvaluationExpressionDimensionSelector(
      final DimensionSelector selector,
      final Expr expression
  )
  {
    // Verify selector has a working dictionary.
    if (selector.getValueCardinality() == CARDINALITY_UNKNOWN
        || !selector.nameLookupPossibleInAdvance()) {
      throw new ISE("Selector of class[%s] does not have a dictionary, cannot use it.", selector.getClass().getName());
    }

    this.selector = Preconditions.checkNotNull(selector, "selector");
    this.expression = Preconditions.checkNotNull(expression, "expression");
  }

  @Override
  public void inspectRuntimeShape(final RuntimeShapeInspector inspector)
  {
    inspector.visit("selector", selector);
    inspector.visit("expression", expression);
  }

  /**
   * Get the underlying selector {@link IndexedInts} row
   */
  @Override
  public IndexedInts getRow()
  {
    return selector.getRow();
  }

  @Override
  public ValueMatcher makeValueMatcher(@Nullable final String value)
  {
    return DimensionSelectorUtils.makeValueMatcherGeneric(this, value);
  }

  @Override
  public ValueMatcher makeValueMatcher(final DruidPredicateFactory predicateFactory)
  {
    return DimensionSelectorUtils.makeValueMatcherGeneric(this, predicateFactory);
  }

  @Override
  public int getValueCardinality()
  {
    return selector.getValueCardinality();
  }

  @Override
  public String lookupName(final int id)
  {
    final String value;

    value = selector.lookupName(id);

    bindings.set(value);
    return expression.eval(bindings).asString();
  }

  @Override
  public boolean nameLookupPossibleInAdvance()
  {
    return true;
  }

  @Nullable
  @Override
  public IdLookup idLookup()
  {
    return null;
  }

  @Nullable
  @Override
  public Object getObject()
  {
    return defaultGetObject();
  }

  @Override
  public Class classOfObject()
  {
    return Object.class;
  }
}
