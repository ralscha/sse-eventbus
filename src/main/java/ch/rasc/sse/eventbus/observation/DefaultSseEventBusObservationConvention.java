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

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * Default observation convention for {@code SseEventBus} operations.
 */
public class DefaultSseEventBusObservationConvention implements SseEventBusObservationConvention {

	@Override
	public String getContextualName(SseEventBusObservationContext context) {
		return "sse.eventbus " + context.getOperation().value();
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(SseEventBusObservationContext context) {
		return KeyValues.of(KeyValue.of("operation", context.getOperation().value()),
				KeyValue.of("outcome", context.getOutcome()),
				KeyValue.of("mode", context.isDirectEvent() ? "direct" : "broadcast"),
				KeyValue.of("replay", Boolean.toString(context.isReplay())));
	}

	@Override
	public KeyValues getHighCardinalityKeyValues(SseEventBusObservationContext context) {
		KeyValues keyValues = KeyValues.empty();
		if (context.getClientId() != null) {
			keyValues = keyValues.and("client.id", context.getClientId());
		}
		if (context.getEventName() != null) {
			keyValues = keyValues.and("event.name", context.getEventName());
		}
		keyValues = keyValues.and("delivery.count", Integer.toString(context.getDeliveryCount()));
		keyValues = keyValues.and("attempt", Integer.toString(context.getAttempt()));
		keyValues = keyValues.and("complete_after_message", Boolean.toString(context.isCompleteAfterMessage()));
		keyValues = keyValues.and("last_event_id_present", Boolean.toString(context.isLastEventIdPresent()));
		return keyValues;
	}

}