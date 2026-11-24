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

/**
 * The run/step/attempt metric vocabulary (issue #317). Every name is a new
 * {@code argi.agent.*} meter; the historical {@code argi.graph.*} names are never
 * reused. Tokens are deliberately absent — usage accounting belongs to issue #249 and
 * double-counting tokens here would break the no-duplicate-aggregation contract.
 */
public final class AgentMetricNames {

	/** Counter of accepted runs, one per delivery. Tags: {@code engine}. */
	public static final String RUN_STARTED = "argi.agent.run.started";

	/**
	 * Counter of one-shot run terminal states. Tags: {@code engine},
	 * {@code outcome}. A repeated terminal signal for an already-terminated run never
	 * increments this counter.
	 */
	public static final String RUN_TERMINATED = "argi.agent.run.terminated";

	/**
	 * Gauge of runs currently in flight. Tags: {@code engine}. Cancellation brings it
	 * back to zero.
	 */
	public static final String RUN_IN_FLIGHT = "argi.agent.run.inflight";

	/**
	 * Counter of physical attempts. Tags: {@code engine}, {@code stage}. Two model
	 * calls are two attempts; a retried step is one more attempt per try.
	 */
	public static final String ATTEMPT = "argi.agent.attempt";

	/**
	 * Timer of stage execution time. Tags: {@code engine}, {@code stage}. Base unit:
	 * seconds.
	 */
	public static final String STAGE_DURATION = "argi.agent.stage.duration";

	private AgentMetricNames() {
	}

}
