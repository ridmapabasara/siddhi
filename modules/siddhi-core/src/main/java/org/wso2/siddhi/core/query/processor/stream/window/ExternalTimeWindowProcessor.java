/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.query.processor.stream.window;

import org.apache.log4j.Logger;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.ReturnAttribute;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.executor.ConstantExpressionExecutor;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.processor.Processor;
import org.wso2.siddhi.core.table.EventTable;
import org.wso2.siddhi.core.util.collection.operator.Finder;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaStateHolder;
import org.wso2.siddhi.core.util.parser.OperatorParser;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.exception.ExecutionPlanValidationException;
import org.wso2.siddhi.query.api.expression.Expression;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Extension(
        name = "externalTime",
        namespace = "",
        description = "A sliding time window based on external time. It holds events that arrived during the last " +
                "windowTime period from the external timestamp, and gets updated on every monotonically " +
                "increasing timestamp.",
        parameters = {
                @Parameter(name = "windowTime",
                        description = "The sliding time period for which the window should hold events.",
                        type = {DataType.INT, DataType.LONG, DataType.TIME}),
        },
        returnAttributes = @ReturnAttribute(
                description = "Returns current and expired events.",
                type = {})
)
public class ExternalTimeWindowProcessor extends WindowProcessor implements FindableProcessor {
    static final Logger log = Logger.getLogger(ExternalTimeWindowProcessor.class);
    private long timeToKeep;
    private ComplexEventChunk<StreamEvent> expiredEventChunk;
    private VariableExpressionExecutor timeStampVariableExpressionExecutor;

    @Override
    protected void init(ExpressionExecutor[] attributeExpressionExecutors, ExecutionPlanContext executionPlanContext) {
        this.expiredEventChunk = new ComplexEventChunk<StreamEvent>(false);
        if (attributeExpressionExecutors.length == 2) {
            if (attributeExpressionExecutors[1].getReturnType() == Attribute.Type.INT) {
                timeToKeep = Integer.parseInt(String.valueOf(((ConstantExpressionExecutor) attributeExpressionExecutors[1]).getValue()));
            } else {
                timeToKeep = Long.parseLong(String.valueOf(((ConstantExpressionExecutor) attributeExpressionExecutors[1]).getValue()));
            }
            if (!(attributeExpressionExecutors[0] instanceof VariableExpressionExecutor)) {
                throw new ExecutionPlanValidationException("ExternalTime window's 1st parameter timeStamp should be a type long stream attribute but found " + attributeExpressionExecutors[0].getClass());
            }
            timeStampVariableExpressionExecutor = ((VariableExpressionExecutor) attributeExpressionExecutors[0]);
            if (timeStampVariableExpressionExecutor.getReturnType() != Attribute.Type.LONG) {
                throw new ExecutionPlanValidationException("ExternalTime window's 1st parameter timeStamp should be type long, but found " + timeStampVariableExpressionExecutor.getReturnType());
            }
        } else {
            throw new ExecutionPlanValidationException("ExternalTime window should only have two parameter (<long> timeStamp, <int|long|time> windowTime), but found " + attributeExpressionExecutors.length + " input attributes");
        }
    }

    @Override
    protected synchronized void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor, StreamEventCloner streamEventCloner) {
        synchronized (this) {
            while (streamEventChunk.hasNext()) {

                StreamEvent streamEvent = streamEventChunk.next();
                long currentTime = (Long) timeStampVariableExpressionExecutor.execute(streamEvent);

                StreamEvent clonedEvent = streamEventCloner.copyStreamEvent(streamEvent);
                clonedEvent.setType(StreamEvent.Type.EXPIRED);

                // reset expiredEventChunk to make sure all of the expired events get removed,
                // otherwise lastReturned.next will always return null and here while check is always false
                expiredEventChunk.reset();
                while (expiredEventChunk.hasNext()) {
                    StreamEvent expiredEvent = expiredEventChunk.next();
                    long expiredEventTime = (Long) timeStampVariableExpressionExecutor.execute(expiredEvent);
                    long timeDiff = expiredEventTime - currentTime + timeToKeep;
                    if (timeDiff <= 0) {
                        expiredEventChunk.remove();
                        expiredEvent.setTimestamp(currentTime);
                        streamEventChunk.insertBeforeCurrent(expiredEvent);
                    } else {
                        expiredEventChunk.reset();
                        break;
                    }
                }

                if (streamEvent.getType() == StreamEvent.Type.CURRENT) {
                    this.expiredEventChunk.add(clonedEvent);
                }
                expiredEventChunk.reset();
            }
        }
        nextProcessor.process(streamEventChunk);
    }

    @Override
    public void start() {
        //Do nothing
    }

    @Override
    public void stop() {
        //Do nothing
    }

    @Override
    public Map<String, Object> currentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("ExpiredEventChunk", expiredEventChunk.getFirst());
        return state;
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        expiredEventChunk.clear();
        expiredEventChunk.add((StreamEvent) state.get("ExpiredEventChunk"));
    }

    @Override
    public synchronized StreamEvent find(StateEvent matchingEvent, Finder finder) {
        return finder.find(matchingEvent, expiredEventChunk, streamEventCloner);
    }

    @Override
    public Finder constructFinder(Expression expression, MatchingMetaStateHolder matchingMetaStateHolder, ExecutionPlanContext executionPlanContext,
                                  List<VariableExpressionExecutor> variableExpressionExecutors, Map<String, EventTable> eventTableMap) {
        return OperatorParser.constructOperator(expiredEventChunk, expression, matchingMetaStateHolder, executionPlanContext, variableExpressionExecutors, eventTableMap, queryName);
    }
}
