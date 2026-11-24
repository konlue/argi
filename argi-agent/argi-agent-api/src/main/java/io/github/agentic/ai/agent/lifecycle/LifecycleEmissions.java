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
package io.github.agentic.ai.agent.lifecycle;

import java.util.List;

/**
 * Shared emission helpers for engines that drive lifecycle events (issues #322 and
 * #317). Every callback is isolated per the {@link LifecycleObserver} contract: an
 * observer that throws never reaches the business execution and never breaks the
 * delivery to the other observers — its failure is reported to them as
 * {@link LifecycleEvent.ExtensionFailed}, and a failure raised while reporting is
 * itself swallowed.
 */
public final class LifecycleEmissions {

	private LifecycleEmissions() {
	}

	/**
	 * The observers that declared the given stage in their own {@code stages()} binding.
	 * @param observers all registered observers, may be empty
	 * @param stage the stage about to be emitted
	 * @return the bound subset, never null
	 */
	public static List<LifecycleObserver> bound(List<LifecycleObserver> observers, LifecycleStage stage) {
		return observers.stream().filter(observer -> observer.stages().contains(stage)).toList();
	}

	/**
	 * Deliver one event to every bound observer, isolating each callback: a throwing
	 * observer is skipped for this event and the remaining observers still receive it.
	 * @param observers the bound observers, may be empty
	 * @param event the event to deliver
	 * @param identity the identity of the work the event describes
	 */
	public static void emitAll(List<LifecycleObserver> observers, LifecycleEvent event, LifecycleIdentity identity) {
		for (LifecycleObserver observer : observers) {
			try {
				observer.onEvent(event);
			}
			catch (RuntimeException failure) {
				reportExtensionFailed(observers, observer, event, identity, failure);
			}
		}
	}

	private static void reportExtensionFailed(List<LifecycleObserver> observers, LifecycleObserver failing,
			LifecycleEvent event, LifecycleIdentity identity, RuntimeException failure) {
		LifecycleEvent report = new LifecycleEvent.ExtensionFailed(event.stage(), identity, failing.id(),
				failure.getClass().getSimpleName() + ": " + failure.getMessage());
		for (LifecycleObserver observer : observers) {
			if (observer == failing || !observer.stages().contains(event.stage())) {
				continue;
			}
			try {
				observer.onEvent(report);
			}
			catch (RuntimeException ignored) {
				// A failing failure-report must never reach the business execution.
			}
		}
	}

}
