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
