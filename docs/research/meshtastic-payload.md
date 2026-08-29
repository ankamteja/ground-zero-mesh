# Meshtastic LoRa payload size — verified

**Verified 2026-08-29.** The handover assumed **237 bytes**. The actual application
payload ceiling is **233 bytes**. `CompactCodec.LORA_MAX_FRAME` is set to 233.

## Finding

| Figure | Meaning | Source |
|---|---|---|
| **233** | `DATA_PAYLOAD_LEN` — bytes available for application data inside the `Data` protobuf | `meshtastic/protobufs` `mesh.proto`; firmware generated headers (`meshtastic_Constants_DATA_PAYLOAD_LEN`) |
| 237 | figure in the docs' Layer-1 packet-structure table, "Max. 237 bytes (excl. protobuf overhead)" — includes ~4 bytes of `Data` protobuf framing | <https://meshtastic.org/docs/overview/mesh-algo/> |
| 16 | unencrypted mesh packet header (dest, sender, packet id, flags, channel hash, next-hop, relay) — **outside** the payload budget | same |

## Packet layout (relevant part)

```
[ 16 bytes ] packet header        (unencrypted, routing)
[ up to 237 ] encrypted Data protobuf
     ├─ ~4 bytes protobuf framing
     └─ 233 bytes application payload   <-- our ceiling
```

Meshtastic uses AES256-CTR (no AEAD tag), so encryption adds no length overhead.

## Region / modem preset

The 233-byte ceiling is **constant** across all regions and modem presets. Presets change
airtime and range, not payload capacity.

## Recommendation

- `LORA_MAX_FRAME = 233` (done).
- Consider a 3-byte reserve (`230`) if real ESP32/Meshtastic bridge testing shows any
  nanopb encoding edge cases. Revisit during Phase 5 bench testing against real hardware.

## Sources

- `meshtastic/protobufs` — `meshtastic/mesh.proto`, `DATA_PAYLOAD_LEN = 233`
- `meshtastic/firmware` — generated `mesh.pb.h`
- <https://meshtastic.org/docs/overview/mesh-algo/> — Layer-1 packet structure
- <https://meshtastic.org/docs/overview/encryption/> — AES256-CTR, no auth tag
