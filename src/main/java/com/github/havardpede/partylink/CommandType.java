package com.github.havardpede.partylink;

enum CommandType {
	JOIN_PARTY,
	LEAVE_PARTY,
	ROLE_CHANGE;

	static CommandType fromString(String value) {
		try {
			return valueOf(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
