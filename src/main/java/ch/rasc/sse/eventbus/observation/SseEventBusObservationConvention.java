/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.rasc.sse.eventbus.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;

/**
 * Convention SPI for customizing observations emitted by {@code SseEventBus}.
 */
public interface SseEventBusObservationConvention extends ObservationConvention<SseEventBusObservationContext> {

	@Override
	default boolean supportsContext(Observation.Context context) {
		return context instanceof SseEventBusObservationContext;
	}

	@Override
	default String getName() {
		return "sse.eventbus";
	}

}