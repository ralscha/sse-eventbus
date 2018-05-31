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

import com.fasterxml.jackson.annotation.JsonView;

public class TestObject3 {

	@JsonView(JsonViews.PUBLIC.class)
	private String uuid;

	@JsonView(JsonViews.PUBLIC.class)
	private String publicInfo;

	@JsonView(JsonViews.PRIVATE.class)
	private int privateData;

	public String getUuid() {
		return this.uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public String getPublicInfo() {
		return this.publicInfo;
	}

	public void setPublicInfo(String publicInfo) {
		this.publicInfo = publicInfo;
	}

	public int getPrivateData() {
		return this.privateData;
	}

	public void setPrivateData(int privateData) {
		this.privateData = privateData;
	}

}
