package com.example.link;

class FakeConfig implements LinkConfig {
	String token;
	String serverUrl;
	boolean isEnabled;

	FakeConfig(String token, boolean isEnabled) {
		this(token, "http://localhost:3000", isEnabled);
	}

	FakeConfig(String token, String serverUrl, boolean isEnabled) {
		this.token = token;
		this.serverUrl = serverUrl;
		this.isEnabled = isEnabled;
	}

	@Override
	public String bearerToken() {
		return token;
	}

	@Override
	public String serverUrl() {
		return serverUrl;
	}

	@Override
	public boolean enabled() {
		return isEnabled;
	}
}
