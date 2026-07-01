# Mech Arena — UDP Match Protocol Notes

Reverse-engineering notes from a single ~52s capture (`ma.pcapng`), match traffic
between client `10.215.173.1` and server `34.93.190.157:7015`.

**Method:** raw byte inspection with scapy — no assumptions carried over from
guesswork, every field below was checked against either a mathematical
relationship (delta vs wall-clock time) or a behavioral correlation (packet
shape changing at a moment you independently reported doing something).

**Confidence key:**
- ✅ Confirmed — checked numerically against ground truth (time, or your report)
- 🟡 Likely — strong structural evidence, not independently verified
- ❓ Unknown — flagged, not guessed at

---

## 1. Transport-level shape

- 1460 total UDP packets in the match window (~50s), split roughly evenly
  client→server (727) and server→client (733).
- No gzip/zlib magic bytes anywhere in the payloads. Average Shannon entropy
  ~5 bits/byte (well below the ~8 bits/byte expected of encryption or
  compression) — this is a **custom bit-packed binary format**, not
  encrypted, not standard-compressed. ✅

## 2. Opcode byte (byte 0)

First byte of every UDP payload acts as a message-type discriminator.

| Opcode (hex) | Direction | Fixed size? | Role |
|---|---|---|---|
| `0x01` | client→server | variable (34–81 B observed) | Per-tick input/state packet |
| `0x81` | client→server | fixed 9 B | Heartbeat |
| `0x82` | client→server | fixed 13 B | Clock-sync packet |
| `0x01` | server→client | variable | Per-tick world-state packet (mirror of client's) |
| `0x81` | server→client | fixed 9 B | Heartbeat ack |
| `0x02` | server→client | 4 occurrences | ❓ Unknown, too rare to characterize |

🟡 Likely correct for the above; not exhaustively tested against every rare
opcode (e.g. `0x84`, `0x86`, `0x2` server-side only appeared 1–4 times each).

## 3. Opcode `0x81` — Heartbeat (9 bytes)

```
81 00 00 20 00 [4-byte LE uint32] 43
```

- Bytes `[4:8]` interpreted as **LE uint32, then divided by 2²⁴** (fixed-point)
  produces a value that tracks wall-clock elapsed match time almost exactly
  1:1 (checked across 20 consecutive packets, consistent to within capture
  jitter). ✅
- Sent roughly every 0.5s for the duration of the match, both directions.
- Interpretation: session-elapsed-time keepalive / clock sync anchor.

## 4. Opcode `0x82` — Clock-sync (13 bytes)

```
82 00 00 40 00 [4-byte LE float32] [4-byte LE float32]
```

- Both 4-byte fields decode cleanly as **LE float32**, both climbing ~1:1
  with wall-clock time (checked numerically). ✅
- Field 1 (`bytes[5:9]`): starts ~314s — likely device/session uptime.
- Field 2 (`bytes[9:13]`): starts ~128s — likely a second clock reference
  (possibly server-relative or render-loop time). ❓ which is which is unknown.
- Also sent ~every 0.5s, same cadence as the heartbeat, structurally
  identical constant header.

## 5. Opcode `0x01` — Per-tick input packet (variable length)

```
01 [2-byte LE seq, +2 per packet] [2-byte LE "load" field] [payload...]
```

- **Sequence number** (`bytes[1:3]`, LE u16): increments by exactly 2 every
  single packet across the whole capture, zero exceptions. ✅ Confirmed
  monotonic counter — most likely a shared client/server tick or ack counter
  (increment-by-2 is consistent with counting both directions on one shared
  index, but the exact reason for step-2 vs step-1 is unconfirmed). 🟡
- **Load field** (`bytes[3:5]`, LE u16): this is the key finding of the
  session.
  - **Idle** (no player input): flat around `256` (`0x0100`), ±16 jitter,
    packet length steady at 35–37 bytes. ✅
  - **Active** (from the moment you reported moving and shooting, t≈20.1s):
    jumps immediately to 264, then climbs through 392 → 448 → 592 as the
    active window continues, packet length scaling in lockstep (38 → 54 → 61
    → 79 bytes). ✅ Timing matches your self-reported action window to
    within ~0.1–0.5s.
  - Returns to idle baseline (~240–256, 35–41 byte packets) after you
    reportedly stopped, around t≈41.5s. ✅
  - **Best interpretation**: this field is a size/element-count header for
    that tick's batch of input events (movement deltas, fire commands, etc.)
    — more simultaneous actions in a tick → larger declared payload → larger
    packet. 🟡 Plausible and consistent with all observed data, but not
    independently proven against a labeled ground truth.
- **Everything past byte 5** in the `0x01` packets: ❓ unknown. This is
  where actual position/rotation/weapon-state data almost certainly lives,
  but no individual field (X position, aim angle, trigger state, etc.) was
  isolated in this session.

## 6. What's *not* yet cracked

- No individual gameplay field (e.g., "this byte = X coordinate", "this bit
  = trigger pressed") has been isolated. The "load field" above tells you
  *when* something happened and roughly *how much*, not *what*.
- Movement and shooting happened in the same continuous ~20s window in this
  capture, so their signatures can't be separated from each other yet.
- The `0x02` and other single-digit-occurrence opcodes are uncharacterized.
- Server→client `0x01` packets (world-state mirror) weren't broken down in
  this pass — same method would apply.

## 7. How to get further resolution

To actually isolate movement vs. shooting vs. other actions, the fastest
path is a **new capture with deliberately isolated, labeled action windows**:

1. Record ~10s doing nothing (baseline — already have this).
2. Record ~10s moving only, no shooting.
3. Record ~10s standing still, shooting only.
4. Record ~10s doing a single distinct action (e.g. one weapon swap, one
   jump) with a few seconds of idle padding before/after.

Diffing each labeled window against the idle baseline the same way this
session diffed the mixed 20–40s window against idle would let specific
byte offsets be attributed to specific actions with actual confidence,
rather than inferred from a mixed signal.

---

*Everything marked ✅ above was checked computationally in this session
(scapy byte extraction + arithmetic comparison against wall-clock time or
your self-reported timing). Nothing here is copied from documentation —
Plarium has not published this protocol; all of it was derived from the
capture itself.*
