/**
 * Copyright 2016-2018 the original author or authors.
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonDataObjectConverter implements DataObjectConverter {

	private final ObjectMapper objectMapper;

	public JacksonDataObjectConverter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(SseEvent event) {
		return true;
	}

	@Override
	public String convert(SseEvent event) {
		if (event.data() != null) {
			try {
				if (!event.jsonView().isPresent()) {
					return this.objectMapper.writeValueAsString(event.data());
				}

				return this.objectMapper.writerWithView(event.jsonView().get())
						.writeValueAsString(event.data());
			}
			catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		}
		return null;
	}

}
