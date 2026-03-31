# Remote Party Control (RPC) Protocol

This document specifies the RPC protocol used between the Party Link RuneLite plugin and any compliant server.

## Overview

RPC is a simple request-response protocol for delivering party management commands to a game client. The plugin authenticates with a server, polls for pending commands, executes them, and acknowledges each one.

### Current: HTTP Polling

The plugin polls `GET /api/plugin/commands` on a fixed interval (default 5 seconds) and processes any commands returned. On consecutive failures, the interval backs off exponentially up to 60 seconds, resetting on the next successful poll.

### Future: WebSocket

The polling approach works but has trade-offs:

- **Latency** — commands wait up to one full poll interval before delivery
- **Overhead** — most polls return an empty response, wasting bandwidth on both sides
- **Scalability** — each connected client generates steady request volume regardless of activity

A future version will replace polling with a persistent WebSocket connection. The server pushes commands as they occur, and the plugin acknowledges them over the same connection. The command format and semantics remain the same — only the transport changes.

## Authentication

### Pairing Flow

Pairing links a game client to a user account on the server. It happens once.

```
Plugin                          Server
  │                               │
  │  POST /api/plugin/pair        │
  │  { "code": "<pairing-code>" } │
  │──────────────────────────────►│
  │                               │
  │  200 { "token": "<bearer>" }  │
  │◄──────────────────────────────│
```

1. The user generates a one-time pairing code on the server (via web UI, bot, etc.)
2. The user pastes the code into the plugin's config panel in RuneLite
3. The plugin sends the code to `POST /api/plugin/pair`
4. The server validates the code and returns a bearer token
5. The plugin stores the token and uses it for all future requests

Clearing the pairing code in the plugin config unpairs the client (the token is discarded locally).

### Request Authentication

All requests after pairing include the bearer token:

```
Authorization: Bearer <token>
```

The plugin also sends the player's in-game name when available:

```
X-Player-Name: <rsn>
```

## Endpoints

### `POST /api/plugin/pair`

Exchange a one-time pairing code for a bearer token.

**Request:**
```json
{
  "code": "abc123"
}
```

**Response (200):**
```json
{
  "token": "eyJhbGciOi..."
}
```

**Errors:**
- `401` — invalid or expired pairing code

---

### `GET /api/plugin/commands`

Fetch pending commands for the authenticated client.

**Headers:**
```
Authorization: Bearer <token>
X-Player-Name: <rsn>
```

**Response (200):**
```json
{
  "commands": [
    {
      "id": "cmd_01",
      "type": "JOIN_PARTY",
      "passphrase": "party-abc-123"
    },
    {
      "id": "cmd_02",
      "type": "LEAVE_PARTY",
      "reason": "KICKED"
    }
  ]
}
```

An empty `commands` array (or `{}`) means no pending commands.

---

### `POST /api/plugin/commands/{id}/ack`

Acknowledge that a command has been executed.

**Headers:**
```
Authorization: Bearer <token>
X-Player-Name: <rsn>
```

**Request body:** `{}` (empty JSON object)

**Response:** `200` on success.

The server should treat an acknowledged command as delivered and not return it on future polls.

---

### `POST /api/plugin/rsn`

Register the player's current in-game name with the server. Sent automatically when the plugin detects the player has logged in.

**Headers:**
```
Authorization: Bearer <token>
```

**Request body:**
```json
{
  "playerName": "Zezima"
}
```

**Response:** `200` on success.

## Command Types

### `JOIN_PARTY`

Instructs the client to join a RuneLite party.

| Field        | Type   | Required | Description                          |
|--------------|--------|----------|--------------------------------------|
| `id`         | string | yes      | Unique command identifier            |
| `type`       | string | yes      | `"JOIN_PARTY"`                       |
| `passphrase` | string | yes      | The RuneLite party passphrase to join |

### `LEAVE_PARTY`

Instructs the client to leave their current RuneLite party.

| Field    | Type   | Required | Description                          |
|----------|--------|----------|--------------------------------------|
| `id`     | string | yes      | Unique command identifier            |
| `type`   | string | yes      | `"LEAVE_PARTY"`                      |
| `reason` | string | no       | Why the player is leaving            |

**Leave reasons:**

| Reason   | Description                          |
|----------|--------------------------------------|
| `KICKED` | The player was removed by the leader |
| `CLOSED` | The party was disbanded              |
| `LEFT`   | The player left voluntarily          |

## Error Handling

- **Non-2xx responses** on poll cause the plugin to back off exponentially (5s → 10s → 20s → ... → 60s max)
- **Successful polls** reset the interval to 5 seconds
- **ACK failures** are logged but do not affect the poll cycle — the server may redeliver unacknowledged commands
- **Command IDs** must match `^[a-zA-Z0-9_-]+$` — the plugin rejects IDs that don't match this pattern

## Implementing a Server

A minimal compliant server needs to:

1. Generate and validate one-time pairing codes
2. Issue and verify bearer tokens
3. Queue commands per client and return them on `GET /commands`
4. Remove commands from the queue when acknowledged
5. Accept RSN registration via `POST /rsn`

The server owns all party logic — who joins what, when to kick, how parties are organized. The plugin is a thin executor that does what it's told.
