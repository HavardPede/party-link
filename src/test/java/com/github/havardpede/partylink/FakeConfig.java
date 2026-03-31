package com.github.havardpede.partylink;

class FakeConfig implements LinkConfig {
	String serverUrl;
	boolean isEnabled;

	FakeConfig(String serverUrl, boolean isEnabled) {
		this.serverUrl = serverUrl;
		this.isEnabled = isEnabled;
	}

	@Override
	public String serverUrl() {
		return serverUrl;
	}

	@Override
	public String pairingKey() {
		return "";
	}

	@Override
	public boolean enabled() {
		return isEnabled;
	}
}
