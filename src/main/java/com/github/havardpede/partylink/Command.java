package com.github.havardpede.partylink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class Command {
	final String id;
	final CommandType type;
	final String passphrase;
	final LeaveReason reason;
	final String role;

	private Command(
			String id, CommandType type, String passphrase, LeaveReason reason, String role) {
		this.id = id;
		this.type = type;
		this.passphrase = passphrase;
		this.reason = reason;
		this.role = role;
	}

	static Command fromJson(String json) {
		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
		return new Command(
				obj.get("id").getAsString(),
				CommandType.fromString(obj.get("command").getAsString()),
				stringOrNull(obj, "passphrase"),
				LeaveReason.fromString(stringOrNull(obj, "reason")),
				stringOrNull(obj, "role"));
	}

	private static String stringOrNull(JsonObject obj, String key) {
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return null;
		}
		return obj.get(key).getAsString();
	}
}
