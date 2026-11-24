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
 * The bounded tag set for the {@link AgentMetricNames} meters (issue #317; the
 * cardinality budget itself belongs to issue #318). Every tag value is drawn from a
 * fixed set — no run, tenant or step identifier ever becomes a tag.
 */
public final class AgentMetricTags {

	/** Which engine delivered the run: {@code react} or {@code workflow}. */
	public static final String ENGINE = "argi.agent.engine";

	/** Terminal kind of a run: {@code completed}, {@code suspended}, {@code cancelled} or {@code failed}. */
	public static final String OUTCOME = "argi.agent.outcome";

	/** Which stage the attempt or duration belongs to: {@code model_call}, {@code tool_call}, {@code workflow_step}. */
	public static final String STAGE = "argi.agent.stage";

	private AgentMetricTags() {
	}

}
