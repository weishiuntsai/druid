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

package org.apache.druid.sql.calcite.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.rules.FilterJoinRule;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.tools.RelBuilder;
import org.apache.druid.java.util.common.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class is a copy (with modification) of {@link FilterJoinRule}. Specifically, this class contains a
 * subset of code from {@link FilterJoinRule} for the codepath involving
 * {@link org.apache.calcite.rel.rules.CoreRules#FILTER_INTO_JOIN}
 * Everything has been keep as-is from {@link FilterJoinRule} except for :
 * 1. the modification of {@link #classifyFilters(List, JoinRelType, boolean, List)} method called in the
 * {@link #perform(RelOptRuleCall, Filter, Join)} method of this class.
 * 2. removing redundant 'IS NOT NULL' filters from inner join filter condition
 * The {@link #classifyFilters(List, JoinRelType, boolean, List)} method is based of {@link RelOptUtil#classifyFilters}.
 * The difference is that the modfied method use in thsi class will not not push filters to the children.
 * Hence, filters will either stay where they are or are pushed to the join (if they originated from above the join).
 *
 * The modification of {@link #classifyFilters(List, JoinRelType, boolean, List)} is needed due to the bug described in
 * https://github.com/apache/druid/pull/9773. This class and it's modification can be removed, switching back to the
 * default Rule provided in Calcite's {@link FilterJoinRule} when https://github.com/apache/druid/issues/9843 is resolved.
 */

public abstract class FilterJoinExcludePushToChildRule<C extends FilterJoinRule.Config> extends FilterJoinRule<C>
{
  public static final FilterJoinRule<FilterIntoJoinRule.FilterIntoJoinRuleConfig> FILTER_ON_JOIN_EXCLUDE_PUSH_TO_CHILD =
      new FilterIntoJoinExcludePushToChildRule(FilterIntoJoinRule.FilterIntoJoinRuleConfig.DEFAULT);

  FilterJoinExcludePushToChildRule(C config)
  {
    super(config);
  }

  /**
   * Rule that tries to push filter expressions into a join
   * condition. Exlucde pushing into the inputs (child) of the join.
   */
  public static class FilterIntoJoinExcludePushToChildRule extends FilterJoinExcludePushToChildRule<FilterIntoJoinRule.FilterIntoJoinRuleConfig>
  {
    public FilterIntoJoinExcludePushToChildRule(FilterIntoJoinRule.FilterIntoJoinRuleConfig config)
    {
      super(config);
    }

    @Override
    public void onMatch(RelOptRuleCall call)
    {
      Filter filter = call.rel(0);
      Join join = call.rel(1);
      perform(call, filter, join);
    }
  }

  /**
   * Copied from {@link FilterJoinRule#perform}
   * The difference is that this method will not not push filters to the children in classifyFilters
   */
  @Override
  protected void perform(RelOptRuleCall call, Filter filter, Join join)
  {
    final List<RexNode> joinFilters =
        RelOptUtil.conjunctions(join.getCondition());
    final List<RexNode> origJoinFilters = ImmutableList.copyOf(joinFilters);
    // If there is only the joinRel,
    // make sure it does not match a cartesian product joinRel
    // (with "true" condition), otherwise this rule will be applied
    // again on the new cartesian product joinRel.
    if (filter == null && joinFilters.isEmpty()) {
      return;
    }

    final List<RexNode> aboveFilters =
        filter != null
        ? getConjunctions(filter)
        : new ArrayList<>();
    final ImmutableList<RexNode> origAboveFilters =
        ImmutableList.copyOf(aboveFilters);

    // Simplify Outer Joins
    JoinRelType joinType = join.getJoinType();
    if (!origAboveFilters.isEmpty() && join.getJoinType() != JoinRelType.INNER) {
      joinType = RelOptUtil.simplifyJoin(join, origAboveFilters, joinType);
    }

    final List<RexNode> leftFilters = new ArrayList<>();
    final List<RexNode> rightFilters = new ArrayList<>();

    // TODO - add logic to derive additional filters.  E.g., from
    // (t1.a = 1 AND t2.a = 2) OR (t1.b = 3 AND t2.b = 4), you can
    // derive table filters:
    // (t1.a = 1 OR t1.b = 3)
    // (t2.a = 2 OR t2.b = 4)

    // Try to push down above filters. These are typically where clause
    // filters. They can be pushed down if they are not on the NULL
    // generating side.
    boolean filterPushed = false;
    if (classifyFilters(aboveFilters, joinType, true, joinFilters)) {
      filterPushed = true;
    }

    // Move join filters up if needed
    validateJoinFilters(aboveFilters, joinFilters, join, joinType);

    // If no filter got pushed after validate, reset filterPushed flag
    if (joinFilters.size() == origJoinFilters.size()) {
      if (Sets.newHashSet(joinFilters)
              .equals(Sets.newHashSet(origJoinFilters))) {
        filterPushed = false;
      }
    }

    // Try to push down filters in ON clause. A ON clause filter can only be
    // pushed down if it does not affect the non-matching set, i.e. it is
    // not on the side which is preserved.

    // Anti-join on conditions can not be pushed into left or right, e.g. for plan:
    //
    //     Join(condition=[AND(cond1, $2)], joinType=[anti])
    //     :  - prj(f0=[$0], f1=[$1], f2=[$2])
    //     :  - prj(f0=[$0])
    //
    // The semantic would change if join condition $2 is pushed into left,
    // that is, the result set may be smaller. The right can not be pushed
    // into for the same reason.
    if (joinType != JoinRelType.ANTI && classifyFilters(joinFilters, joinType, false, joinFilters)) {
      filterPushed = true;
    }

    // once the filters are pushed to join from top, try to remove redudant 'IS NOT NULL' filters
    removeRedundantIsNotNullFilters(joinFilters, joinType);

    // if nothing actually got pushed and there is nothing leftover,
    // then this rule is a no-op
    if ((!filterPushed && joinType == join.getJoinType()) || joinFilters.isEmpty()) {
      return;
    }

    // create Filters on top of the children if any filters were
    // pushed to them
    final RexBuilder rexBuilder = join.getCluster().getRexBuilder();
    final RelBuilder relBuilder = call.builder();
    final RelNode leftRel =
        relBuilder.push(join.getLeft()).filter(leftFilters).build();
    final RelNode rightRel =
        relBuilder.push(join.getRight()).filter(rightFilters).build();

    // create the new join node referencing the new children and
    // containing its new join filters (if there are any)
    final ImmutableList<RelDataType> fieldTypes =
        ImmutableList.<RelDataType>builder()
            .addAll(RelOptUtil.getFieldTypeList(leftRel.getRowType()))
            .addAll(RelOptUtil.getFieldTypeList(rightRel.getRowType())).build();
    final RexNode joinFilter =
        RexUtil.composeConjunction(rexBuilder,
                                   RexUtil.fixUp(rexBuilder, joinFilters, fieldTypes));

    // If nothing actually got pushed and there is nothing leftover,
    // then this rule is a no-op
    if (joinFilter.isAlwaysTrue()
        && leftFilters.isEmpty()
        && rightFilters.isEmpty()
        && joinType == join.getJoinType()) {
      return;
    }

    RelNode newJoinRel =
        join.copy(
            join.getTraitSet(),
            joinFilter,
            leftRel,
            rightRel,
            joinType,
            join.isSemiJoinDone());
    call.getPlanner().onCopy(join, newJoinRel);
    if (!leftFilters.isEmpty()) {
      call.getPlanner().onCopy(filter, leftRel);
    }
    if (!rightFilters.isEmpty()) {
      call.getPlanner().onCopy(filter, rightRel);
    }

    relBuilder.push(newJoinRel);

    // Create a project on top of the join if some of the columns have become
    // NOT NULL due to the join-type getting stricter.
    relBuilder.convert(join.getRowType(), false);

    // create a FilterRel on top of the join if needed
    relBuilder.filter(
        RexUtil.fixUp(rexBuilder, aboveFilters,
                      RelOptUtil.getFieldTypeList(relBuilder.peek().getRowType())));
    call.transformTo(relBuilder.build());
  }

  /**
   * Copied from {@link FilterJoinRule#getConjunctions}. Method is exactly the same as original.
   */
  private List<RexNode> getConjunctions(Filter filter)
  {
    List<RexNode> conjunctions = RelOptUtil.conjunctions(filter.getCondition());
    RexBuilder rexBuilder = filter.getCluster().getRexBuilder();
    for (int i = 0; i < conjunctions.size(); i++) {
      RexNode node = conjunctions.get(i);
      if (node instanceof RexCall) {
        conjunctions.set(i,
                         RelOptUtil.collapseExpandedIsNotDistinctFromExpr((RexCall) node, rexBuilder));
      }
    }
    return conjunctions;
  }

  /**
   * Copied from {@link RelOptUtil#classifyFilters}
   * The difference is that this method will not not push filters to the children.
   * Hence, filters will either stay where they are or are pushed to the join (if they originated
   * from above the join).
   */
  private static boolean classifyFilters(List<RexNode> filters,
                                        JoinRelType joinType,
                                        boolean pushInto,
                                        List<RexNode> joinFilters)
  {
    final List<RexNode> filtersToRemove = new ArrayList<>();
    for (RexNode filter : filters) {
      // Skip pushing the filter to either child. However, if the join
      // is an inner join, push them to the join if they originated
      // from above the join
      if (!joinType.isOuterJoin() && pushInto) {
        if (!joinFilters.contains(filter)) {
          joinFilters.add(filter);
        }
        filtersToRemove.add(filter);
      }
    }

    // Remove filters after the loop, to prevent concurrent modification.
    if (!filtersToRemove.isEmpty()) {
      filters.removeAll(filtersToRemove);
    }

    // Did anything change?
    return !filtersToRemove.isEmpty();
  }

  /**
   * This tries to find all the 'IS NOT NULL' filters in an inner join whose checking column is also
   * a part of an equi-condition between the two tables. It removes such 'IS NOT NULL' filters from join since
   * the equi-condition will never return true for null input, thus making the 'IS NOT NULL' filter a no-op.
   *
   * @param joinFilters
   * @param joinType
   */
  static void removeRedundantIsNotNullFilters(List<RexNode> joinFilters, JoinRelType joinType)
  {
    if (joinType != JoinRelType.INNER) {
      return; // only works for inner joins
    }

    ImmutableList.Builder<RexNode> isNotNullFiltersBuilder = ImmutableList.builder();
    ImmutableList.Builder<Pair<RexNode, RexNode>> equalityFiltersOperandBuilder = ImmutableList.builder();

    joinFilters.stream().filter(joinFilter -> joinFilter instanceof RexCall).forEach(joinFilter -> {
      if (joinFilter.isA(SqlKind.IS_NOT_NULL)) {
        isNotNullFiltersBuilder.add(joinFilter);
      } else if (joinFilter.isA(SqlKind.EQUALS)) {
        List<RexNode> operands = ((RexCall) joinFilter).getOperands();
        if (operands.size() == 2 && operands.stream().noneMatch(Objects::isNull)) {
          equalityFiltersOperandBuilder.add(new Pair<>(operands.get(0), operands.get(1)));
        }
      }
    });

    List<Pair<RexNode, RexNode>> equalityFilters = equalityFiltersOperandBuilder.build();
    ImmutableList.Builder<RexNode> removableFilters = ImmutableList.builder();
    for (RexNode isNotNullFilter : isNotNullFiltersBuilder.build()) {
      List<RexNode> operands = ((RexCall) isNotNullFilter).getOperands();
      boolean canDrop = false;
      for (Pair<RexNode, RexNode> equalityFilterOperands : equalityFilters) {
        if ((equalityFilterOperands.lhs != null && equalityFilterOperands.lhs.equals(operands.get(0))) ||
            (equalityFilterOperands.rhs != null && equalityFilterOperands.rhs.equals(operands.get(0)))) {
          canDrop = true;
          break;
        }
      }
      if (canDrop) {
        removableFilters.add(isNotNullFilter);
      }
    }
    joinFilters.removeAll(removableFilters.build());
  }
}
