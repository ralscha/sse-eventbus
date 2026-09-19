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
package ch.rasc.sse.eventbus;

import org.jspecify.annotations.Nullable;

/**
 * Default {@link EventCoalescer} that merges plain data events.
 * <p>
 * Two events are merged when both have no event id, no retry, no comment, no JSON view
 * and the same event name. Their data is joined with a line break, which the SSE
 * protocol encodes as multiple {@code data:} lines of a single event, so a client that
 * concatenates the data lines receives the original data in order. Events without data
 * are never merged.
 */
public class DefaultEventCoalescer implements EventCoalescer {

	@Override
	public @Nullable ClientEvent coalesce(ClientEvent first, ClientEvent second) {
		SseEvent firstEvent = first.getSseEvent();
		SseEvent secondEvent = second.getSseEvent();
		if (firstEvent.event() != null && secondEvent.event() != null
				&& !firstEvent.event().equals(secondEvent.event())) {
			return null;
		}
		if (firstEvent.id().isPresent() || secondEvent.id().isPresent() || firstEvent.retry().isPresent()
				|| secondEvent.retry().isPresent() || firstEvent.comment().isPresent()
				|| secondEvent.comment().isPresent() || firstEvent.jsonView().isPresent()
				|| secondEvent.jsonView().isPresent()) {
			return null;
		}
		String firstData = eventData(first);
		String secondData = eventData(second);
		if (firstData == null || secondData == null) {
			return null;
		}
		String mergedData = firstData + "\n" + secondData;
		SseEvent merged = SseEvent.builder()
			.event(firstEvent.event())
			.data(mergedData)
			.build();
		return new ClientEvent(first.getClient(), merged, mergedData);
	}

	private static @Nullable String eventData(ClientEvent event) {
		String converted = event.getConvertedValue();
		if (converted != null) {
			return converted;
		}
		Object data = event.getSseEvent().data();
		return data != null ? String.valueOf(data) : null;
	}

}
