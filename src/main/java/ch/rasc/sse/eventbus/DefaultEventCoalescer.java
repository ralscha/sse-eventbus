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
 * and the same event name. Their payload is taken from the converted value when present,
 * or directly from a String payload (the send path writes String payloads as-is). The
 * data is joined with a line break after normalizing CRLF and CR to LF, matching SSE
 * encoding. Clients receive one event with newline-separated data, so event boundaries
 * change and concatenated JSON documents are not a single JSON value. Unconverted
 * non-String payloads are never merged.
 */
public class DefaultEventCoalescer implements EventCoalescer {

	@Override
	public @Nullable ClientEvent coalesce(ClientEvent first, ClientEvent second) {
		SseEvent firstEvent = first.getSseEvent();
		SseEvent secondEvent = second.getSseEvent();
		if (!firstEvent.event().equals(secondEvent.event())) {
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
		String mergedData = normalizeLineEndings(firstData) + "\n" + normalizeLineEndings(secondData);
		SseEvent merged = SseEvent.builder().event(firstEvent.event()).data(mergedData).build();
		return new ClientEvent(first.getClient(), merged, mergedData);
	}

	private static String normalizeLineEndings(String data) {
		return data.replace("\r\n", "\n").replace('\r', '\n');
	}

	private static @Nullable String eventData(ClientEvent event) {
		String converted = event.getConvertedValue();
		if (converted != null) {
			return converted;
		}
		// A String payload is written as-is by the send path (the converted value stays
		// null), so it can be merged directly. Unconverted non-String payloads are never
		// merged: stringifying them here could differ from the converter's output.
		Object data = event.getSseEvent().data();
		return data instanceof String string ? string : null;
	}

}
