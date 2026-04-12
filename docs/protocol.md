# Remote Party Control (RPC) Protocol

This document specifies the RPC protocol used between the Party Link RuneLite plugin and any compliant server.

## Overview

RPC is a WebSocket-based protocol for delivering party management commands to a game client. The plugin establishes a persistent connection, authenticates, identifies itself, and then receives and executes commands in real time.

```
Plugin                            Server
  │                                  │
  │  WebSocket connect               │
  │─────────────────────────────────►│
  │                                  │
  │  PAIR or AUTH                    │
  │─────────────────────────────────►│
  │                                  │
  │  PAIR_OK / PAIR_ERROR            │
  │  AUTH_OK / AUTH_ERROR            │
  │◄─────────────────────────────────│
  │                                  │
  │  IDENTIFY (once authenticated)   │
  │─────────────────────────────────►│
  │                                  │
  │  COMMAND                         │
  │◄─────────────────────────────────│
  │                                  │
  │  ACK                             │
  │─────────────────────────────────►│
```

All messages are JSON objects with a `type` field that identifies the message kind.

## Connection

The plugin connects to a configurable WebSocket URL. The URL must begin with `wss://` or `ws://`.

On `onOpen`, the plugin immediately sends either a `PAIR` or `AUTH` message depending on whether a pairing code or a stored token is available.

## Authentication

### Initial Pairing

Pairing links a game client to a user account on the server. It happens once per device. The user generates a one-time pairing code on the server (via web UI, bot, etc.) and pastes it into the plugin config.

```
Plugin                            Server
  │                                  │
  │  PAIR {"code":"ABCD-1234"}       │
  │─────────────────────────────────►│
  │                                  │
  │  PAIR_OK {"token":"..."}         │
  │◄─────────────────────────────────│
```

The returned token is persisted and used for all future connections. The pairing code is discarded after use.

If the code is invalid or expired:

```
Server → Plugin: PAIR_ERROR {"type":"PAIR_ERROR","reason":"Invalid code"}
```

The plugin does **not** reconnect after `PAIR_ERROR`.

### Subsequent Connections

On reconnects, the plugin authenticates with its stored token:

```
Plugin                            Server
  │                                  │
  │  AUTH {"token":"..."}            │
  │─────────────────────────────────►│
  │                                  │
  │  AUTH_OK                         │
  │◄─────────────────────────────────│
```

If the token is invalid:

```
Server → Plugin: AUTH_ERROR {"type":"AUTH_ERROR","reason":"Invalid token"}
```

The plugin does **not** reconnect after `AUTH_ERROR`.

## Message Reference

### Client → Server

#### `PAIR`

Exchange a one-time pairing code for a bearer token.

```json
{
  "type": "PAIR",
  "code": "ABCD-1234"
}
```

#### `AUTH`

Authenticate using a previously issued bearer token.

```json
{
  "type": "AUTH",
  "token": "eyJhbGciOi..."
}
```

#### `IDENTIFY`

Send the player's current RuneScape screen name to the server. Sent immediately after successful authentication, and queued if authentication hasn't completed yet.

```json
{
  "type": "IDENTIFY",
  "rsn": "Zezima"
}
```

#### `PARTY_STATE`

Notify the server of a party membership change. Sent whenever the plugin detects the player has joined or left a party.

Joined:
```json
{
  "type": "PARTY_STATE",
  "state": "JOINED",
  "passphrase": "coral-lime-oak-river"
}
```

Left:
```json
{
  "type": "PARTY_STATE",
  "state": "LEFT"
}
```

#### `ACK`

Acknowledge a received command. Sent immediately after the command has been executed.

```json
{
  "type": "ACK",
  "commandId": "cmd-1"
}
```

---

### Server → Client

#### `PAIR_OK`

Pairing succeeded. Contains the bearer token to store for future connections.

```json
{
  "type": "PAIR_OK",
  "token": "eyJhbGciOi..."
}
```

#### `PAIR_ERROR`

Pairing failed. The plugin will not reconnect.

```json
{
  "type": "PAIR_ERROR",
  "reason": "Invalid code"
}
```

#### `AUTH_OK`

Token authentication succeeded.

```json
{
  "type": "AUTH_OK"
}
```

#### `AUTH_ERROR`

Token authentication failed. The plugin will not reconnect.

```json
{
  "type": "AUTH_ERROR",
  "reason": "Invalid token"
}
```

#### `COMMAND`

Deliver a party management command to the client. The plugin executes it and responds with `ACK`.

```json
{
  "type": "COMMAND",
  "id": "cmd-1",
  "command": "JOIN_PARTY",
  "passphrase": "coral-lime-oak-river"
}
```

```json
{
  "type": "COMMAND",
  "id": "cmd-2",
  "command": "LEAVE_PARTY",
  "reason": "KICKED"
}
```

#### `ERROR`

Informational server error. The plugin logs it and continues.

```json
{
  "type": "ERROR",
  "message": "something went wrong"
}
```

## Command Types

### `JOIN_PARTY`

Instructs the client to join a RuneLite party.

| Field        | Type   | Required | Description                           |
|--------------|--------|----------|---------------------------------------|
| `id`         | string | yes      | Unique command identifier             |
| `command`    | string | yes      | `"JOIN_PARTY"`                        |
| `passphrase` | string | yes      | The RuneLite party passphrase to join |

### `LEAVE_PARTY`

Instructs the client to leave their current RuneLite party.

| Field     | Type   | Required | Description                          |
|-----------|--------|----------|--------------------------------------|
| `id`      | string | yes      | Unique command identifier            |
| `command` | string | yes      | `"LEAVE_PARTY"`                      |
| `reason`  | string | no       | Why the player is leaving            |

**Leave reasons:**

| Reason   | Description                          |
|----------|--------------------------------------|
| `KICKED` | The player was removed by the leader |
| `CLOSED` | The party was disbanded              |
| `LEFT`   | The player left voluntarily          |

### `ROLE_CHANGE`

Notifies the client that their role in the party has changed. The plugin displays the new role to the player but does not alter party membership.

| Field     | Type   | Required | Description                      |
|-----------|--------|----------|----------------------------------|
| `id`      | string | yes      | Unique command identifier        |
| `command` | string | yes      | `"ROLE_CHANGE"`                  |
| `role`    | string | yes      | The player's new role            |

## Connection Lifecycle

### Reconnection

The plugin reconnects automatically on network failure or server-initiated close, using exponential backoff:

- Starting interval: **5 seconds**
- Each failure doubles the interval, up to a cap of **60 seconds**
- A successful `AUTH_OK` resets the interval back to 5 seconds

The plugin does **not** reconnect after `AUTH_ERROR` or `PAIR_ERROR` — these indicate a configuration problem, not a transient network issue.

### Disconnection

Clearing the pairing token in the plugin config disconnects the client and disables reconnection until a new pairing is completed.

## Error Handling

| Scenario | Plugin behavior |
|---|---|
| `AUTH_ERROR` | Closes connection, no reconnect |
| `PAIR_ERROR` | Closes connection, no reconnect |
| Network failure | Reconnects with exponential backoff |
| Server-initiated close | Reconnects with exponential backoff |
| `ERROR` message | Logs warning, continues |
| ACK failure | Logs warning; server may redeliver unacknowledged commands |

## Implementing a Server

A minimal compliant server must:

1. Generate and validate one-time pairing codes
2. Issue and verify bearer tokens
3. Accept `PAIR` and `AUTH` messages on WebSocket open
4. Push `COMMAND` messages to connected clients
5. Handle `ACK` messages and stop redelivering acknowledged commands
6. Accept `IDENTIFY` messages and track which RSN belongs to which connection
7. Accept `PARTY_STATE` messages if party tracking is needed

The server owns all party logic — who joins what, when to kick, how parties are organized. The plugin is a thin executor that does what it's told.
