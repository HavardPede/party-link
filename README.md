# Party Link

A RuneLite plugin that lets you control in-game parties from an external server. Built on a simple command protocol — **Remote Party Control (RPC)** — that any server can implement.

## What is this?

Old School RuneScape has a built-in party system (via RuneLite) that lets players group up to share stats, locations, and other data. But managing parties in-game is clunky — there's no way to orchestrate who joins what party from outside the game client.

Party Link bridges that gap. It connects your RuneLite client to an external server, which can issue commands like "join this party" or "you've been kicked." This enables things like:

- Web dashboards for party leaders to manage members
- Automated party assignment for events and tournaments
- Discord bots that can shuffle players between parties

## How it works

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│  RuneLite    │◄───────►│    Server    │◄───────►│   Web UI /   │
│  + Plugin    │   RPC   │   (yours)    │         │   Bot / CLI  │
└──────────────┘         └──────────────┘         └──────────────┘
```

1. A player **pairs** their game client with a server using a one-time code
2. The server issues the player a **bearer token** for future requests
3. The plugin **polls** the server for pending commands and executes them in-game
4. Each command is **acknowledged** after execution, so the server knows it landed

The plugin is server-agnostic — point it at any server that implements the RPC protocol and it works.

## The RPC Protocol

Remote Party Control (RPC) is the protocol that defines how the plugin and server communicate. It covers authentication, command delivery, and acknowledgment.

The current implementation uses HTTP polling. A future version will move to WebSockets for real-time delivery and lower overhead — the protocol is designed with that transition in mind.

Full specification: **[docs/protocol.md](docs/protocol.md)**

## Setup

1. Install the plugin from the RuneLite Plugin Hub
2. In the plugin settings, enter your server URL
3. Paste the pairing code from your server's web interface
4. Enable sync — the plugin will start receiving commands

## Building

```bash
./gradlew build
```

## License

MIT
