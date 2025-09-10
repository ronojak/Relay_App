#!/usr/bin/env python3
"""
Relay Test Server — TCP/UDP sink+source to mimic the "other side" of your Relay app.

Features
- Modes: TCP, UDP, or BOTH
- Roles:
  - sink: accept incoming frames and parse/validate Envelope + GamepadState
  - source: generate synthetic GamepadState frames at a fixed rate and send to peer
- Robustness: timeouts, partial-read protection, length checks, backpressure-aware TCP writes,
  UDP packet sizing checks, graceful shutdown, structured logging
- Fault tolerance: never crashes on single-client errors; per-connection isolation

Usage examples

# 1) TCP sink (listen for your Relay app to connect and send frames)
python relay_test_server.py --mode tcp --role sink --bind 0.0.0.0 --port 26543

# 2) TCP source (act like "relay sender", emit synthetic frames to a client)
python relay_test_server.py --mode tcp --role source --target 192.168.2.92:26543 --rate 120

# 3) UDP sink (listen for datagrams from your app)
python relay_test_server.py --mode udp --role sink --bind 0.0.0.0 --port 26543

# 4) UDP source (periodically send to a target)
python relay_test_server.py --mode udp --role source --target 192.168.2.92:26543 --rate 200

# 5) Run both servers: TCP sink + UDP sink at once
python relay_test_server.py --mode both --role sink --bind 0.0.0.0 --port 26543

Notes
- Source role crafts Envelope + GamepadState matching your struct formats.
- Sink role validates envelope length == GamepadState.SIZE and logs anomalies.
"""
import argparse
import asyncio
import logging
import os
import signal
import socket
import struct
import sys
import time
from typing import Optional, Tuple

# Import your protocol definitions so we stay in lockstep with formats/sizes
from protocol import Envelope, GamepadState  # :contentReference[oaicite:3]{index=3}

# -----------------------
# Packing helpers (to-bytes)
# -----------------------
_ENVELOPE_SFMT = Envelope.STRUCT_FORMAT
_GS_SFMT = GamepadState.STRUCT_FORMAT
_GS_SIZE = GamepadState.SIZE
_ENV_SIZE = Envelope.SIZE

def pack_gamepad_state(seq: int) -> bytes:
    """
    Create a synthetic but plausible GamepadState payload.
    - Sticks oscillate gently with seq so you can see motion.
    - Triggers ramp 0..1023 repeating.
    - Buttons/dpad toggle periodically.
    """
    device_id = 0
    flags = 0x01
    buttons = 0x0001 if (seq // 30) % 2 == 0 else 0x0002
    def tri(n, period, amp):
        t = n % period
        half = period // 2
        return int((t if t < half else period - t) / half * amp * (1 if t < half else -1))

    lx = tri(seq, 200, 12000)
    ly = tri(seq + 50, 200, 12000)
    rx = tri(seq + 100, 200, 12000)
    ry = tri(seq + 150, 200, 12000)
    l2 = (seq * 8) % 1024
    r2 = (seq * 5) % 1024
    dpad_x = -1 if (seq // 60) % 3 == 0 else (1 if (seq // 60) % 3 == 1 else 0)
    dpad_y = 0

    return struct.pack(_GS_SFMT, device_id, flags, buttons, lx, ly, rx, ry, l2, r2, dpad_x, dpad_y)

def pack_envelope(msg_type: int, version: int, payload: bytes, seq: int) -> bytes:
    length = len(payload)
    timestamp_us = time.time_ns() // 1_000
    return struct.pack(_ENVELOPE_SFMT, msg_type, version, length, seq, timestamp_us)

# -----------------------
# Logging setup
# -----------------------
def setup_logging(verbose: bool):
    # Set default log level to DEBUG to always show incoming messages.
    level = logging.DEBUG
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

logger = logging.getLogger("relay_test_server")

# -----------------------
# TCP sink
# -----------------------
async def tcp_sink(bind: str, port: int, read_timeout: float):
    server = await asyncio.start_server(
        lambda r, w: tcp_sink_client(r, w, read_timeout),
        host=bind,
        port=port,
        reuse_address=True,
        reuse_port=False
    )
    sockets = ", ".join(str(s.getsockname()) for s in server.sockets or [])
    logger.info(f"TCP sink listening on {sockets}")
    async with server:
        await server.serve_forever()

async def read_exactly(reader: asyncio.StreamReader, n: int, timeout: float) -> bytes:
    return await asyncio.wait_for(reader.readexactly(n), timeout=timeout)

async def tcp_sink_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, read_timeout: float):
    peer = writer.get_extra_info("peername")
    log = logging.getLogger(f"tcp-sink:{peer}")
    log.info("Client connected")
    try:
        while True:
            header = await read_exactly(reader, _ENV_SIZE, read_timeout)
            env = Envelope.from_bytes(header)  # :contentReference[oaicite:4]{index=4}
            if env.length <= 0 or env.length > 4096:
                log.warning(f"Invalid length {env.length}; dropping connection")
                break

            payload = await read_exactly(reader, env.length, read_timeout)
            if env.length != _GS_SIZE:
                log.warning(f"Length mismatch: env.length={env.length} vs GamepadState.SIZE={_GS_SIZE}")

            try:
                state = GamepadState.from_bytes(payload)  # :contentReference[oaicite:5]{index=5}
                log.info(f"Envelope(seq={env.seq}, ver={env.version}, ts_us={env.timestamp_us}) | {state}")
            except Exception as e:
                log.error(f"Failed to parse GamepadState ({env.length} bytes): {e}")
    except (asyncio.IncompleteReadError, asyncio.TimeoutError):
        log.warning("Read timeout / incomplete read; closing client")
    except ConnectionResetError:
        log.warning("Peer reset")
    except Exception as e:
        log.exception(f"Unhandled error: {e}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        log.info("Client disconnected")

# -----------------------
# TCP source
# -----------------------
async def tcp_source(target: Tuple[str, int], rate_hz: int, msg_type: int, version: int):
    host, port = target
    backoff = 1.0
    while True:
        try:
            logger.info(f"TCP source connecting to {host}:{port} ...")
            reader, writer = await asyncio.open_connection(host, port)
            logger.info("Connected")
            backoff = 1.0
            seq = 0
            period = 1.0 / max(1, rate_hz)
            while True:
                payload = pack_gamepad_state(seq)
                env = pack_envelope(msg_type, version, payload, seq)
                writer.write(env + payload)
                await writer.drain()
                seq += 1
                await asyncio.sleep(period)
        except (ConnectionRefusedError, TimeoutError, OSError) as e:
            logger.warning(f"Connect/send error: {e}. Reconnecting in {backoff:.1f}s")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 10.0)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.exception(f"Unexpected error: {e}")
            await asyncio.sleep(1.0)

# -----------------------
# UDP sink
# -----------------------
class UDPSinkProtocol(asyncio.DatagramProtocol):
    def __init__(self):
        super().__init__()
        self.log = logging.getLogger("udp-sink")

    def datagram_received(self, data: bytes, addr):
        try:
            if len(data) < _ENV_SIZE:
                self.log.warning(f"{addr} -> short packet ({len(data)} bytes)")
                return
            env = Envelope.from_bytes(data[:_ENV_SIZE])  # :contentReference[oaicite:6]{index=6}
            payload = data[_ENV_SIZE:_ENV_SIZE + env.length]

            if len(payload) != env.length:
                self.log.warning(f"{addr} -> truncated payload: expected {env.length}, got {len(payload)}")
                return
            if env.length != _GS_SIZE:
                self.log.warning(f"{addr} -> length mismatch (env={env.length}, gs={_GS_SIZE})")

            try:
                state = GamepadState.from_bytes(payload)  # :contentReference[oaicite:7]{index=7}
                self.log.info(f"{addr} | Envelope(seq={env.seq}, ver={env.version}, ts_us={env.timestamp_us}) | {state}")
            except Exception as e:
                self.log.error(f"{addr} -> GamepadState parse error: {e}")
        except Exception as e:
            self.log.exception(f"{addr} -> packet error: {e}")

async def udp_sink(bind: str, port: int):
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.create_datagram_endpoint(
        UDPSinkProtocol,
        local_addr=(bind, port),
        allow_broadcast=True,
        reuse_port=False,
    )
    logger.info(f"UDP sink listening on {bind}:{port}")
    try:
        await asyncio.Future()
    finally:
        transport.close()

# -----------------------
# UDP source
# -----------------------
async def udp_source(target: Tuple[str, int], rate_hz: int, msg_type: int, version: int, bind: Optional[str] = None):
    host, port = target
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if bind:
        sock.bind((bind, 0))
    sock.setblocking(False)

    loop = asyncio.get_running_loop()
    seq = 0
    period = 1.0 / max(1, rate_hz)
    logger.info(f"UDP source sending to {host}:{port} at {rate_hz} Hz")
    try:
        while True:
            payload = pack_gamepad_state(seq)
            env = pack_envelope(msg_type, version, payload, seq)
            packet = env + payload
            if len(packet) > 1472:
                logger.warning(f"Packet {len(packet)} exceeds typical UDP payload MTU; dropping")
            else:
                try:
                    await loop.sock_sendto(sock, packet, (host, port))
                except Exception as e:
                    logger.warning(f"UDP send error: {e}")
            seq += 1
            await asyncio.sleep(period)
    finally:
        sock.close()

# -----------------------
# Orchestration
# -----------------------
async def main_async(args):
    tasks = []

    if args.mode in ("tcp", "both"):
        if args.role == "sink":
            tasks.append(asyncio.create_task(tcp_sink(args.bind, args.port, args.read_timeout)))
        else:
            if not args.target:
                raise SystemExit("--target host:port required for TCP source")
            target = parse_hostport(args.target)
            tasks.append(asyncio.create_task(tcp_source(target, args.rate, args.msg_type, args.version)))

    if args.mode in ("udp", "both"):
        if args.role == "sink":
            tasks.append(asyncio.create_task(udp_sink(args.bind, args.port)))
        else:
            if not args.target:
                raise SystemExit("--target host:port required for UDP source")
            target = parse_hostport(args.target)
            tasks.append(asyncio.create_task(udp_source(target, args.rate, args.msg_type, args.version, args.bind if args.bind != "0.0.0.0" else None)))

    stop = asyncio.Event()

    def _signal(*_):
        logger.info("Received shutdown signal")
        stop.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _signal)
        except NotImplementedError:
            signal.signal(sig, lambda *_: _signal())

    await stop.wait()
    for t in tasks:
        t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)

def parse_hostport(s: str) -> Tuple[str, int]:
    if ":" not in s:
        raise ValueError("target must be host:port")
    host, port_s = s.rsplit(":", 1)
    return host, int(port_s)

def cli():
    p = argparse.ArgumentParser(description="Relay Test Server (TCP/UDP sink or source)")
    p.add_argument("--mode", choices=["tcp", "udp", "both"], default="tcp",
                   help="transport to run")
    p.add_argument("--role", choices=["sink", "source"], default="sink",
                   help="sink=receive/parse, source=generate/send")
    p.add_argument("--bind", default="0.0.0.0", help="bind address for sink or UDP source (default 0.0.0.0)")
    p.add_argument("--port", type=int, default=26543, help="port to listen on (sink) or bind for UDP source")
    p.add_argument("--target", help="host:port to send to (source modes)")
    p.add_argument("--rate", type=int, default=120, help="send rate in Hz for source modes")
    p.add_argument("--msg-type", type=int, default=1, help="Envelope.msg_type value to send (source)")
    p.add_argument("--version", type=int, default=1, help="Envelope.version value to send (source)")
    p.add_argument("--read-timeout", type=float, default=10.0, help="per-read timeout seconds (TCP sink)")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args()
    setup_logging(args.verbose)
    return args

def main():
    args = cli()
    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        pass

if __name__ == "__main__":
    main()

