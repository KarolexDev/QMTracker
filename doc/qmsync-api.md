# QMSync HTTP API Contract (protocol version 1)

This document is the contract between the QMTracker mod (client) and the QMSync website (server).
The mod is already implemented against it (`impl/qmsync/`); the website must implement the two
endpoints below exactly as described.

## Overview

- The mod is a pure HTTP **client**. All requests originate from the player's machine.
- The player runs `/qmsync connect <baseUrl>` in-game. The mod POSTs a **handshake**; if the website
  answers `SYNCED`, the mod stores the base URL for that Minecraft server/world and starts uploading.
- Every ~5 seconds — only when the tracked data actually changed — the mod POSTs a **full snapshot**
  to the sync endpoint. The website is responsible for diffing against its previous state.
- The website is configured (by a human admin) with:
  - the **expected server id** it accepts data from, and
  - a **whitelist of player UUIDs**.
- All request bodies are JSON (`Content-Type: application/json`), UTF-8.
- Base URL may be `http://` or `https://` (http is intended for local testing only).

## Common identity fields

Every request body contains these fields:

| Field             | Type   | Description                                                                                        |
|-------------------|--------|----------------------------------------------------------------------------------------------------|
| `protocolVersion` | number | Currently `1`. Reject other values with HTTP 400.                                                  |
| `playerUuid`      | string | Minecraft account UUID of the sender, e.g. `"069a79f4-44e9-4726-a5be-fca90e38aaf5"`.               |
| `playerName`      | string | Current display name of the player. Cosmetic; the UUID is authoritative for the whitelist.          |
| `serverId`        | string | Stable connection identifier, e.g. `"multiplayer/mc_hypixel_net"` (multiplayer, derived from the server IP), `"singleplayer/New World"`, `"realms/4201C8930F12E800"`, `"lan/..."`. **This is the field the admin-configured expected server is compared against.** |
| `serverName`      | string | Human-friendly connection name, e.g. `"Multiplayer: Hypixel"`. Cosmetic only — display it, never use it for the access check. |

## Endpoint: `POST <baseUrl>/api/handshake`

Sent once when the player runs `/qmsync connect <baseUrl>`.

**Request body:** the common identity fields only.

**The website must check, in this order:**
1. `protocolVersion` is supported → else HTTP 400.
2. `serverId` matches the admin-configured expected server → else **deny**.
3. `playerUuid` is on the whitelist → else **deny**.

**Responses:**

Accepted:
```json
HTTP 200
{ "status": "SYNCED" }
```

Denied (wrong server or player not whitelisted):
```json
HTTP 200
{ "status": "ACCESS_DENIED" }
```
(The mod also treats plain HTTP `401`/`403` as ACCESS DENIED, and `404` as "URL not found".
A 2xx response whose body has no recognisable `status` field is treated as "URL not found",
i.e. "this is not a QMSync endpoint".)

## Endpoint: `POST <baseUrl>/api/sync`

Sent periodically (~5 s) while sync is active, but only when the data changed since the last
successful upload. Each request is a complete, self-contained snapshot — **not** a delta.
A request supersedes all previous ones; lost or failed uploads need no recovery handling.

**Request body:** the common identity fields plus:

| Field  | Type   | Description                                                             |
|--------|--------|--------------------------------------------------------------------------|
| `data` | object | Full snapshot of the tracked storage for this server. Format below.     |

**The website must re-check `serverId` + `playerUuid` on every sync request** (the access decision
is per-request, not per-session — there are no sessions or tokens in protocol 1).

**Responses:** same as handshake: `{ "status": "SYNCED" }` to accept,
`{ "status": "ACCESS_DENIED" }` / 401 / 403 to deny. On deny the mod shows the player a failure
message once and keeps retrying quietly (so re-whitelisting resumes sync without player action).

### `data` format

`data` is the mod's native serialized memory-bank format (produced by `MemoryBankImpl.DATA_CODEC`,
the same encoding used in the mod's own `.json` save files). Structure:

```json
{
  "<memoryKeyId>": {
    "memories": {
      "<x,y,z>": {
        "items": [ { "id": "minecraft:iron_ingot", "count": 64 }, ... ],
        "name": { ... optional custom name as a Minecraft text component ... },
        ...
      },
      ...
    },
    "overrides": { ... per-position user overrides, cosmetic ... }
  },
  ...
}
```

- `<memoryKeyId>` is a dimension/context id such as `"minecraft:overworld"`, `"minecraft:the_nether"`,
  or a mod-specific key like `"chesttracker:ender_chest"`.
- `<x,y,z>` keys are block positions as comma-separated integers, e.g. `"102,64,-33"`.
- Each memory's `items` list contains item stacks in Minecraft's registry-id + count encoding;
  item components (enchantments etc.) may appear as additional fields on a stack.
- The website should treat `data` as an opaque-ish document: parse the two levels it needs
  (keys → positions → items) and tolerate unknown fields, since the exact stack encoding follows
  Minecraft's own codecs and can gain fields across Minecraft versions.

## Error semantics summary (mod side, for reference)

| Condition                                              | Player-facing result                     |
|--------------------------------------------------------|------------------------------------------|
| 2xx + `{"status":"SYNCED"}`                            | connect: "SYNCED"; sync: silent success  |
| 2xx + `{"status":"ACCESS_DENIED"}` or 401/403          | "ACCESS DENIED"                          |
| DNS failure, 404, or 2xx without QMSync `status` body  | "URL not found"                          |
| Connection refused, timeout, 5xx, other network error  | "Connection could not be established"    |

During periodic sync, failures are reported in chat **once** per outage (first failure), and recovery
is reported **once** when uploads succeed again.

## Website implementation checklist

- [ ] `POST /api/handshake` — validate protocol, server id, whitelist; respond as above.
- [ ] `POST /api/sync` — same validation; store/diff the snapshot; respond as above.
- [ ] Admin configuration: expected `serverId` (readable string) + player UUID whitelist.
- [ ] Diff logic: compare each snapshot against the previously stored one (per player, per server).
- [ ] Idempotency: identical repeated snapshots must be harmless (the mod usually skips unchanged
      uploads, but re-sends after connection recovery).
