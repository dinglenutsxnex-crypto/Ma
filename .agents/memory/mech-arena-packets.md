---
name: Mech Arena packet structure
description: Confirmed layout of com.plarium.mechlegion UDP client tick packets
---

## Confirmed client_tick_action packet layout (opcode 0x01)

```
byte[0]    = 0x01  (opcode, Lidgren data)
byte[1:3]  = LE u16 sequence number (+2 per tick)
byte[3:5]  = LE u16 load field (~256 idle, >272 active)
byte[5]    = 0x63  (constant — game message header byte)
byte[6]    = varies (weapon slot / flags)
byte[7]    = yaw byte  →  yaw_deg = byte[7] * 360/256  (plain byte, NOT Lidgren-packed)
byte[8]    = X position low byte (slowly varying)
byte[9]    = varies
byte[10]   = varies
byte[11]   = 0x42 for non-movement, 0x30/0x70/0xb0/0xf0 for movement
byte[12]   = varies
byte[13]   = 0x50 → movement packet (decode XYZ)
             0x06 → non-movement packet (skip — garbage at XYZ offset)
byte[5:]   = Lidgren bit-packed payload (LSB-first)
```

## XYZ at bit offset 50 (movement packets only)
```
X  = lidgrenRanged(data, 50,   -2000, 2000, 24 bits)
Y  = lidgrenRanged(data, 74,    -300,  300, 16 bits)
Z  = lidgrenRanged(data, 90,   -2000, 2000, 24 bits)
```
Constants from dump.cs BinarySerializationExtension:
- PositionXZRange=2000, PositionXZBits=24
- PositionYRange=300, PositionYBits=16

## TickState field mapping (OverlayService.kt compatibility)
- estF0 = X (metres)
- estF1 = Z (metres)
- estF2 = yaw (radians, for fmtAngle())
- estF3 = Y (metres, height)

## Authority model
- Client sends: movement vector, yaw, trigger-held flag (no damage values)
- Server owns: hit detection, damage, HP, kill confirmation
- Server sends back: ActorShootingState.ShotBulletsBlock, ActorState.Hp, ActorDeathData
- Damage NOT detectable from client packets

## Two message subtypes in opcode 0x01
Every ~3 ticks the client sends a non-movement packet (byte[13]=0x06).
These share opcode 0x01 but have completely different payload layout.
Never try to decode XYZ from them.

**Why:** Raw brute-force offset search + byte-level diff across 30 consecutive packets.
Confirmed: all 18 movement packets stable at Z≈-1685, Y≈-240; all 9 skip packets give Z=-1975, Y≈0 at that offset.

## Game server
UDP: 34.93.190.157:7015
