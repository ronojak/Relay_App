#!/usr/bin/env python3
import argparse
import asyncio
import logging
import signal
import socket
import struct
import time
from typing import Optional, Tuple
import threading
from dataclasses import dataclass

# ===== Wire formats (explicit) =====
# Envelope on the wire: LITTLE-ENDIAN 16B -> <BBHIQ
_ENVELOPE_SFMT = "<BBHIQ"
_ENV_SIZE = struct.calcsize(_ENVELOPE_SFMT)

# Try to import payload definition; the sink can still run without it
try:
    from protocol import GamepadState
    _GS_SFMT = GamepadState.STRUCT_FORMAT
    _GS_SIZE = GamepadState.SIZE
except Exception:
    GamepadState = None
    _GS_SFMT = None
    _GS_SIZE = 0

# ===== Logging =====
def setup_logging(verbose: bool):
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
    logging.getLogger("relay_test_server").info(
        f"Envelope fmt={_ENVELOPE_SFMT} ENV_SIZE={_ENV_SIZE}; GamepadState SIZE={_GS_SIZE} fmt={_GS_SFMT}"
    )

logger = logging.getLogger("relay_test_server")

# ===== Envelope helper =====
@dataclass
class WireEnvelope:
    msg_type: int
    version: int
    length: int
    seq: int
    timestamp_us: int

    @classmethod
    def from_bytes(cls, b: bytes) -> "WireEnvelope":
        msg_type, version, length, seq, ts_us = struct.unpack(_ENVELOPE_SFMT, b)
        return cls(msg_type, version, length, seq, ts_us)

# ===== Byte counter (inline status) =====
total_bytes_received = 0
total_bytes_lock = threading.Lock()

def print_inline_byte_count():
    with total_bytes_lock:
        kb = total_bytes_received // 1024
        print(f"\rTotal bytes received: {kb} KB", end="", flush=True)

# ===== Hex dump utils =====
def hexdump(data: bytes, start_off: int = 0, line_bytes: int = 16, show_ascii: bool = False) -> str:
    lines = []
    for i in range(0, len(data), line_bytes):
        chunk = data[i:i+line_bytes]
        hexpart = " ".join(f"{b:02x}" for b in chunk)
        if len(chunk) < line_bytes:
            hexpart += "   " * (line_bytes - len(chunk))
        if show_ascii:
            asc = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
            lines.append(f"{start_off+i:08x}  {hexpart}  |{asc}|")
        else:
            lines.append(f"{start_off+i:08x}  {hexpart}")
    return "\n".join(lines)

# ===== TCP SINK with TAP + best-effort framer =====
async def tcp_sink(bind: str, port: int, read_timeout: float, tap: bool, line_bytes: int, show_ascii: bool):
    server = await asyncio.start_server(
        lambda r, w: tcp_sink_client(r, w, read_timeout, tap, line_bytes, show_ascii),
        host=bind, port=port, reuse_address=True, reuse_port=False
    )
    sockets = ", ".join(str(s.getsockname()) for s in server.sockets or [])
    logger.info(f"TCP sink listening on {sockets}")
    async with server:
        await server.serve_forever()

async def tcp_sink_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter,
                          read_timeout: float, tap: bool, line_bytes: int, show_ascii: bool):
    peer = writer.get_extra_info("peername")
    sock = writer.get_extra_info("socket")
    if sock:
        try: sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError: pass

    log = logging.getLogger(f"tcp-sink:{peer}")
    log.info("Client connected")
    global total_bytes_received

    # We'll accumulate raw bytes in a buffer; parser consumes from it without blocking
    buf = bytearray()
    offset = 0
    reader_task = asyncio.create_task(reader.read(4096))

    try:
        while True:
            # if we already have enough for a header, try parsing; else pull more bytes
            if len(buf) >= _ENV_SIZE:
                # Try to parse header
                header = bytes(buf[:_ENV_SIZE])
                try:
                    env = WireEnvelope.from_bytes(header)
                except struct.error:
                    # Not enough for header (shouldn't happen), fall through to read
                    pass
                else:
                    frame_total = _ENV_SIZE + int(env.length)
                    # If not all payload is in buffer yet, fetch more
                    if len(buf) < frame_total:
                        # Ask for more bytes (non-blocking wait with timeout)
                        try:
                            more = await asyncio.wait_for(reader.read(frame_total - len(buf)), timeout=read_timeout)
                        except asyncio.TimeoutError:
                            log.warning("Timeout waiting for payload bytes")
                            # in tap mode we already printed what we had; attempt resync by dropping one byte
                            buf.pop(0)
                            continue
                        if not more:
                            break
                        if tap:
                            with total_bytes_lock: total_bytes_received += len(more)
                            print_inline_byte_count(); print()
                            print(hexdump(more, start_off=offset, line_bytes=line_bytes, show_ascii=show_ascii))
                            offset += len(more)
                        buf.extend(more)

                    # Now we have a full frame (or at least the claimed length)
                    frame = bytes(buf[:frame_total])
                    # Remove from buffer
                    del buf[:frame_total]

                    # Parse payload (best-effort)
                    payload = frame[_ENV_SIZE:frame_total]
                    if GamepadState is not None and env.length != _GS_SIZE:
                        log.warning(f"Length mismatch: env.length={env.length} vs GamepadState.SIZE={_GS_SIZE}")
                    try:
                        if GamepadState is not None:
                            state = GamepadState.from_bytes(payload)
                            log.info(f"Envelope(seq={env.seq}, ver={env.version}, ts_us={env.timestamp_us}) | {state}")
                        else:
                            log.info(f"Envelope(seq={env.seq}, len={env.length})")
                    except Exception as e:
                        log.error(f"Failed to parse GamepadState ({env.length} bytes): {e}")
                    continue  # try to parse next frame if buffer has data

            # Need more bytes: await reader
            if reader_task.done():
                chunk = reader_task.result()
                if not chunk:
                    break
                # TAP: dump raw bytes unconditionally
                if tap:
                    with total_bytes_lock: total_bytes_received += len(chunk)
                    print_inline_byte_count(); print()
                    print(hexdump(chunk, start_off=offset, line_bytes=line_bytes, show_ascii=show_ascii))
                    offset += len(chunk)
                buf.extend(chunk)
                # start another read
                reader_task = asyncio.create_task(reader.read(4096))
            else:
                # Briefly yield until more bytes arrive or header becomes parseable
                await asyncio.sleep(0.001)

    except Exception as e:
        log.exception(f"Unhandled sink error: {e}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        log.info("Client disconnected")

# ===== TCP SOURCE (tap prints everything sent) =====
def pack_gamepad_state(seq: int) -> bytes:
    if GamepadState is None:
        return bytes(26)  # default payload length used in your capture
    # Synthetic motion pattern
    def tri(n, period, amp):
        t = n % period
        half = period // 2
        return int((t if t < half else period - t) / half * amp * (1 if t < half else -1))
    device_id = 0
    flags = 0x01
    buttons = 0x0001 if (seq // 30) % 2 == 0 else 0x0002
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

async def tcp_source(target: Tuple[str, int], rate_hz: int, msg_type: int, version: int,
                     tap: bool, line_bytes: int, show_ascii: bool):
    host, port = target
    backoff = 1.0
    offset = 0
    while True:
        try:
            logger.info(f"TCP source connecting to {host}:{port} ...")
            reader, writer = await asyncio.open_connection(host, port)
            logger.info("Connected")
            sock = writer.get_extra_info("socket")
            if sock:
                try: sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                except OSError: pass
            backoff = 1.0
            seq = 0
            period = 1.0 / max(1, rate_hz)
            while True:
                payload = pack_gamepad_state(seq)
                env = pack_envelope(msg_type, version, payload, seq)
                packet = env + payload
                writer.write(packet)
                await writer.drain()
                if tap:
                    print(hexdump(packet, start_off=offset, line_bytes=line_bytes, show_ascii=show_ascii)); offset += len(packet)
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

# ===== UDP SINK (tap prints every datagram) =====
class UDPSinkProtocol(asyncio.DatagramProtocol):
    def __init__(self, tap: bool, line_bytes: int, show_ascii: bool):
        super().__init__()
        self.tap = tap
        self.line_bytes = line_bytes
        self.show_ascii = show_ascii
        self.log = logging.getLogger("udp-sink")

    def datagram_received(self, data: bytes, addr):
        global total_bytes_received
        if self.tap:
            with total_bytes_lock: total_bytes_received += len(data)
            print_inline_byte_count(); print()
            print(f"{addr} len={len(data)}")
            print(hexdump(data, start_off=0, line_bytes=self.line_bytes, show_ascii=self.show_ascii))

        if len(data) < _ENV_SIZE:
            self.log.warning(f"{addr} -> short packet ({len(data)} bytes)")
            return
        env = WireEnvelope.from_bytes(data[:_ENV_SIZE])
        payload = data[_ENV_SIZE:_ENV_SIZE + env.length]
        if len(payload) != env.length:
            self.log.warning(f"{addr} -> truncated payload: expected {env.length}, got {len(payload)}")
            return
        if GamepadState is not None and env.length != _GS_SIZE:
            self.log.warning(f"{addr} -> length mismatch (env={env.length}, gs={_GS_SIZE})")
        try:
            if GamepadState is not None:
                state = GamepadState.from_bytes(payload)
                self.log.info(f"{addr} | seq={env.seq} ver={env.version} ts_us={env.timestamp_us} | {state}")
            else:
                self.log.info(f"{addr} | seq={env.seq} len={env.length}")
        except Exception as e:
            self.log.error(f"{addr} -> GamepadState parse error: {e}")

async def udp_sink(bind: str, port: int, tap: bool, line_bytes: int, show_ascii: bool):
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(
        lambda: UDPSinkProtocol(tap, line_bytes, show_ascii),
        local_addr=(bind, port),
        allow_broadcast=True,
        reuse_port=False,
    )
    logger.info(f"UDP sink listening on {bind}:{port}")
    try:
        await asyncio.Future()
    finally:
        transport.close()

# ===== UDP SOURCE (tap prints every packet you send) =====
async def udp_source(target: Tuple[str, int], rate_hz: int, msg_type: int, version: int,
                     bind: Optional[str], tap: bool, line_bytes: int, show_ascii: bool):
    host, port = target
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if bind:
        sock.bind((bind, 0))
    sock.setblocking(False)
    loop = asyncio.get_running_loop()
    seq = 0
    period = 1.0 / max(1, rate_hz)
    logger.info(f"UDP source sending to {host}:{port} at {rate_hz} Hz")
    offset = 0
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
                    if tap:
                        print(hexdump(packet, start_off=offset, line_bytes=line_bytes, show_ascii=show_ascii)); offset += len(packet)
                except Exception as e:
                    logger.warning(f"UDP send error: {e}")
            seq += 1
            await asyncio.sleep(period)
    finally:
        sock.close()

# ===== Orchestration =====
async def main_async(args):
    tasks = []

    def add_task(coro):
        tasks.append(asyncio.create_task(coro))

    if args.mode in ("tcp", "both"):
        if args.role == "sink":
            add_task(tcp_sink(args.bind, args.port, args.read_timeout, args.tap, args.line_bytes, args.show_ascii))
        elif args.role == "source":
            if not args.target:
                raise SystemExit("--target host:port required for TCP source")
            target = parse_hostport(args.target)
            add_task(tcp_source(target, args.rate, args.msg_type, args.version, args.tap, args.line_bytes, args.show_ascii))

    if args.mode in ("udp", "both"):
        if args.role == "sink":
            add_task(udp_sink(args.bind, args.port, args.tap, args.line_bytes, args.show_ascii))
        elif args.role == "source":
            if not args.target:
                raise SystemExit("--target host:port required for UDP source")
            target = parse_hostport(args.target)
            add_task(udp_source(target, args.rate, args.msg_type, args.version,
                                args.bind if args.bind != "0.0.0.0" else None,
                                args.tap, args.line_bytes, args.show_ascii))

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
    p = argparse.ArgumentParser(description="Relay Test Server (TCP/UDP sink/source) with tap mode")
    p.add_argument("--mode", choices=["tcp", "udp", "both"], default="tcp", help="transport to run")
    p.add_argument("--role", choices=["sink", "source"], default="sink", help="sink=receive/parse, source=send")
    p.add_argument("--bind", default="0.0.0.0", help="bind address for sink/UDP source (default 0.0.0.0)")
    p.add_argument("--port", type=int, default=26543, help="port to listen on (sink) or bind for UDP source")
    p.add_argument("--target", help="host:port to send to (source modes)")
    p.add_argument("--rate", type=int, default=120, help="send rate in Hz for source modes")
    p.add_argument("--msg-type", type=int, default=1, help="Envelope.msg_type value to send (source)")
    p.add_argument("--version", type=int, default=1, help="Envelope.version value to send (source)")
    p.add_argument("--read-timeout", type=float, default=10.0, help="per-read timeout seconds (TCP sink)")
    # tap options
    p.add_argument("--tap", action="store_true", help="print raw hex of all bytes (source: sent, sink: received)")
    p.add_argument("--line-bytes", type=int, default=16, help="bytes per hex dump line in tap mode")
    p.add_argument("--show-ascii", action="store_true", help="append ASCII gutter in hex dump")
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
