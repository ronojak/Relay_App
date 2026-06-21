# Gamepad Relay Wire Protocol

This is the exact, byte-level format the Relay app sends to the peripheral/server.
It is authoritative for writing a server that interprets gamepad frames. It is
derived directly from the serialization code (`protocol/MessageEnvelope.kt`,
`protocol/GamepadState.kt`, `protocol/MessageSerializer.kt`).

> Note: some older docs (CLAUDE.md/README history) mention a "14-byte header" and
> port 6543 — those are stale. The real header is **16 bytes** and the default
> port is **26543**.

## Transport & framing

- **Transport:** TCP. The **MCU/peripheral is the server**; the phone is the
  client and connects out to `IP:port`.
- **Default port:** `26543`.
- **Byte order:** **little-endian** for every multi-byte field.
- **Socket options:** `TCP_NODELAY` on, `SO_KEEPALIVE` on.
- **Framing:** a continuous stream of messages, each = **16-byte envelope** followed
  by **`length`** payload bytes (`length` comes from the envelope). There are no
  delimiters or magic bytes — you must length-prefix-parse.

Server read loop (pseudocode):

```
read exactly 16 bytes            -> envelope
length   = u16le(envelope[2:4])  -> payload size
read exactly `length` bytes      -> payload
if envelope[0] == 0x01: parse GamepadState(payload)
```

## Envelope (16 bytes)

Python `struct` format: `"<BBHIQ"` (size 16).

| Offset | Size | Type      | Field          | Notes |
|-------:|-----:|-----------|----------------|-------|
| 0      | 1    | uint8     | `msg_type`     | `0x01` = GamepadState (the only type the relay currently sends) |
| 1      | 1    | uint8     | `version`      | currently `1` |
| 2      | 2    | uint16 LE | `length`       | payload byte count (26, or 38 with sensors) |
| 4      | 4    | uint32 LE | `seq`          | per-frame sequence, increments by 1 |
| 8      | 8    | uint64 LE | `timestamp_us` | microseconds (wall clock: `epoch_ms * 1000`, so ms precision) |

`msg_type` values that exist in the enum but are **not** emitted on the send path
today: `0x02` ping, `0x03` auth challenge, `0x04` auth response, `0x10` set-rumble,
`0x11` set-led, `0x20` device-info. A server only needs to handle `0x01` for now,
but should skip unknown types using `length`.

## GamepadState payload

Base payload is **26 bytes**. Bytes 0–19 are defined fields; bytes 20–25 are
**reserved (currently zero)**. The relay currently always sends the 26-byte form
(no motion sensors).

Python `struct` format (base): `"<BBHhhhhHHhh6x"` (size 26).

| Offset | Size | Type      | Field        | Range / meaning |
|-------:|-----:|-----------|--------------|-----------------|
| 0      | 1    | uint8     | `device_id`  | logical controller id |
| 1      | 1    | uint8     | `flags`      | bitmask, see below |
| 2      | 2    | uint16 LE | `buttons`    | button bitmask, see below |
| 4      | 2    | int16 LE  | `lx`         | left stick X, −32768..32767 (0 = center) |
| 6      | 2    | int16 LE  | `ly`         | left stick Y, −32768..32767 (0 = center) |
| 8      | 2    | int16 LE  | `rx`         | right stick X, −32768..32767 |
| 10     | 2    | int16 LE  | `ry`         | right stick Y, −32768..32767 |
| 12     | 2    | uint16 LE | `l2`         | left trigger, 0 (released) .. 65535 (full) |
| 14     | 2    | uint16 LE | `r2`         | right trigger, 0 .. 65535 |
| 16     | 2    | int16 LE  | `dpad_x`     | D-pad X as analog: −32767 left, 0 center, 32767 right |
| 18     | 2    | int16 LE  | `dpad_y`     | D-pad Y as analog: −32767 up, 0 center, 32767 down |
| 20     | 6    | —         | reserved     | zero (no sensors). With sensors this region is used (below) |

Stick/D-pad axes follow the Android joystick convention: **up/left are negative**,
**down/right are positive**. Triggers are unsigned (read the 16-bit field as
`uint16`, not signed).

### Flags (byte 1)

| Bit | Mask | Meaning |
|----:|-----:|---------|
| 0   | 0x01 | gyro data present |
| 1   | 0x02 | accelerometer data present |
| 2   | 0x04 | touchpad enabled |

### Buttons (uint16 bitmask at offset 2)

Bit set = pressed. Layout matches PS5 DualSense naming; map to your controller as
needed.

| Bit | Mask   | Button |
|----:|-------:|--------|
| 0   | 0x0001 | Cross / A |
| 1   | 0x0002 | Circle / B |
| 2   | 0x0004 | Square / X |
| 3   | 0x0008 | Triangle / Y |
| 4   | 0x0010 | L1 / LB |
| 5   | 0x0020 | R1 / RB |
| 6   | 0x0040 | L2 (digital press; analog value in `l2`) |
| 7   | 0x0080 | R2 (digital press; analog value in `r2`) |
| 8   | 0x0100 | Share / Create |
| 9   | 0x0200 | Options / Menu |
| 10  | 0x0400 | L3 (left stick click) |
| 11  | 0x0800 | R3 (right stick click) |
| 12  | 0x1000 | PS / Guide |
| 13  | 0x2000 | Touchpad click |
| 14  | 0x4000 | Mute |
| 15  | 0x8000 | unused |

### Optional motion sensors (payload length 38)

If `flags & 0x01` and `flags & 0x02` are both set **and** `length == 38`, the
payload carries gyro + accel after the base fields:

Python `struct` format: `"<BBHhhhhHHhhhhhhhh6x"` (size 38).

| Offset | Size | Type     | Field |
|-------:|-----:|----------|-------|
| 20     | 2    | int16 LE | `gyro_x` |
| 22     | 2    | int16 LE | `gyro_y` |
| 24     | 2    | int16 LE | `gyro_z` |
| 26     | 2    | int16 LE | `accel_x` |
| 28     | 2    | int16 LE | `accel_y` |
| 30     | 2    | int16 LE | `accel_z` |
| 32     | 6    | —        | reserved (zero) |

Always trust `length` (and the flags) rather than assuming a fixed size: 26 = base,
38 = base + sensors.

## Reference parsers

### C struct (packed, little-endian host)

```c
#pragma pack(push, 1)
struct Envelope {
    uint8_t  msg_type;      // 0x01
    uint8_t  version;       // 1
    uint16_t length;        // payload bytes
    uint32_t seq;
    uint64_t timestamp_us;
};
struct GamepadState {       // base, length == 26
    uint8_t  device_id;
    uint8_t  flags;
    uint16_t buttons;
    int16_t  lx, ly, rx, ry;
    uint16_t l2, r2;
    int16_t  dpad_x, dpad_y;
    uint8_t  reserved[6];   // zero
};
#pragma pack(pop)
```

### Python

```python
import struct
ENV = "<BBHIQ"                  # 16 bytes
GS  = "<BBHhhhhHHhh6x"          # 26 bytes (base)

def read_frame(sock):
    env = recvall(sock, struct.calcsize(ENV))
    msg_type, version, length, seq, ts_us = struct.unpack(ENV, env)
    payload = recvall(sock, length)
    if msg_type == 0x01 and length >= struct.calcsize(GS):
        device_id, flags, buttons, lx, ly, rx, ry, l2, r2, dpad_x, dpad_y = \
            struct.unpack(GS, payload[:struct.calcsize(GS)])
        # ... use fields ...
    return msg_type, seq, ts_us
```

`utils/relay_test_server.py --role sink` implements exactly this framer and prints
each decoded frame — use it to validate a server implementation.

## Notes for implementers

- **Normalization (optional):** sticks/D-pad ÷ 32767 → −1.0..1.0; triggers ÷ 65535
  → 0.0..1.0 (matches the app's own helpers).
- **Rate:** the relay currently emits at a fixed ~10 Hz (it coalesces to the latest
  state); design the server to accept a variable/bursty rate up to ~120 Hz.
- **Liveness:** the relay relies on the TCP connection; if your server closes the
  socket, the relay detects it (EOF) and stops. Keep the accept loop alive and
  re-accept after a client disconnects.
