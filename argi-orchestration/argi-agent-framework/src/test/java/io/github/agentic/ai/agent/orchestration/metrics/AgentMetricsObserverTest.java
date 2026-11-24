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

import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.lifecycle.InvocationId;
import io.github.agentic.ai.agent.lifecycle.LifecycleEvent;
import io.github.agentic.ai.agent.lifecycle.LifecycleIdentity;
import io.github.agentic.ai.agent.lifecycle.LifecycleStage;
import io.github.agentic.ai.agent.lifecycle.ModelTurnId;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.agent.react.ReActAgent;
import io.github.agentic.ai.workflow.WorkflowAgent;
import io.github.agentic.ai.workflow.WorkflowDefinition;
import io.github.agentic.ai.workflow.WorkflowStep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #317 acceptance: one-shot terminal counters, bounded attempts, in-flight to zero. */
class AgentMetricsObserverTest {

	/** Unit level: two model calls form one terminal state and two attempts. */
	@Test
	void twoModelCallsFormOneRunTerminalAndTwoAttempts() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentMetricsObserver observer = new AgentMetricsObserver(registry, "react");
		LifecycleIdentity identity = LifecycleIdentity.builder(new InvocationId("run")).build();

		observer.onEvent(new LifecycleEvent.StageEntered(LifecycleStage.RUN_START, identity));
		for (int turn = 0; turn < 2; turn++) {
			LifecycleIdentity turnIdentity = LifecycleIdentity.builder(new InvocationId("run"))
				.modelTurn(new ModelTurnId("turn-" + turn))
				.build();
			observer.onEvent(new LifecycleEvent.StageEntered(LifecycleStage.MODEL_CALL, turnIdentity));
			observer.onEvent(new LifecycleEvent.StageExited(LifecycleStage.MODEL_CALL, turnIdentity, true, null));
		}
		observer.onEvent(new LifecycleEvent.StageExited(LifecycleStage.TERMINATION, identity, true, "completed"));

		assertThat(registry.counter(AgentMetricNames.RUN_STARTED, AgentMetricTags.ENGINE, "react").count())
			.isEqualTo(1.0);
		assertThat(registry.counter(AgentMetricNames.RUN_TERMINATED, AgentMetricTags.ENGINE, "react",
				AgentMetricTags.OUTCOME, "completed").count()).isEqualTo(1.0);
		assertThat(registry.counter(AgentMetricNames.ATTEMPT, AgentMetricTags.ENGINE, "react",
				AgentMetricTags.STAGE, "model-call").count()).isEqualTo(2.0);
		assertThat(registry.find(AgentMetricNames.RUN_IN_FLIGHT).gauge().value()).isZero();
		assertThat(registry.find(AgentMetricNames.STAGE_DURATION).tag("argi.agent.stage", "model-call").timer()
				.count()).isEqualTo(2L);
	}

	/** A repeated terminal signal for an already-terminated run never counts. */
	@Test
	void aRepeatedTerminalSignalDoesNotCountAndTheGaugeNeverGoesNegative() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentMetricsObserver observer = new AgentMetricsObserver(registry, "react");
		LifecycleIdentity identity = LifecycleIdentity.builder(new InvocationId("run")).build();

		observer.onEvent(new LifecycleEvent.StageEntered(LifecycleStage.RUN_START, identity));
		observer.onEvent(new LifecycleEvent.StageExited(LifecycleStage.TERMINATION, identity, true, "completed"));
		observer.onEvent(new LifecycleEvent.StageExited(LifecycleStage.TERMINATION, identity, true, "completed"));

		assertThat(registry.counter(AgentMetricNames.RUN_TERMINATED, AgentMetricTags.ENGINE, "react",
				AgentMetricTags.OUTCOME, "completed").count()).isEqualTo(1.0);
		assertThat(registry.find(AgentMetricNames.RUN_IN_FLIGHT).gauge().value()).isZero();
	}

	/** Cancellation brings the in-flight gauge back to zero. */
	@Test
	void cancellationBringsTheInFlightGaugeBackToZero() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentMetricsObserver observer = new AgentMetricsObserver(registry, "react");
		LifecycleIdentity identity = LifecycleIdentity.builder(new InvocationId("run")).build();

		observer.onEvent(new LifecycleEvent.StageEntered(LifecycleStage.RUN_START, identity));
		observer.onEvent(new LifecycleEvent.StageExited(LifecycleStage.TERMINATION, identity, false, "cancelled"));

		assertThat(registry.find(AgentMetricNames.RUN_IN_FLIGHT).gauge().value()).isZero();
		assertThat(registry.counter(AgentMetricNames.RUN_TERMINATED, AgentMetricTags.ENGINE, "react",
				AgentMetricTags.OUTCOME, "cancelled").count()).isEqualTo(1.0);
	}

	/** Full workflow chain: attempts counted per physical try, terminal counted once. */
	@Test
	void theWorkflowEngineDrivesTheRunAndAttemptMeters() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentMetricsObserver observer = new AgentMetricsObserver(registry, "workflow");
		AtomicInteger attempts = new AtomicInteger();
		Agent failingTwice = (request, context) -> subscriber -> {
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					if (demand > 0) {
						if (attempts.incrementAndGet() <= 2) {
							subscriber.onError(new IllegalStateException("boom " + attempts.get()));
						}
						else {
							subscriber.onNext(new AgentEvent.Finished(AgentOutcome.completed(
									List.of(ChatMessage.assistant("third")), Map.of("value", "v"),
									new Usage(1L, 1L, 2L))));
							subscriber.onComplete();
						}
					}
				}

				public void cancel() {
				}
			});
		};
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker", Map.of(), null,
						new WorkflowStep.RetryPolicy(3, Duration.ZERO))));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", failingTwice))
			.observers(observer)
			.build();

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
			assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.COMPLETED);
		}

		assertThat(registry.counter(AgentMetricNames.RUN_STARTED, AgentMetricTags.ENGINE, "workflow").count())
			.isEqualTo(1.0);
		assertThat(registry.counter(AgentMetricNames.ATTEMPT, AgentMetricTags.ENGINE, "workflow",
				AgentMetricTags.STAGE, "workflow-step").count()).isEqualTo(3.0);
		assertThat(registry.counter(AgentMetricNames.RUN_TERMINATED, AgentMetricTags.ENGINE, "workflow",
				AgentMetricTags.OUTCOME, "completed").count()).isEqualTo(1.0);
		assertThat(registry.find(AgentMetricNames.RUN_IN_FLIGHT).gauge().value()).isZero();
	}

	/** Full ReAct chain: two model turns are two attempts and one terminal state. */
	@Test
	void theReactEngineDrivesTheModelCallAttemptMeters() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentMetricsObserver observer = new AgentMetricsObserver(registry, "react");
		ChatClient scripted = new ChatClient() {
			private final List<ChatResponse> turns = List.of(
					new ChatResponse("id", "model", List.of(ChatMessage.assistant("calling")),
							List.of(new ToolCall("c1", "lookup", "{}")), FinishReason.STOP, new Usage(1L, 1L, 2L), Map.of()),
					new ChatResponse("id", "model", List.of(ChatMessage.assistant("done")), List.of(),
							FinishReason.STOP, new Usage(2L, 2L, 4L), Map.of()));
			private final AtomicInteger index = new AtomicInteger();

			@Override
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return CompletableFuture.completedFuture(this.turns.get(Math.min(this.index.getAndIncrement(), 1)));
			}

			@Override
			public Flow.Publisher<io.github.agentic.ai.api.chat.ChatEvent> stream(ChatRequest request) {
				throw new AssertionError("Expected the non-streaming model path");
			}

			@Override
			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
		Tool lookup = new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", "lookup", "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("found"));
			}
		};
		ReActAgent agent = ReActAgent.builder(scripted)
			.tools(new ToolRegistry(List.of(lookup)))
			.observers(observer)
			.build();

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = agent
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(5, TimeUnit.SECONDS);
			assertThat(outcome.status()).isEqualTo(AgentOutcome.Status.COMPLETED);
		}

		assertThat(registry.counter(AgentMetricNames.ATTEMPT, AgentMetricTags.ENGINE, "react",
				AgentMetricTags.STAGE, "model-call").count()).isEqualTo(2.0);
		assertThat(registry.counter(AgentMetricNames.ATTEMPT, AgentMetricTags.ENGINE, "react",
				AgentMetricTags.STAGE, "tool-call").count()).isEqualTo(1.0);
		assertThat(registry.counter(AgentMetricNames.RUN_TERMINATED, AgentMetricTags.ENGINE, "react",
				AgentMetricTags.OUTCOME, "completed").count()).isEqualTo(1.0);
		assertThat(registry.find(AgentMetricNames.RUN_IN_FLIGHT).gauge().value()).isZero();
	}

	/** Cancellation of a real workflow run ends the in-flight gauge and counts one terminal. */
	@Test
	void aCancelledWorkflowRunEndsInFlightWithOneCancelledTerminal() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentMetricsObserver observer = new AgentMetricsObserver(registry, "workflow");
		CountDownLatch entered = new CountDownLatch(1);
		Agent blocking = (request, context) -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
			private boolean started;

			public void request(long demand) {
				if (demand <= 0 || this.started) {
					return;
				}
				this.started = true;
				entered.countDown();
				// A cooperative cancellation surfaces as an error, which lets the
				// workflow emit its TERMINATION(cancelled) from the worker thread.
				context.control().onLoss(() -> subscriber.onError(new IllegalStateException("cancelled")));
			}

			public void cancel() {
			}
		});
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition).agents(Map.of("worker", blocking))
			.observers(observer)
			.build();
		AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
		try (AgentExecutionContext context = context()) {
			workflow.stream(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.subscribe(new Flow.Subscriber<AgentEvent>() {
					public void onSubscribe(Flow.Subscription started) {
						subscription.set(started);
						started.request(1);
					}

					public void onNext(AgentEvent event) {
					}

					public void onError(Throwable error) {
					}

					public void onComplete() {
					}
				});
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			subscription.get().cancel();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (cancelledTerminals(registry) < 1 && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
		}

		assertThat(cancelledTerminals(registry)).isEqualTo(1.0);
		assertThat(registry.find(AgentMetricNames.RUN_IN_FLIGHT).gauge().value()).isZero();
	}

	private static double cancelledTerminals(SimpleMeterRegistry registry) {
		return registry.counter(AgentMetricNames.RUN_TERMINATED, AgentMetricTags.ENGINE, "workflow",
				AgentMetricTags.OUTCOME, "cancelled").count();
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run")
			.correlationId("correlation")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

}
