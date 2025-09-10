import struct

class Envelope:
    STRUCT_FORMAT = '<BBH I Q'  # msg_type, version, length (uint16), seq (uint32), timestamp_us (uint64)
    SIZE = struct.calcsize(STRUCT_FORMAT)

    def __init__(self, msg_type, version, length, seq, timestamp_us):
        self.msg_type = msg_type
        self.version = version
        self.length = length
        self.seq = seq
        self.timestamp_us = timestamp_us

    @classmethod
    def from_bytes(cls, data: bytes):
        fields = struct.unpack(cls.STRUCT_FORMAT, data)
        return cls(*fields)

    def __repr__(self):
        return f"Envelope(msg_type={self.msg_type}, version={self.version}, length={self.length}, seq={self.seq}, timestamp_us={self.timestamp_us})"

class GamepadState:
    STRUCT_FORMAT = '<BBHhhhhHHhh' # device_id, flags, buttons, lx, ly, rx, ry, l2, r2, dpad_x, dpad_y
    SIZE = struct.calcsize(STRUCT_FORMAT)

    def __init__(self, device_id, flags, buttons, lx, ly, rx, ry, l2, r2, dpad_x, dpad_y):
        self.device_id = device_id
        self.flags = flags
        self.buttons = buttons
        self.lx = lx
        self.ly = ly
        self.rx = rx
        self.ry = ry
        self.l2 = l2
        self.r2 = r2
        self.dpad_x = dpad_x
        self.dpad_y = dpad_y
        # TODO: Add sensor fields if protocol is extended

    @classmethod
    def from_bytes(cls, data: bytes):
        fields = struct.unpack(cls.STRUCT_FORMAT, data[:cls.SIZE])
        return cls(*fields)

    def __repr__(self):
        return (
            f"GamepadState(dev_id={self.device_id}, flags=0x{self.flags:02X}, btns=0x{self.buttons:04X}, "
            f"lx={self.lx}, ly={self.ly}, rx={self.rx}, ry={self.ry}, l2={self.l2}, r2={self.r2}, dpad_x={self.dpad_x}, dpad_y={self.dpad_y})"
        )
