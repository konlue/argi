/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.agent.orchestration.metrics;

import java.time.Clock;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.agentic.ai.agent.lifecycle.LifecycleEvent;
import io.github.agentic.ai.agent.lifecycle.LifecycleIdentity;
import io.github.agentic.ai.agent.lifecycle.LifecycleObserver;
import io.github.agentic.ai.agent.lifecycle.LifecycleStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * Turns the lifecycle events of the ReAct and Workflow engines into the run/attempt
 * metric vocabulary of issue #317 — process-transition counters and in-flight gauges
 * that die with the JVM (issue #331's package-info separates them from the persisted
 * stock). Trace sampling is irrelevant: the meters are driven by the lifecycle events
 * themselves, never by spans.
 * <p>
 * Semantics: a run start accepts the run and raises the in-flight gauge; a TERMINATION
 * exit lowers it (never below zero) and, only when it was still in flight, increments
 * the one-shot terminal counter — a repeated terminal signal never counts. Enter events
 * whose identity carries an attempt, model turn or tool call count physical attempts.
 * Enter/exit pairs time the stage on this observer's clock.
 * <p>
 * Tokens are deliberately not metered here: usage breakdown and its no-duplicate
 * aggregation belong to issue #249.
 */
public final class AgentMetricsObserver implements LifecycleObserver {

	private final MeterRegistry registry;

	private final String engine;

	private final Clock clock;

	private final AtomicLong inFlight = new AtomicLong();

	/** Enter timestamps per thread; enter/exit run on the executing thread (see #322). */
	private final ThreadLocal<Deque<Long>> stageStarts = ThreadLocal.withInitial(ConcurrentLinkedDeque::new);

	public AgentMetricsObserver(MeterRegistry registry, String engine) {
		this(registry, engine, Clock.systemUTC());
	}

	public AgentMetricsObserver(MeterRegistry registry, String engine, Clock clock) {
		this.registry = registry;
		this.engine = engine;
		this.clock = clock;
	}

	@Override
	public Set<LifecycleStage> stages() {
		return Set.of(LifecycleStage.RUN_START, LifecycleStage.TERMINATION, LifecycleStage.MODEL_CALL,
				LifecycleStage.TOOL_CALL, LifecycleStage.WORKFLOW_STEP);
	}

	@Override
	public String id() {
		return "agent-metrics[" + this.engine + "]";
	}

	@Override
	public void onEvent(LifecycleEvent event) {
		if (event instanceof LifecycleEvent.StageEntered entered) {
			onStageEntered(event.stage(), entered.identity());
		}
		else if (event instanceof LifecycleEvent.StageExited exited) {
			onStageExited(event.stage(), exited.identity(), exited.successful(), exited.failureReason());
		}
	}

	private void onStageEntered(LifecycleStage stage, LifecycleIdentity identity) {
		if (stage == LifecycleStage.RUN_START) {
			this.registry.counter(AgentMetricNames.RUN_STARTED, AgentMetricTags.ENGINE, this.engine).increment();
			this.inFlight.incrementAndGet();
			this.registry.gauge(AgentMetricNames.RUN_IN_FLIGHT, List.of(Tag.of(AgentMetricTags.ENGINE, this.engine)),
					this.inFlight);
			return;
		}
		if (attemptOf(stage, identity) != null) {
			this.registry.counter(AgentMetricNames.ATTEMPT, AgentMetricTags.ENGINE, this.engine,
					AgentMetricTags.STAGE, stageLabel(stage)).increment();
		}
		this.stageStarts.get().push(this.clock.instant().toEpochMilli());
	}

	private void onStageExited(LifecycleStage stage, LifecycleIdentity identity, boolean successful,
			String failureReason) {
		Long start = this.stageStarts.get().poll();
		if (stage == LifecycleStage.TERMINATION) {
			long previous = this.inFlight.getAndUpdate(current -> Math.max(0, current - 1));
			if (previous > 0) {
				// One-shot: only the transition that actually lowered a positive gauge
				// counts the terminal state; repeated signals never do.
				this.registry.counter(AgentMetricNames.RUN_TERMINATED, AgentMetricTags.ENGINE, this.engine,
						AgentMetricTags.OUTCOME, outcomeOf(successful, failureReason)).increment();
			}
		}
		if (start != null) {
			long elapsed = this.clock.instant().toEpochMilli() - start;
			if (elapsed >= 0) {
				this.registry.timer(AgentMetricNames.STAGE_DURATION, AgentMetricTags.ENGINE, this.engine,
						AgentMetricTags.STAGE, stageLabel(stage)).record(elapsed, TimeUnit.MILLISECONDS);
			}
		}
	}

	/**
	 * The attempt dimension of a stage entry: model turns, tool calls and workflow
	 * attempts carry their own identifier; step-level and run-level events do not.
	 */
	private String attemptOf(LifecycleStage stage, LifecycleIdentity identity) {
		return switch (stage) {
			case MODEL_CALL -> identity.modelTurn() == null ? null : identity.modelTurn().value();
			case TOOL_CALL -> identity.toolCall() == null ? null : identity.toolCall().value();
			case WORKFLOW_STEP -> identity.attempt() == null ? null : identity.attempt().value();
			default -> null;
		};
	}

	private static String stageLabel(LifecycleStage stage) {
		return stage.name().toLowerCase().replace('_', '-');
	}

	private static String outcomeOf(boolean successful, String failureReason) {
		if (successful) {
			return failureReason == null ? "completed" : failureReason;
		}
		return "cancelled".equals(failureReason) ? "cancelled" : "failed";
	}

}
