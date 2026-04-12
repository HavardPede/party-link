package com.github.havardpede.partylink;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class CommandExecutor {
	private final Consumer<String> changeParty;
	private final Consumer<String> sendChatMessage;

	CommandExecutor(Consumer<String> changeParty, Consumer<String> sendChatMessage) {
		this.changeParty = changeParty;
		this.sendChatMessage = sendChatMessage;
	}

	void execute(Command command) {
		if (command.type == null) {
			log.warn("Unknown command type (command={})", command.id);
			return;
		}

		switch (command.type) {
			case JOIN_PARTY:
				log.info("Executing JOIN_PARTY (command={}, role={})", command.id, command.role);
				changeParty.accept(command.passphrase);
				if (command.role != null) {
					sendChatMessage.accept(
							"You have joined the party. Your role is " + command.role + ".");
				} else {
					sendChatMessage.accept("You have joined the party.");
				}
				break;
			case LEAVE_PARTY:
				log.info(
						"Executing LEAVE_PARTY (command={}, reason={})",
						command.id,
						command.reason);
				changeParty.accept(null);
				if (command.reason == LeaveReason.KICKED) {
					sendChatMessage.accept("You have been kicked from the party.");
				} else if (command.reason == LeaveReason.CLOSED) {
					sendChatMessage.accept("The party has been closed by the leader.");
				}
				break;
			case ROLE_CHANGE:
				log.info("Executing ROLE_CHANGE (command={}, role={})", command.id, command.role);
				sendChatMessage.accept("Your role has been changed to " + command.role + ".");
				break;
		}
	}
}
