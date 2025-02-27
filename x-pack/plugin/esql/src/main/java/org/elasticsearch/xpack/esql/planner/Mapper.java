/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.EsqlFunctionRegistry;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.BinaryPlan;
import org.elasticsearch.xpack.esql.plan.logical.Dissect;
import org.elasticsearch.xpack.esql.plan.logical.Enrich;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Grok;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.MvExpand;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.TopN;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;
import org.elasticsearch.xpack.esql.plan.logical.join.Join;
import org.elasticsearch.xpack.esql.plan.logical.join.JoinConfig;
import org.elasticsearch.xpack.esql.plan.logical.join.JoinType;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.plan.logical.show.ShowInfo;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.DissectExec;
import org.elasticsearch.xpack.esql.plan.physical.EnrichExec;
import org.elasticsearch.xpack.esql.plan.physical.EsSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.EvalExec;
import org.elasticsearch.xpack.esql.plan.physical.ExchangeExec;
import org.elasticsearch.xpack.esql.plan.physical.FilterExec;
import org.elasticsearch.xpack.esql.plan.physical.FragmentExec;
import org.elasticsearch.xpack.esql.plan.physical.GrokExec;
import org.elasticsearch.xpack.esql.plan.physical.HashJoinExec;
import org.elasticsearch.xpack.esql.plan.physical.LimitExec;
import org.elasticsearch.xpack.esql.plan.physical.LocalSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.MvExpandExec;
import org.elasticsearch.xpack.esql.plan.physical.OrderExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.plan.physical.ProjectExec;
import org.elasticsearch.xpack.esql.plan.physical.RowExec;
import org.elasticsearch.xpack.esql.plan.physical.ShowExec;
import org.elasticsearch.xpack.esql.plan.physical.TopNExec;
import org.elasticsearch.xpack.esql.plan.physical.UnaryExec;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>This class is part of the planner</p>
 *
 * <p>Translates the logical plan into a physical plan.  This is where we start to decide what will be executed on the data nodes and what
 * will be executed on the coordinator nodes.  This step creates {@link org.elasticsearch.xpack.esql.plan.physical.FragmentExec} instances,
 * which represent logical plan fragments to be sent to the data nodes and {@link org.elasticsearch.xpack.esql.plan.physical.ExchangeExec}
 * instances, which represent data being sent back from the data nodes to the coordinating node.</p>
 */
public class Mapper {

    private final EsqlFunctionRegistry functionRegistry;
    private final boolean localMode; // non-coordinator (data node) mode

    public Mapper(EsqlFunctionRegistry functionRegistry) {
        this.functionRegistry = functionRegistry;
        localMode = false;
    }

    public Mapper(boolean localMode) {
        this.functionRegistry = null;
        this.localMode = localMode;
    }

    public PhysicalPlan map(LogicalPlan p) {
        //
        // Leaf Node
        //

        // Source
        if (p instanceof EsRelation esRelation) {
            return localMode ? new EsSourceExec(esRelation) : new FragmentExec(p);
        }

        if (p instanceof Row row) {
            return new RowExec(row.source(), row.fields());
        }

        if (p instanceof LocalRelation local) {
            return new LocalSourceExec(local.source(), local.output(), local.supplier());
        }

        // Commands
        if (p instanceof ShowInfo showInfo) {
            return new ShowExec(showInfo.source(), showInfo.output(), showInfo.values());
        }

        //
        // Unary Plan
        //
        if (localMode == false && p instanceof Enrich enrich && enrich.mode() == Enrich.Mode.REMOTE) {
            // When we have remote enrich, we want to put it under FragmentExec, so it would be executed remotely.
            // We're only going to do it on the coordinator node.
            // The way we're going to do it is as follows:
            // 1. Locate FragmentExec in the tree. If we have no FragmentExec, we won't do anything.
            // 2. Put this Enrich under it, removing everything that was below it previously.
            // 3. Above FragmentExec, we should deal with pipeline breakers, since pipeline ops already are supposed to go under
            // FragmentExec.
            // 4. Aggregates can't appear here since the plan should have errored out if we have aggregate inside remote Enrich.
            // 5. So we should be keeping: LimitExec, ExchangeExec, OrderExec, TopNExec (actually OrderExec probably can't happen anyway).

            var child = map(enrich.child());
            AtomicBoolean hasFragment = new AtomicBoolean(false);

            var childTransformed = child.transformUp((f) -> {
                // Once we reached FragmentExec, we stuff our Enrich under it
                if (f instanceof FragmentExec) {
                    hasFragment.set(true);
                    return new FragmentExec(p);
                }
                if (f instanceof EnrichExec enrichExec) {
                    // It can only be ANY because COORDINATOR would have errored out earlier, and REMOTE should be under FragmentExec
                    assert enrichExec.mode() == Enrich.Mode.ANY : "enrich must be in ANY mode here";
                    return enrichExec.child();
                }
                if (f instanceof UnaryExec unaryExec) {
                    if (f instanceof LimitExec || f instanceof ExchangeExec || f instanceof OrderExec || f instanceof TopNExec) {
                        return f;
                    } else {
                        return unaryExec.child();
                    }
                }
                // Currently, it's either UnaryExec or LeafExec. Leaf will either resolve to FragmentExec or we'll ignore it.
                return f;
            });

            if (hasFragment.get()) {
                return childTransformed;
            }
        }

        if (p instanceof UnaryPlan ua) {
            var child = map(ua.child());
            if (child instanceof FragmentExec) {
                // COORDINATOR enrich must not be included to the fragment as it has to be executed on the coordinating node
                if (p instanceof Enrich enrich && enrich.mode() == Enrich.Mode.COORDINATOR) {
                    assert localMode == false : "coordinator enrich must not be included to a fragment and re-planned locally";
                    child = addExchangeForFragment(enrich.child(), child);
                    return map(enrich, child);
                }
                // in case of a fragment, push to it any current streaming operator
                if (isPipelineBreaker(p) == false) {
                    return new FragmentExec(p);
                }
            }
            return map(ua, child);
        }

        if (p instanceof BinaryPlan bp) {
            var left = map(bp.left());
            var right = map(bp.right());

            if (left instanceof FragmentExec) {
                if (right instanceof FragmentExec) {
                    throw new EsqlIllegalArgumentException("can't plan binary [" + p.nodeName() + "]");
                }
                // in case of a fragment, push to it any current streaming operator
                return new FragmentExec(p);
            }
            if (right instanceof FragmentExec) {
                // in case of a fragment, push to it any current streaming operator
                return new FragmentExec(p);
            }
            return map(bp, left, right);
        }

        throw new EsqlIllegalArgumentException("unsupported logical plan node [" + p.nodeName() + "]");
    }

    static boolean isPipelineBreaker(LogicalPlan p) {
        return p instanceof Aggregate || p instanceof TopN || p instanceof Limit || p instanceof OrderBy;
    }

    private PhysicalPlan map(UnaryPlan p, PhysicalPlan child) {
        //
        // Pipeline operators
        //
        if (p instanceof Filter f) {
            return new FilterExec(f.source(), child, f.condition());
        }

        if (p instanceof Project pj) {
            return new ProjectExec(pj.source(), child, pj.projections());
        }

        if (p instanceof Eval eval) {
            return new EvalExec(eval.source(), child, eval.fields());
        }

        if (p instanceof Dissect dissect) {
            return new DissectExec(dissect.source(), child, dissect.input(), dissect.parser(), dissect.extractedFields());
        }

        if (p instanceof Grok grok) {
            return new GrokExec(grok.source(), child, grok.input(), grok.parser(), grok.extractedFields());
        }

        if (p instanceof Enrich enrich) {
            return new EnrichExec(
                enrich.source(),
                child,
                enrich.mode(),
                enrich.policy().getType(),
                enrich.matchField(),
                BytesRefs.toString(enrich.policyName().fold()),
                enrich.policy().getMatchField(),
                enrich.concreteIndices(),
                enrich.enrichFields()
            );
        }

        if (p instanceof MvExpand mvExpand) {
            MvExpandExec result = new MvExpandExec(mvExpand.source(), map(mvExpand.child()), mvExpand.target(), mvExpand.expanded());
            if (mvExpand.limit() != null) {
                // MvExpand could have an inner limit
                // see PushDownAndCombineLimits rule
                return new LimitExec(result.source(), result, new Literal(Source.EMPTY, mvExpand.limit(), DataType.INTEGER));
            }
            return result;
        }

        //
        // Pipeline breakers
        //
        if (p instanceof Limit limit) {
            return map(limit, child);
        }

        if (p instanceof OrderBy o) {
            return map(o, child);
        }

        if (p instanceof TopN topN) {
            return map(topN, child);
        }

        if (p instanceof Aggregate aggregate) {
            return map(aggregate, child);
        }

        throw new EsqlIllegalArgumentException("unsupported logical plan node [" + p.nodeName() + "]");
    }

    private PhysicalPlan map(Aggregate aggregate, PhysicalPlan child) {
        List<Attribute> intermediateAttributes = AbstractPhysicalOperationProviders.intermediateAttributes(
            aggregate.aggregates(),
            aggregate.groupings()
        );
        // in local mode the only aggregate that can appear is the partial side under an exchange
        if (localMode) {
            child = aggExec(aggregate, child, AggregatorMode.INITIAL, intermediateAttributes);
        }
        // otherwise create both sides of the aggregate (for parallelism purposes), if no fragment is present
        // TODO: might be easier long term to end up with just one node and split if necessary instead of doing that always at this stage
        else {
            child = addExchangeForFragment(aggregate, child);
            // exchange was added - use the intermediates for the output
            if (child instanceof ExchangeExec exchange) {
                child = new ExchangeExec(child.source(), intermediateAttributes, true, exchange.child());
            }
            // if no exchange was added, create the partial aggregate
            else {
                child = aggExec(aggregate, child, AggregatorMode.INITIAL, intermediateAttributes);
            }

            // regardless, always add the final agg
            child = aggExec(aggregate, child, AggregatorMode.FINAL, intermediateAttributes);
        }

        return child;
    }

    private static AggregateExec aggExec(
        Aggregate aggregate,
        PhysicalPlan child,
        AggregatorMode aggMode,
        List<Attribute> intermediateAttributes
    ) {
        return new AggregateExec(
            aggregate.source(),
            child,
            aggregate.groupings(),
            aggregate.aggregates(),
            aggMode,
            intermediateAttributes,
            null
        );
    }

    private PhysicalPlan map(Limit limit, PhysicalPlan child) {
        child = addExchangeForFragment(limit, child);
        return new LimitExec(limit.source(), child, limit.limit());
    }

    private PhysicalPlan map(OrderBy o, PhysicalPlan child) {
        child = addExchangeForFragment(o, child);
        return new OrderExec(o.source(), child, o.order());
    }

    private PhysicalPlan map(TopN topN, PhysicalPlan child) {
        child = addExchangeForFragment(topN, child);
        return new TopNExec(topN.source(), child, topN.order(), topN.limit(), null);
    }

    private PhysicalPlan addExchangeForFragment(LogicalPlan logical, PhysicalPlan child) {
        // in case of fragment, preserve the streaming operator (order-by, limit or topN) for local replanning
        // no need to do it for an aggregate since it gets split
        // and clone it as a physical node along with the exchange
        if (child instanceof FragmentExec) {
            child = new FragmentExec(logical);
            child = new ExchangeExec(child.source(), child);
        }
        return child;
    }

    private PhysicalPlan map(BinaryPlan p, PhysicalPlan lhs, PhysicalPlan rhs) {
        if (p instanceof Join join) {
            PhysicalPlan hash = tryHashJoin(join, lhs, rhs);
            if (hash != null) {
                return hash;
            }
        }
        throw new EsqlIllegalArgumentException("unsupported logical plan node [" + p.nodeName() + "]");
    }

    private PhysicalPlan tryHashJoin(Join join, PhysicalPlan lhs, PhysicalPlan rhs) {
        JoinConfig config = join.config();
        if (config.type() != JoinType.LEFT) {
            return null;
        }
        if (rhs instanceof LocalSourceExec local) {
            return new HashJoinExec(
                join.source(),
                lhs,
                local,
                config.matchFields(),
                config.leftFields(),
                config.rightFields(),
                join.output()
            );
        }
        return null;
    }
}
