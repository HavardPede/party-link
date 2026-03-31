package com.github.havardpede.partylink;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

class Command {
	final String id;
	final CommandType type;
	final String passphrase;
	final LeaveReason reason;

	private Command(String id, CommandType type, String passphrase, LeaveReason reason) {
		this.id = id;
		this.type = type;
		this.passphrase = passphrase;
		this.reason = reason;
	}

	static List<Command> listFromJson(String json) {
		JsonArray array = extractCommandsArray(json);
		List<Command> commands = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			commands.add(fromJsonObject(array.get(i).getAsJsonObject()));
		}
		return commands;
	}

	private static JsonArray extractCommandsArray(String json) {
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		if (!root.has("commands")) {
			return new JsonArray();
		}
		return root.getAsJsonArray("commands");
	}

	private static Command fromJsonObject(JsonObject obj) {
		return new Command(
				obj.get("id").getAsString(),
				CommandType.fromString(obj.get("type").getAsString()),
				stringOrNull(obj, "passphrase"),
				LeaveReason.fromString(stringOrNull(obj, "reason")));
	}

	private static String stringOrNull(JsonObject obj, String key) {
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return null;
		}
		return obj.get(key).getAsString();
	}
}

enum CommandType {
	JOIN_PARTY,
	LEAVE_PARTY;

	static CommandType fromString(String value) {
		try {
			return valueOf(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
