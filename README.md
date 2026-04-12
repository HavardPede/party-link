# Party Link

A RuneLite plugin that lets external tools — web dashboards, Discord bots, or tournament software — control your in-game party automatically.

---

## What does it do?

Old School RuneScape has a built-in party system that lets players group up and share stats. Normally, managing who's in a party requires everyone to be in the same Discord or coordinating manually in-game.

Party Link removes that friction. Once you install the plugin and connect it to a compatible service, that service can:

- Automatically move you into the right party for an event or tournament
- Kick or reassign players without anyone needing to do it manually in-game
- Keep party membership in sync with a web dashboard or Discord bot in real time

You just install the plugin, pair it once, and it works in the background.

---

## Getting started

1. Install **Party Link** from the RuneLite Plugin Hub
2. Open the plugin settings and enter the **server URL** provided by the service you're using
3. Paste the **pairing code** from that service's website or bot
4. Enable the plugin — it will connect automatically and start receiving commands

That's it. The plugin runs silently in the background and handles party changes as they come in.

---

## Troubleshooting

**Not connecting or commands not working?**

1. Go to the service's website and **unlink your RuneLite client**
2. Generate a **new pairing code**
3. Paste the new code into the plugin settings and re-pair

**Still having issues?**

Try logging out and back into the game. Some party state gets stuck and a fresh login clears it.

---

## For developers

Party Link is server-agnostic — it implements a simple WebSocket-based protocol called **Remote Party Control (RPC)**. Any server that speaks the protocol can issue commands to the plugin.

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│  RuneLite    │◄───────►│    Server    │◄───────►│   Web UI /   │
│  + Plugin    │   RPC   │   (yours)    │         │   Bot / CLI  │
└──────────────┘         └──────────────┘         └──────────────┘
```

How it works:
1. A player **pairs** their client with a server using a one-time code
2. The server issues a **bearer token** for future reconnections
3. The plugin holds a persistent **WebSocket connection** to the server
4. The server **pushes commands** in real time — no polling
5. Each command is **acknowledged** after execution

Full protocol specification: **[docs/protocol.md](docs/protocol.md)**

### Building from source

```bash
./gradlew build
```

---

## License

BSD 2-Clause. See [LICENSE](LICENSE).
