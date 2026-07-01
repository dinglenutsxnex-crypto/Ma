"""
Mech Arena (com.plarium.mechlegion) Lidgren bit-packed UDP packet decoder.

Confirmed from dump.cs (BinarySerializationExtension):
  PositionXZRange=2000, PositionYRange=300, PositionXZBits=24, PositionYBits=16
  VelocityXZRange=200,  VelocityYRange=200, VelocityXZBits=16, VelocityYBits=16

Lidgren ReadRangedSingle(min, max, n_bits):
  reads n bits LSB-first → value = min + raw * (max - min) / ((1 << n_bits) - 1)

Empirically confirmed bit layout (server_world_state and client_tick_action):
  byte[0]    = 0x01  opcode
  byte[1:3]  = LE u16  seq  (increments +2 per tick)
  byte[3:5]  = LE u16  load (~256 idle, >272 active)
  byte[5:]   = Lidgren bit-packed payload
    bit_offset +10 from byte 5 → ActorMovementState:
      X     : 24 bits  range [-2000, 2000]
      Y     : 16 bits  range [-300,   300]
      Z     : 24 bits  range [-2000, 2000]
      VelX  : 16 bits  range [-200,   200]
      VelY  : 16 bits  range [-200,   200]
      VelZ  : 16 bits  range [-200,   200]
      Yaw   : 12 bits  range [0,       360]   (best-fit estimate)

  For client_tick_action byte[7] also encodes a simple 8-bit yaw:
      rot_raw = byte[7],  rot_deg = rot_raw * 360 / 256
"""

import zipfile
import struct
import json
import os
import glob


# ── Lidgren bit buffer ──────────────────────────────────────────────────────

class BitBuffer:
    def __init__(self, data: bytes, start_bit: int = 0):
        self.data = data
        self.bit_pos = start_bit

    def read_bits(self, n: int) -> int:
        val = 0
        for i in range(n):
            bi = self.bit_pos // 8
            bo = self.bit_pos % 8
            if bi < len(self.data):
                val |= ((self.data[bi] >> bo) & 1) << i
            self.bit_pos += 1
        return val

    def read_bool(self) -> bool:
        return bool(self.read_bits(1))

    def read_ranged_single(self, min_v: float, max_v: float, n_bits: int) -> float:
        raw = self.read_bits(n_bits)
        return min_v + raw * (max_v - min_v) / ((1 << n_bits) - 1)

    def read_u8(self) -> int:
        return self.read_bits(8)

    def read_u16(self) -> int:
        return self.read_bits(16)

    def remaining_bytes(self) -> int:
        return max(0, len(self.data) - (self.bit_pos + 7) // 8)

    def skip(self, n_bits: int):
        self.bit_pos += n_bits


# ── Position / velocity constants from dump.cs ─────────────────────────────

POS_XZ_RANGE = 2000.0
POS_Y_RANGE  = 300.0
POS_XZ_BITS  = 24
POS_Y_BITS   = 16

VEL_XZ_RANGE = 200.0
VEL_Y_RANGE  = 200.0
VEL_XZ_BITS  = 16
VEL_Y_BITS   = 16

YAW_BITS = 12   # best-fit from empirical testing across battle frames


def decode_compressed_position(buf: BitBuffer):
    x = buf.read_ranged_single(-POS_XZ_RANGE, POS_XZ_RANGE, POS_XZ_BITS)
    y = buf.read_ranged_single(-POS_Y_RANGE,  POS_Y_RANGE,  POS_Y_BITS)
    z = buf.read_ranged_single(-POS_XZ_RANGE, POS_XZ_RANGE, POS_XZ_BITS)
    return x, y, z


def decode_compressed_velocity(buf: BitBuffer):
    vx = buf.read_ranged_single(-VEL_XZ_RANGE, VEL_XZ_RANGE, VEL_XZ_BITS)
    vy = buf.read_ranged_single(-VEL_Y_RANGE,  VEL_Y_RANGE,  VEL_Y_BITS)
    vz = buf.read_ranged_single(-VEL_XZ_RANGE, VEL_XZ_RANGE, VEL_XZ_BITS)
    return vx, vy, vz


def decode_movement_state(data: bytes, payload_bit_offset: int = 10):
    """
    Decode ActorMovementState from an opcode-0x01 packet.
    payload_bit_offset is measured from byte 5 (start of Lidgren payload).
    Returns dict with x/y/z/vx/vy/vz/yaw or None if out-of-range.
    """
    if len(data) < 10 or (data[0] & 0xFF) != 0x01:
        return None

    start_bit = 5 * 8 + payload_bit_offset
    buf = BitBuffer(data, start_bit)

    try:
        x, y, z = decode_compressed_position(buf)
        vx, vy, vz = decode_compressed_velocity(buf)
        yaw = buf.read_ranged_single(0.0, 360.0, YAW_BITS)
    except Exception:
        return None

    # Sanity check — out-of-range means misaligned
    if abs(x) > 1990 or abs(z) > 1990 or abs(y) > 295:
        return None

    return dict(
        x=round(x, 2), y=round(y, 2), z=round(z, 2),
        vx=round(vx, 2), vy=round(vy, 2), vz=round(vz, 2),
        yaw=round(yaw, 1),
    )


# ── Packet header parsing ──────────────────────────────────────────────────

def parse_header(data: bytes):
    """Return (opcode, seq, load) from the 5-byte header."""
    if len(data) < 5:
        return None, 0, 0
    opcode = data[0] & 0xFF
    seq = struct.unpack_from('<H', data, 1)[0]
    load = struct.unpack_from('<H', data, 3)[0]
    return opcode, seq, load


def packet_type_label(filename: str, opcode: int, data: bytes) -> tuple:
    """Return (type_tag, shooting, ability, moving)."""
    fname = os.path.basename(filename)
    shooting = ability = moving = False

    if 'server_world_state' in fname:
        return 'world', False, False, False
    if 'client_tick_action' in fname:
        load = struct.unpack_from('<H', data, 3)[0] if len(data) >= 5 else 0
        moving = load > 272
        shooting = load > 400
        ability = False
        return 'tick', shooting, ability, moving
    if 'client_tick_idle' in fname:
        return 'idle', False, False, False
    if 'client_ping' in fname or 'server_ping' in fname:
        return 'hb', False, False, False
    if 'clock_sync' in fname:
        return 'cs', False, False, False
    return 'unk', False, False, False


# ── Battle ZIP decoder ─────────────────────────────────────────────────────

def decode_zip(zip_path: str) -> dict:
    client_pkts = []
    server_pkts = []
    heartbeats_c = []
    heartbeats_s = []
    clocksync = []
    server_events = []
    t_base = None

    with zipfile.ZipFile(zip_path) as zf:
        manifest_raw = None
        try:
            manifest_raw = zf.read('_manifest.txt').decode()
        except Exception:
            pass

        # Build seq→timestamp from manifest if available
        ts_map = {}
        if manifest_raw:
            for line in manifest_raw.splitlines():
                parts = line.strip().split('\t')
                if len(parts) >= 3:
                    try:
                        ts_map[parts[0]] = float(parts[1])
                    except ValueError:
                        pass

        names = sorted(zf.namelist())
        seq_counter = 0

        for name in names:
            if not name.endswith('.bin'):
                continue
            try:
                data = zf.read(name)
            except Exception:
                continue

            fname = os.path.basename(name)
            opcode, seq, load = parse_header(data)
            if opcode is None:
                continue

            t = ts_map.get(fname, seq_counter * 0.033)
            seq_counter += 1
            if t_base is None:
                t_base = t
            t_rel = round(t - t_base, 4)

            tag, shooting, ability, moving = packet_type_label(fname, opcode, data)

            pkt = dict(
                t=t_rel,
                seq=seq,
                load=load,
                len=len(data),
                hex=data.hex(),
                shooting=shooting,
                ability=ability,
                moving=moving,
            )

            # Client rotation (simple byte encoding)
            if 'client_tick_action' in fname and len(data) > 7:
                rot_raw = data[7] & 0xFF
                pkt['rot'] = round(rot_raw * 360.0 / 256.0, 1)
                pkt['rot_raw'] = rot_raw
                pkt['move_raw'] = data[8] & 0xFF if len(data) > 8 else 0

            # Lidgren bit-packed position / velocity / yaw
            mv = decode_movement_state(data)
            if mv:
                pkt.update(mv)

            if 'server_world_state' in fname:
                server_pkts.append(pkt)
            elif 'client_tick_action' in fname or 'client_tick_idle' in fname:
                client_pkts.append(pkt)
            elif 'client_ping' in fname:
                heartbeats_c.append(pkt)
            elif 'server_ping' in fname:
                heartbeats_s.append(pkt)
            elif 'clock_sync' in fname:
                clocksync.append(pkt)
            else:
                server_events.append(pkt)

    return dict(
        client=client_pkts,
        server=server_pkts,
        heartbeats_c=heartbeats_c,
        heartbeats_s=heartbeats_s,
        clocksync=clocksync,
        server_events=server_events,
    )


# ── Main ───────────────────────────────────────────────────────────────────

def find_latest_zip():
    pattern = os.path.join(
        os.path.dirname(__file__), '..', 'attached_assets', 'mech_battle_*.zip'
    )
    zips = sorted(glob.glob(pattern))
    return zips[-1] if zips else None


if __name__ == '__main__':
    import sys
    zip_path = sys.argv[1] if len(sys.argv) > 1 else find_latest_zip()
    if not zip_path:
        print('No battle ZIP found.')
        sys.exit(1)

    print(f'Decoding {zip_path} …')
    result = decode_zip(zip_path)

    out_path = os.path.join(os.path.dirname(__file__), 'static', 'packet_data.json')
    with open(out_path, 'w') as f:
        json.dump(result, f, separators=(',', ':'))

    c = len(result['client'])
    s = len(result['server'])
    decoded_pos = sum(1 for p in result['server'] if 'x' in p)
    print(f'Done — {c} client, {s} server pkts, {decoded_pos} with decoded positions → {out_path}')
