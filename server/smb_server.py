from __future__ import annotations

import hashlib
import hmac
import logging
import os
import shutil
import socket
import struct
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from server.config import SettingsManager
from server.watcher import PipelineWorker

logger = logging.getLogger("pixelnas.smb")

# NTSTATUS constants
STATUS_SUCCESS = 0x00000000
STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016
STATUS_NO_MORE_FILES = 0x80000006
STATUS_INVALID_PARAMETER = 0xC000000D
STATUS_NO_SUCH_FILE = 0xC000000F
STATUS_END_OF_FILE = 0xC0000011
STATUS_ACCESS_DENIED = 0xC0000022
STATUS_OBJECT_NAME_INVALID = 0xC0000033
STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034
STATUS_OBJECT_NAME_COLLISION = 0xC0000035
STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003A
STATUS_LOGON_FAILURE = 0xC000006D
STATUS_FILE_IS_A_DIRECTORY = 0xC00000BA
STATUS_NOT_SUPPORTED = 0xC00000BB
STATUS_BAD_NETWORK_NAME = 0xC00000CC
STATUS_DIRECTORY_NOT_EMPTY = 0xC0000101
STATUS_FILE_CLOSED = 0xC0000128
STATUS_USER_SESSION_DELETED = 0xC0000203
STATUS_NETWORK_NAME_DELETED = 0xC00000C9
STATUS_FS_DRIVER_REQUIRED = 0xC000019C

# SMB2 Commands
SMB2_NEGOTIATE = 0x0000
SMB2_SESSION_SETUP = 0x0001
SMB2_LOGOFF = 0x0002
SMB2_TREE_CONNECT = 0x0003
SMB2_TREE_DISCONNECT = 0x0004
SMB2_CREATE = 0x0005
SMB2_CLOSE = 0x0006
SMB2_FLUSH = 0x0007
SMB2_READ = 0x0008
SMB2_WRITE = 0x0009
SMB2_LOCK = 0x000A
SMB2_IOCTL = 0x000B
SMB2_CANCEL = 0x000C
SMB2_ECHO = 0x000D
SMB2_QUERY_DIRECTORY = 0x000E
SMB2_CHANGE_NOTIFY = 0x000F
SMB2_QUERY_INFO = 0x0010
SMB2_SET_INFO = 0x0011

# SMB2 Header Flags
SMB2_FLAGS_SERVER_TO_REDIR = 0x00000001
SMB2_FLAGS_RELATED_OPERATIONS = 0x00000004
SMB2_FLAGS_SIGNED = 0x00000008

# File Attributes
FILE_ATTRIBUTE_DIRECTORY = 0x00000010
FILE_ATTRIBUTE_ARCHIVE = 0x00000020
FILE_ATTRIBUTE_NORMAL = 0x00000080

# Create Dispositions & Actions
FILE_SUPERSEDE = 0x00000000
FILE_OPEN = 0x00000001
FILE_CREATE = 0x00000002
FILE_OPEN_IF = 0x00000003
FILE_OVERWRITE = 0x00000004
FILE_OVERWRITE_IF = 0x00000005

FILE_SUPERSEDED = 0x00000000
FILE_OPENED = 0x00000001
FILE_CREATED = 0x00000002
FILE_OVERWRITTEN = 0x00000003

FILE_DIRECTORY_FILE = 0x00000001
FILE_NON_DIRECTORY_FILE = 0x00000040
FILE_DELETE_ON_CLOSE = 0x00001000

EPOCH_DIFF_100NS = 116444736000000000


def unix_to_filetime(ts: float) -> int:
    return max(0, int(ts * 10_000_000) + EPOCH_DIFF_100NS)


def filetime_to_unix(ft: int) -> float:
    return max(0.0, (ft - EPOCH_DIFF_100NS) / 10_000_000.0)


def md4_hash(data: bytes) -> bytes:
    """Pure-Python RFC 1320 MD4 so OpenSSL 3 legacy-provider restrictions never fail."""
    def _lrot(x: int, n: int) -> int:
        return ((x << n) | (x >> (32 - n))) & 0xFFFFFFFF

    msg = bytearray(data)
    bit_len = (len(msg) * 8) & 0xFFFFFFFFFFFFFFFF
    msg.append(0x80)
    while (len(msg) % 64) != 56:
        msg.append(0)
    msg.extend(struct.pack("<Q", bit_len))

    a, b, c, d = 0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476
    for offset in range(0, len(msg), 64):
        x = struct.unpack("<16I", msg[offset : offset + 64])
        aa, bb, cc, dd = a, b, c, d

        for i, s in zip(range(16), [3, 7, 11, 19] * 4):
            f = (b & c) | ((~b & 0xFFFFFFFF) & d)
            a = _lrot((a + f + x[i]) & 0xFFFFFFFF, s)
            a, b, c, d = d, a, b, c

        order2 = [0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15]
        for idx, s in zip(order2, [3, 5, 9, 13] * 4):
            g = (b & c) | (b & d) | (c & d)
            a = _lrot((a + g + x[idx] + 0x5A827999) & 0xFFFFFFFF, s)
            a, b, c, d = d, a, b, c

        order3 = [0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15]
        for idx, s in zip(order3, [3, 9, 11, 15] * 4):
            h = b ^ c ^ d
            a = _lrot((a + h + x[idx] + 0x6ED9EBA1) & 0xFFFFFFFF, s)
            a, b, c, d = d, a, b, c

        a = (a + aa) & 0xFFFFFFFF
        b = (b + bb) & 0xFFFFFFFF
        c = (c + cc) & 0xFFFFFFFF
        d = (d + dd) & 0xFFFFFFFF

    return struct.pack("<4I", a, b, c, d)


def rc4_crypt(key: bytes, data: bytes) -> bytes:
    S = list(range(256))
    j = 0
    for i in range(256):
        j = (j + S[i] + key[i % len(key)]) % 256
        S[i], S[j] = S[j], S[i]
    i = j = 0
    out = bytearray(len(data))
    for k, b in enumerate(data):
        i = (i + 1) % 256
        j = (j + S[i]) % 256
        S[i], S[j] = S[j], S[i]
        out[k] = b ^ S[(S[i] + S[j]) % 256]
    return bytes(out)


def _der_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(raw)]) + raw


def _der_tlv(tag: int, body: bytes) -> bytes:
    return bytes([tag]) + _der_len(len(body)) + body


SPNEGO_OID = bytes([0x06, 0x06, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x02])
NTLMSSP_OID = bytes([0x06, 0x0A, 0x2B, 0x06, 0x01, 0x04, 0x01, 0x82, 0x37, 0x02, 0x02, 0x0A])


def spnego_neg_token_init_ntlm() -> bytes:
    mech_types = _der_tlv(0xA0, _der_tlv(0x30, NTLMSSP_OID))
    neg_token_init = _der_tlv(0xA0, _der_tlv(0x30, mech_types))
    return _der_tlv(0x60, SPNEGO_OID + neg_token_init)


def spnego_neg_token_resp_challenge(ntlm_type2: bytes) -> bytes:
    state = _der_tlv(0xA0, _der_tlv(0x0A, b"\x01"))  # accept-incomplete
    supported_mech = _der_tlv(0xA1, NTLMSSP_OID)
    resp_token = _der_tlv(0xA2, _der_tlv(0x04, ntlm_type2))
    return _der_tlv(0xA1, _der_tlv(0x30, state + supported_mech + resp_token))


def spnego_neg_token_resp_accept() -> bytes:
    state = _der_tlv(0xA0, _der_tlv(0x0A, b"\x00"))  # accept-completed
    return _der_tlv(0xA1, _der_tlv(0x30, state))


def extract_ntlmssp_token(sec_blob: bytes) -> Optional[bytes]:
    idx = sec_blob.find(b"NTLMSSP\x00")
    return sec_blob[idx:] if idx >= 0 else None


def build_ntlm_type2_challenge(server_challenge: bytes, server_name: str = "PIXELNAS") -> bytes:
    nb_bytes = server_name.encode("utf-16-le")
    av_pairs = bytearray()
    for av_id, val in [
        (0x0002, nb_bytes),  # MsvAvNbDomainName
        (0x0001, nb_bytes),  # MsvAvNbComputerName
        (0x0004, nb_bytes),  # MsvAvDnsDomainName
        (0x0003, nb_bytes),  # MsvAvDnsComputerName
        (0x0007, struct.pack("<Q", unix_to_filetime(time.time()))),  # MsvAvTimestamp
    ]:
        av_pairs.extend(struct.pack("<HH", av_id, len(val)))
        av_pairs.extend(val)
    av_pairs.extend(struct.pack("<HH", 0x0000, 0))  # MsvAvEOL

    flags = (
        0x00000001  # NTLMSSP_NEGOTIATE_UNICODE
        | 0x00000200  # NTLMSSP_NEGOTIATE_NTLM
        | 0x00008000  # NTLMSSP_NEGOTIATE_ALWAYS_SIGN
        | 0x00010000  # NTLMSSP_TARGET_TYPE_SERVER
        | 0x00080000  # NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY
        | 0x00800000  # NTLMSSP_NEGOTIATE_TARGET_INFO
        | 0x02000000  # NTLMSSP_NEGOTIATE_VERSION
        | 0x20000000  # NTLMSSP_NEGOTIATE_128
        | 0x40000000  # NTLMSSP_NEGOTIATE_KEY_EXCH
        | 0x80000000  # NTLMSSP_NEGOTIATE_56
    )

    header_len = 56
    target_name_offset = header_len
    target_info_offset = target_name_offset + len(nb_bytes)

    msg = bytearray()
    msg.extend(b"NTLMSSP\x00")
    msg.extend(struct.pack("<I", 2))
    msg.extend(struct.pack("<HHI", len(nb_bytes), len(nb_bytes), target_name_offset))
    msg.extend(struct.pack("<I", flags))
    msg.extend(server_challenge)
    msg.extend(b"\x00" * 8)  # Reserved
    msg.extend(struct.pack("<HHI", len(av_pairs), len(av_pairs), target_info_offset))
    msg.extend(struct.pack("<BBHBBBB", 10, 0, 19041, 0, 0, 0, 15))
    msg.extend(nb_bytes)
    msg.extend(av_pairs)
    return bytes(msg)


def verify_ntlm_type3(
    token: bytes,
    server_challenge: bytes,
    auth_required: bool,
    expected_user: str,
    expected_password: str,
) -> Tuple[bool, bool, bytes]:
    """
    Returns (accepted, is_guest, session_key_16).
    Supports both NTLMv2 full cryptographic verification (deriving real SessionKey for SMB2 signing!)
    and seamless Guest/Anonymous household access when auth_required is False.
    """
    if len(token) < 64 or not token.startswith(b"NTLMSSP\x00"):
        return (not auth_required, True, b"\x00" * 16)

    msg_type = struct.unpack_from("<I", token, 8)[0]
    if msg_type != 3:
        return (False, True, b"\x00" * 16)

    def _read_sec_buf(pos: int) -> bytes:
        ln, _max_ln, off = struct.unpack_from("<HHI", token, pos)
        if ln == 0 or off + ln > len(token):
            return b""
        return token[off : off + ln]

    nt_resp = _read_sec_buf(20)
    domain_b = _read_sec_buf(28)
    user_b = _read_sec_buf(36)
    enc_rand_session_key = _read_sec_buf(52)
    flags = struct.unpack_from("<I", token, 60)[0]

    username = user_b.decode("utf-16-le", errors="ignore")
    domain = domain_b.decode("utf-16-le", errors="ignore")

    # Anonymous login (blank username AND blank NTLM response)
    if not username and not nt_resp:
        if auth_required:
            return (False, True, b"\x00" * 16)
        return (True, True, b"\x00" * 16)

    def _try_ntlmv2_with_password(pw: str) -> Optional[bytes]:
        if len(nt_resp) < 16:
            return None
        nt_proof_str = nt_resp[:16]
        client_blob = nt_resp[16:]
        nt_hash = md4_hash(pw.encode("utf-16-le"))
        # Try both exact domain and empty domain because clients vary in domain casing/blanking
        for dom_candidate in (domain, "", domain.upper()):
            identity = (username.upper() + dom_candidate).encode("utf-16-le")
            resp_key_nt = hmac.new(nt_hash, identity, hashlib.md5).digest()
            expected_proof = hmac.new(
                resp_key_nt, server_challenge + client_blob, hashlib.md5
            ).digest()
            if hmac.compare_digest(nt_proof_str, expected_proof):
                key_exchange_key = hmac.new(resp_key_nt, nt_proof_str, hashlib.md5).digest()
                if (flags & 0x40000000) and len(enc_rand_session_key) == 16:
                    return rc4_crypt(key_exchange_key, enc_rand_session_key)
                return key_exchange_key
        return None

    if auth_required:
        if username.lower() != expected_user.lower():
            return (False, False, b"\x00" * 16)
        session_key = _try_ntlmv2_with_password(expected_password)
        if session_key is None:
            return (False, False, b"\x00" * 16)
        return (True, False, session_key)

    # Auth not required -> recover exact NTLMv2 SessionKey for configured password, "pixel", or empty
    # password "" (which satisfies strict SMB2 packet signing even for passwordless Guest / Member logins!)
    for candidate_pw in [expected_password, "pixel", ""]:
        sk = _try_ntlmv2_with_password(candidate_pw)
        if sk is not None:
            return (True, False, sk)

    return (True, True, b"\x00" * 16)


@dataclass
class OpenHandle:
    handle_id: int
    share_kind: str  # "root", "incoming", "output", "quarantine", "ipc"
    rel_path: str
    fs_path: Optional[Path]
    is_dir: bool
    delete_on_close: bool = False
    was_written: bool = False
    dir_scan_done: bool = False
    dir_scan_index: int = 0


@dataclass
class SmbSession:
    session_id: int
    server_challenge: bytes = field(default_factory=lambda: os.urandom(8))
    authenticated: bool = False
    is_guest: bool = False
    session_key: bytes = b"\x00" * 16
    trees: Dict[int, str] = field(default_factory=dict)  # tree_id -> share_kind
    handles: Dict[int, OpenHandle] = field(default_factory=dict)
    next_tree_id: int = 1
    next_handle_id: int = 1


class EmbeddedSmbServer:
    """
    Pure-Python Microsoft [MS-SMB2] (SMB 2.0.2 / 2.1) Samba File Share Server for Pixel Photo NAS.
    Exposes:
      - \\\\<host>\\PixelNAS     -> Unified share containing Incoming\\, Output\\, and Quarantine\\
      - \\\\<host>\\Incoming     -> Direct share for Incoming\\ (StorageSaver\\, Original\\, Family\\)
      - \\\\<host>\\Output       -> Direct share for Google Photos Output\\
      - \\\\<host>\\Quarantine   -> Direct share for Quarantine\\
    """

    SERVER_GUID = uuid.UUID("70697865-6c6e-6173-736d-623273657276").bytes_le

    def __init__(self, settings_mgr: SettingsManager, worker: PipelineWorker) -> None:
        self.settings_mgr = settings_mgr
        self.worker = worker
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._lock = threading.Lock()
        self.bound_port: int = 0

    def start_or_reload(self) -> None:
        with self._lock:
            self._stop_unlocked()
            cfg = self.settings_mgr.get()
            if not getattr(cfg, "smb_enabled", True):
                return

            preferred_port = int(getattr(cfg, "smb_port", 4445))
            candidates = [preferred_port] if preferred_port == 0 else [preferred_port, 4445, 0]
            self._stop_event.clear()

            for port in candidates:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                try:
                    s.bind(("0.0.0.0", port))
                    s.listen(64)
                    s.settimeout(0.5)
                    self._sock = s
                    self.bound_port = s.getsockname()[1]
                    self._thread = threading.Thread(
                        target=self._accept_loop,
                        name="pixelnas-smb2",
                        daemon=True,
                    )
                    self._thread.start()
                    logger.info("SMB2 Samba server listening on 0.0.0.0:%s", self.bound_port)
                    return
                except Exception as exc:
                    s.close()
                    logger.debug("SMB bind port %s skipped: %s", port, exc)

    def stop(self) -> None:
        with self._lock:
            self._stop_unlocked()

    def _stop_unlocked(self) -> None:
        self._stop_event.set()
        if self._sock is not None:
            try:
                self._sock.close()
            except Exception:
                pass
            self._sock = None
        self.bound_port = 0

    def _accept_loop(self) -> None:
        while not self._stop_event.is_set() and self._sock is not None:
            try:
                conn, addr = self._sock.accept()
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                threading.Thread(
                    target=self._client_loop,
                    args=(conn, addr),
                    daemon=True,
                ).start()
            except socket.timeout:
                continue
            except OSError:
                break

    def _recv_exact(self, conn: socket.socket, n: int) -> Optional[bytes]:
        buf = bytearray()
        while len(buf) < n:
            chunk = conn.recv(n - len(buf))
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)

    def _client_loop(self, conn: socket.socket, _addr) -> None:
        sessions: Dict[int, SmbSession] = {}
        next_session_id = 0x100001
        try:
            while not self._stop_event.is_set():
                hdr4 = self._recv_exact(conn, 4)
                if not hdr4:
                    break
                frame_len = struct.unpack(">I", hdr4)[0] & 0x00FFFFFF
                if frame_len <= 0 or frame_len > 16 * 1024 * 1024:
                    break
                payload = self._recv_exact(conn, frame_len)
                if payload is None:
                    break

                # Upgrade legacy SMB1 Negotiate -> SMB2 Negotiate seamlessly
                if payload[:4] == b"\xffSMB":
                    resp_pkt = self._handle_smb1_negotiate(payload)
                    conn.sendall(struct.pack(">I", len(resp_pkt)) + resp_pkt)
                    continue

                if payload[:4] != b"\xfeSMB":
                    break

                resp_frame, next_session_id = self._process_compound_frame(
                    payload, sessions, next_session_id
                )
                if resp_frame:
                    conn.sendall(struct.pack(">I", len(resp_frame)) + resp_frame)
        except Exception as exc:
            logger.debug("SMB client disconnected: %s", exc)
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _handle_smb1_negotiate(self, smb1_pkt: bytes) -> bytes:
        dialect = 0x02FF if b"SMB 2.???" in smb1_pkt else 0x0202
        return self._build_smb2_packet(
            command=SMB2_NEGOTIATE,
            status=STATUS_SUCCESS,
            message_id=0,
            session_id=0,
            tree_id=0,
            body=self._build_negotiate_body(dialect),
        )

    def _build_negotiate_body(self, dialect: int) -> bytes:
        sec_blob = spnego_neg_token_init_ntlm()
        now_ft = unix_to_filetime(time.time())
        body = bytearray()
        body.extend(struct.pack("<H", 65))  # StructureSize
        body.extend(struct.pack("<H", 0x0001))  # Signing Enabled (optional)
        body.extend(struct.pack("<H", dialect))  # DialectRevision
        body.extend(struct.pack("<H", 0))  # Reserved / NegotiateContextCount
        body.extend(self.SERVER_GUID)
        body.extend(struct.pack("<I", 0x00000007))  # Capabilities: DFS | LEASING | LARGE_MTU
        body.extend(struct.pack("<I", 1048576))  # MaxTransactSize (1 MiB)
        body.extend(struct.pack("<I", 1048576))  # MaxReadSize (1 MiB)
        body.extend(struct.pack("<I", 1048576))  # MaxWriteSize (1 MiB)
        body.extend(struct.pack("<Q", now_ft))  # SystemTime
        body.extend(struct.pack("<Q", now_ft - 36000000000))  # ServerStartTime
        body.extend(struct.pack("<HH", 64 + 64, len(sec_blob)))  # SecurityBufferOffset = 128
        body.extend(struct.pack("<I", 0))
        body.extend(sec_blob)
        return bytes(body)

    def _build_smb2_packet(
        self,
        command: int,
        status: int,
        message_id: int,
        session_id: int,
        tree_id: int,
        body: bytes,
        credits: int = 32,
        extra_flags: int = 0,
        sign_key: Optional[bytes] = None,
    ) -> bytes:
        flags = SMB2_FLAGS_SERVER_TO_REDIR | extra_flags
        if sign_key is not None:
            flags |= SMB2_FLAGS_SIGNED
        hdr = bytearray(64)
        hdr[0:4] = b"\xfeSMB"
        struct.pack_into("<H", hdr, 4, 64)
        struct.pack_into("<H", hdr, 6, 1)  # CreditCharge
        struct.pack_into("<I", hdr, 8, status)
        struct.pack_into("<H", hdr, 12, command)
        struct.pack_into("<H", hdr, 14, max(1, credits))
        struct.pack_into("<I", hdr, 16, flags)
        struct.pack_into("<I", hdr, 20, 0)  # NextCommand
        struct.pack_into("<Q", hdr, 24, message_id)
        struct.pack_into("<I", hdr, 32, 0)  # Reserved
        struct.pack_into("<I", hdr, 36, tree_id)
        struct.pack_into("<Q", hdr, 40, session_id)
        pkt = bytearray(hdr) + bytearray(body)
        if sign_key is not None:
            sig = hmac.new(sign_key[:16], bytes(pkt), hashlib.sha256).digest()[:16]
            pkt[48:64] = sig
        return bytes(pkt)

    def _error_body(self) -> bytes:
        return struct.pack("<HBBIB", 9, 0, 0, 0, 0)

    def _process_compound_frame(
        self,
        frame: bytes,
        sessions: Dict[int, SmbSession],
        next_session_id: int,
    ) -> Tuple[bytes, int]:
        req_slices: List[bytes] = []
        offset = 0
        while offset < len(frame):
            if offset + 64 > len(frame):
                break
            next_cmd = struct.unpack_from("<I", frame, offset + 20)[0]
            if next_cmd == 0:
                req_slices.append(frame[offset:])
                break
            req_slices.append(frame[offset : offset + next_cmd])
            offset += next_cmd

        raw_responses: List[Tuple[bytearray, Optional[bytes]]] = []
        last_session_id = 0
        last_tree_id = 0
        last_handle_id = 0

        for req in req_slices:
            cmd = struct.unpack_from("<H", req, 12)[0]
            credits_req = struct.unpack_from("<H", req, 14)[0]
            flags = struct.unpack_from("<I", req, 16)[0]
            msg_id = struct.unpack_from("<Q", req, 24)[0]
            tree_id = struct.unpack_from("<I", req, 36)[0]
            sess_id = struct.unpack_from("<Q", req, 40)[0]
            related = bool(flags & SMB2_FLAGS_RELATED_OPERATIONS)

            if related:
                if sess_id in (0, 0xFFFFFFFFFFFFFFFF):
                    sess_id = last_session_id
                if tree_id in (0, 0xFFFFFFFF):
                    tree_id = last_tree_id

            status, resp_body, sess_id, tree_id, opened_hid, next_session_id = (
                self._dispatch_single(
                    cmd, req, sessions, sess_id, tree_id, last_handle_id if related else 0, next_session_id
                )
            )
            if opened_hid:
                last_handle_id = opened_hid
            last_session_id = sess_id
            last_tree_id = tree_id

            sess = sessions.get(sess_id)
            should_sign = (
                sess is not None
                and sess.authenticated
                and not sess.is_guest
                and cmd != SMB2_NEGOTIATE
                and (
                    bool(flags & SMB2_FLAGS_SIGNED)
                    or (cmd == SMB2_SESSION_SETUP and status == STATUS_SUCCESS)
                )
            )
            sign_key = sess.session_key if should_sign else None

            pkt = bytearray(
                self._build_smb2_packet(
                    command=cmd,
                    status=status,
                    message_id=msg_id,
                    session_id=sess_id,
                    tree_id=tree_id,
                    body=resp_body,
                    credits=max(32, credits_req),
                    extra_flags=(SMB2_FLAGS_RELATED_OPERATIONS if related else 0),
                    sign_key=None,  # Sign AFTER setting NextCommand alignment in compound frames!
                )
            )
            raw_responses.append((pkt, sign_key))

        # Chain compound responses on 8-byte boundaries and sign each slice accurately
        out = bytearray()
        for idx, (pkt, sign_key) in enumerate(raw_responses):
            is_last = idx == len(raw_responses) - 1
            if not is_last:
                pad = (8 - (len(pkt) % 8)) % 8
                pkt.extend(b"\x00" * pad)
                struct.pack_into("<I", pkt, 20, len(pkt))
            if sign_key is not None:
                cur_flags = struct.unpack_from("<I", pkt, 16)[0] | SMB2_FLAGS_SIGNED
                struct.pack_into("<I", pkt, 16, cur_flags)
                pkt[48:64] = b"\x00" * 16
                pkt[48:64] = hmac.new(sign_key[:16], bytes(pkt), hashlib.sha256).digest()[:16]
            out.extend(pkt)

        return bytes(out), next_session_id

    def _resolve_share_path(self, share_kind: str, rel_path: str) -> Tuple[Optional[Path], bool]:
        inc_dir, out_dir = self.settings_mgr.ensure_directories()
        quar_dir = self.settings_mgr.quarantine_path()
        norm = rel_path.replace("/", "\\").strip("\\")
        parts = [p for p in norm.split("\\") if p and p not in (".", "..")]

        if share_kind == "ipc":
            return None, False

        if share_kind == "root":
            if not parts:
                return None, True  # Virtual root combining Incoming, Output, Quarantine
            head = parts[0].lower()
            tail = parts[1:]
            base = {
                "incoming": inc_dir,
                "output": out_dir,
                "googlephotos_output": out_dir,
                "quarantine": quar_dir,
            }.get(head)
            if base is None:
                return None, False
            target = (base.joinpath(*tail)).resolve() if tail else base
            if not str(target).startswith(str(base)):
                return None, False
            return target, False

        base_map = {
            "incoming": inc_dir,
            "output": out_dir,
            "quarantine": quar_dir,
        }
        base = base_map.get(share_kind)
        if base is None:
            return None, False
        target = (base.joinpath(*parts)).resolve() if parts else base
        if not str(target).startswith(str(base)):
            return None, False
        return target, False

    def _is_inside_incoming(self, p: Path) -> bool:
        inc_dir, _ = self.settings_mgr.ensure_directories()
        resolved = p.resolve()
        return str(resolved).startswith(str(inc_dir) + os.sep)

    def _dispatch_single(
        self,
        cmd: int,
        req: bytes,
        sessions: Dict[int, SmbSession],
        sess_id: int,
        tree_id: int,
        inherited_hid: int,
        next_session_id: int,
    ) -> Tuple[int, bytes, int, int, int, int]:
        body = req[64:]

        if cmd == SMB2_NEGOTIATE:
            dialect_count = struct.unpack_from("<H", body, 2)[0]
            dialects = [
                struct.unpack_from("<H", body, 36 + i * 2)[0]
                for i in range(min(dialect_count, (len(body) - 36) // 2))
            ]
            chosen = 0x0210 if (0x0210 in dialects or 0x0300 in dialects or 0x0302 in dialects) else 0x0202
            return STATUS_SUCCESS, self._build_negotiate_body(chosen), 0, 0, 0, next_session_id

        if cmd == SMB2_SESSION_SETUP:
            sec_off, sec_len = struct.unpack_from("<HH", body, 12)
            sec_blob = req[sec_off : sec_off + sec_len] if sec_off + sec_len <= len(req) else b""
            if sess_id == 0 or sess_id not in sessions:
                sess_id = next_session_id
                next_session_id += 1
                sessions[sess_id] = SmbSession(session_id=sess_id)
            sess = sessions[sess_id]

            ntlm_tok = extract_ntlmssp_token(sec_blob)
            cfg = self.settings_mgr.get()
            auth_req = bool(cfg.auth_enabled and cfg.auth_password)

            if ntlm_tok is None:
                if auth_req:
                    return STATUS_LOGON_FAILURE, self._error_body(), sess_id, 0, 0, next_session_id
                sess.authenticated = True
                sess.is_guest = True
                accept_blob = spnego_neg_token_resp_accept()
                resp = struct.pack("<HHHH", 9, 0x0001, 64 + 8, len(accept_blob)) + accept_blob
                return STATUS_SUCCESS, resp, sess_id, 0, 0, next_session_id

            msg_type = struct.unpack_from("<I", ntlm_tok, 8)[0]
            if msg_type == 1:
                ch_blob = spnego_neg_token_resp_challenge(
                    build_ntlm_type2_challenge(sess.server_challenge)
                )
                resp = struct.pack("<HHHH", 9, 0, 64 + 8, len(ch_blob)) + ch_blob
                return STATUS_MORE_PROCESSING_REQUIRED, resp, sess_id, 0, 0, next_session_id

            ok, is_guest, sk = verify_ntlm_type3(
                ntlm_tok,
                sess.server_challenge,
                auth_req,
                cfg.auth_username or "pixel",
                cfg.auth_password or "",
            )
            if not ok:
                sessions.pop(sess_id, None)
                return STATUS_LOGON_FAILURE, self._error_body(), sess_id, 0, 0, next_session_id

            sess.authenticated = True
            sess.is_guest = is_guest
            sess.session_key = sk
            accept_blob = spnego_neg_token_resp_accept()
            sess_flags = 0x0001 if is_guest else 0x0000
            resp = struct.pack("<HHHH", 9, sess_flags, 64 + 8, len(accept_blob)) + accept_blob
            return STATUS_SUCCESS, resp, sess_id, 0, 0, next_session_id

        if cmd == SMB2_ECHO:
            return STATUS_SUCCESS, struct.pack("<HH", 4, 0), sess_id, tree_id, 0, next_session_id

        sess = sessions.get(sess_id)
        if sess is None or not sess.authenticated:
            return STATUS_USER_SESSION_DELETED, self._error_body(), sess_id, tree_id, 0, next_session_id

        if cmd == SMB2_LOGOFF:
            sessions.pop(sess_id, None)
            return STATUS_SUCCESS, struct.pack("<HH", 4, 0), sess_id, 0, 0, next_session_id

        if cmd == SMB2_TREE_CONNECT:
            cfg = self.settings_mgr.get()
            path_off, path_len = struct.unpack_from("<HH", body, 4)
            unc = req[path_off : path_off + path_len].decode("utf-16-le", errors="ignore")
            share_name = unc.replace("/", "\\").rstrip("\\").split("\\")[-1].lower()
            custom_share = (cfg.smb_share_name or "PixelNAS").strip().lower()
            share_map = {
                "ipc$": ("ipc", 0x02),
                "pixelnas": ("root", 0x01),
                custom_share: ("root", 0x01),
                "incoming": ("incoming", 0x01),
                "output": ("output", 0x01),
                "googlephotos_output": ("output", 0x01),
                "quarantine": ("quarantine", 0x01),
            }
            if share_name not in share_map:
                return STATUS_BAD_NETWORK_NAME, self._error_body(), sess_id, 0, 0, next_session_id
            kind, share_type = share_map[share_name]
            tid = sess.next_tree_id
            sess.next_tree_id += 1
            sess.trees[tid] = kind
            resp = struct.pack("<HBBIII", 16, share_type, 0, 0, 0, 0x001F01FF)
            return STATUS_SUCCESS, resp, sess_id, tid, 0, next_session_id

        share_kind = sess.trees.get(tree_id)
        if share_kind is None:
            return STATUS_NETWORK_NAME_DELETED, self._error_body(), sess_id, tree_id, 0, next_session_id

        if cmd == SMB2_TREE_DISCONNECT:
            sess.trees.pop(tree_id, None)
            return STATUS_SUCCESS, struct.pack("<HH", 4, 0), sess_id, tree_id, 0, next_session_id

        if cmd == SMB2_IOCTL:
            ctl_code = struct.unpack_from("<I", body, 4)[0]
            if ctl_code == 0x00060194:  # FSCTL_DFS_GET_REFERRALS
                return STATUS_FS_DRIVER_REQUIRED, self._error_body(), sess_id, tree_id, 0, next_session_id
            return STATUS_NOT_SUPPORTED, self._error_body(), sess_id, tree_id, 0, next_session_id

        if cmd == SMB2_CREATE:
            create_disp = struct.unpack_from("<I", body, 36)[0]
            create_opts = struct.unpack_from("<I", body, 40)[0]
            name_off, name_len = struct.unpack_from("<HH", body, 44)
            rel_name = (
                req[name_off : name_off + name_len].decode("utf-16-le", errors="ignore")
                if name_len > 0
                else ""
            ).replace("/", "\\").strip("\\")

            if share_kind == "ipc":
                hid = sess.next_handle_id
                sess.next_handle_id += 1
                sess.handles[hid] = OpenHandle(hid, "ipc", rel_name, None, False)
                return (
                    STATUS_SUCCESS,
                    self._build_create_resp(hid, FILE_OPENED, None, False),
                    sess_id,
                    tree_id,
                    hid,
                    next_session_id,
                )

            fs_path, is_virtual_root = self._resolve_share_path(share_kind, rel_name)
            if is_virtual_root:
                hid = sess.next_handle_id
                sess.next_handle_id += 1
                sess.handles[hid] = OpenHandle(hid, "root", "", None, True)
                return (
                    STATUS_SUCCESS,
                    self._build_create_resp(hid, FILE_OPENED, None, True),
                    sess_id,
                    tree_id,
                    hid,
                    next_session_id,
                )

            if fs_path is None:
                return STATUS_OBJECT_PATH_NOT_FOUND, self._error_body(), sess_id, tree_id, 0, next_session_id

            want_dir = bool(create_opts & FILE_DIRECTORY_FILE)
            want_file = bool(create_opts & FILE_NON_DIRECTORY_FILE)
            del_on_close = bool(create_opts & FILE_DELETE_ON_CLOSE)
            exists = fs_path.exists()

            if exists and want_dir and not fs_path.is_dir():
                return STATUS_NOT_SUPPORTED, self._error_body(), sess_id, tree_id, 0, next_session_id
            if exists and want_file and fs_path.is_dir():
                return STATUS_FILE_IS_A_DIRECTORY, self._error_body(), sess_id, tree_id, 0, next_session_id

            was_written = False
            if create_disp == FILE_OPEN:
                if not exists:
                    return STATUS_OBJECT_NAME_NOT_FOUND, self._error_body(), sess_id, tree_id, 0, next_session_id
                action = FILE_OPENED
            elif create_disp == FILE_CREATE:
                if exists:
                    return STATUS_OBJECT_NAME_COLLISION, self._error_body(), sess_id, tree_id, 0, next_session_id
                if not fs_path.parent.exists():
                    return STATUS_OBJECT_PATH_NOT_FOUND, self._error_body(), sess_id, tree_id, 0, next_session_id
                if want_dir:
                    fs_path.mkdir(parents=True, exist_ok=True)
                else:
                    fs_path.write_bytes(b"")
                    was_written = True
                action = FILE_CREATED
            elif create_disp == FILE_OPEN_IF:
                if exists:
                    action = FILE_OPENED
                else:
                    if not fs_path.parent.exists():
                        return STATUS_OBJECT_PATH_NOT_FOUND, self._error_body(), sess_id, tree_id, 0, next_session_id
                    if want_dir:
                        fs_path.mkdir(parents=True, exist_ok=True)
                    else:
                        fs_path.write_bytes(b"")
                        was_written = True
                    action = FILE_CREATED
            elif create_disp in (FILE_OVERWRITE, FILE_OVERWRITE_IF, FILE_SUPERSEDE):
                if create_disp == FILE_OVERWRITE and not exists:
                    return STATUS_OBJECT_NAME_NOT_FOUND, self._error_body(), sess_id, tree_id, 0, next_session_id
                if not fs_path.parent.exists():
                    fs_path.parent.mkdir(parents=True, exist_ok=True)
                fs_path.write_bytes(b"")
                was_written = True
                if not exists:
                    action = FILE_CREATED
                elif create_disp == FILE_SUPERSEDE:
                    action = FILE_SUPERSEDED
                else:
                    action = FILE_OVERWRITTEN
            else:
                return STATUS_INVALID_PARAMETER, self._error_body(), sess_id, tree_id, 0, next_session_id

            hid = sess.next_handle_id
            sess.next_handle_id += 1
            is_dir = fs_path.is_dir()
            sess.handles[hid] = OpenHandle(
                handle_id=hid,
                share_kind=share_kind,
                rel_path=rel_name,
                fs_path=fs_path,
                is_dir=is_dir,
                delete_on_close=del_on_close,
                was_written=was_written,
            )
            return (
                STATUS_SUCCESS,
                self._build_create_resp(hid, action, fs_path, is_dir),
                sess_id,
                tree_id,
                hid,
                next_session_id,
            )

        # Commands below operate on an open FileId
        hid = self._read_handle_id(cmd, body, inherited_hid)
        h = sess.handles.get(hid)
        if h is None:
            return STATUS_FILE_CLOSED, self._error_body(), sess_id, tree_id, 0, next_session_id

        if cmd == SMB2_CLOSE:
            sess.handles.pop(hid, None)
            st_attrs = FILE_ATTRIBUTE_DIRECTORY if h.is_dir else FILE_ATTRIBUTE_ARCHIVE
            eof = 0
            mtime = time.time()
            if h.fs_path is not None and h.fs_path.exists():
                st = h.fs_path.stat()
                eof = 0 if h.is_dir else st.st_size
                mtime = st.st_mtime
                if h.delete_on_close:
                    inc_dir, out_dir = self.settings_mgr.ensure_directories()
                    quar_dir = self.settings_mgr.quarantine_path()
                    if h.fs_path not in (inc_dir, out_dir, quar_dir):
                        if h.fs_path.is_dir():
                            try:
                                h.fs_path.rmdir()
                            except OSError:
                                pass
                        else:
                            h.fs_path.unlink(missing_ok=True)
                elif h.was_written and not h.is_dir and self._is_inside_incoming(h.fs_path):
                    self.worker.enqueue_if_auto(h.fs_path)
            ft = unix_to_filetime(mtime)
            alloc = ((eof + 4095) // 4096) * 4096
            resp = struct.pack(
                "<HHIQQQQQQI",
                60,
                0x0001,
                0,
                ft,
                ft,
                ft,
                ft,
                alloc,
                eof,
                st_attrs,
            )
            return STATUS_SUCCESS, resp, sess_id, tree_id, hid, next_session_id

        if cmd == SMB2_FLUSH:
            return STATUS_SUCCESS, struct.pack("<HH", 4, 0), sess_id, tree_id, hid, next_session_id

        if cmd == SMB2_WRITE:
            if h.fs_path is None or h.is_dir:
                return STATUS_ACCESS_DENIED, self._error_body(), sess_id, tree_id, hid, next_session_id
            data_off = struct.unpack_from("<H", body, 2)[0]
            data_len = struct.unpack_from("<I", body, 4)[0]
            file_off = struct.unpack_from("<Q", body, 8)[0]
            chunk = req[data_off : data_off + data_len]
            mode = "r+b" if h.fs_path.exists() else "wb"
            with h.fs_path.open(mode) as fp:
                fp.seek(file_off)
                fp.write(chunk)
            h.was_written = True
            resp = struct.pack("<HHIII", 17, 0, len(chunk), 0, 0)
            return STATUS_SUCCESS, resp, sess_id, tree_id, hid, next_session_id

        if cmd == SMB2_READ:
            if h.fs_path is None or h.is_dir or not h.fs_path.exists():
                return STATUS_END_OF_FILE, self._error_body(), sess_id, tree_id, hid, next_session_id
            read_len = struct.unpack_from("<I", body, 4)[0]
            file_off = struct.unpack_from("<Q", body, 8)[0]
            with h.fs_path.open("rb") as fp:
                fp.seek(file_off)
                data = fp.read(min(read_len, 1048576))
            if not data:
                return STATUS_END_OF_FILE, self._error_body(), sess_id, tree_id, hid, next_session_id
            resp = struct.pack("<HBBI", 17, 64 + 16, 0, len(data)) + struct.pack("<II", 0, 0) + data
            return STATUS_SUCCESS, resp, sess_id, tree_id, hid, next_session_id

        if cmd == SMB2_QUERY_DIRECTORY:
            info_class = body[2]
            q_flags = body[3]
            file_idx = struct.unpack_from("<I", body, 4)[0]
            pat_off, pat_len = struct.unpack_from("<HH", body, 24)
            out_max = struct.unpack_from("<I", body, 28)[0]
            pattern = (
                req[pat_off : pat_off + pat_len].decode("utf-16-le", errors="ignore")
                if pat_len > 0
                else "*"
            )

            if (q_flags & 0x01) or (q_flags & 0x04):  # RESTART_SCANS or INDEX_SPECIFIED
                h.dir_scan_index = file_idx if (q_flags & 0x04) else 0
                h.dir_scan_done = False

            if h.dir_scan_done:
                return STATUS_NO_MORE_FILES, self._error_body(), sess_id, tree_id, hid, next_session_id

            entries = self._list_dir_entries(h, pattern)
            if h.dir_scan_index >= len(entries):
                h.dir_scan_done = True
                return STATUS_NO_MORE_FILES, self._error_body(), sess_id, tree_id, hid, next_session_id

            single = bool(q_flags & 0x02)  # SL_RETURN_SINGLE_ENTRY
            buf, consumed = self._pack_dir_entries(
                entries[h.dir_scan_index :], info_class, out_max, single
            )
            h.dir_scan_index += consumed
            if h.dir_scan_index >= len(entries):
                h.dir_scan_done = True
            resp = struct.pack("<HH", 9, 64 + 8) + struct.pack("<I", len(buf)) + buf
            return STATUS_SUCCESS, resp, sess_id, tree_id, hid, next_session_id

        if cmd == SMB2_QUERY_INFO:
            info_type = body[2]
            info_class = body[3]
            out_buf = self._build_query_info(h, info_type, info_class)
            if out_buf is None:
                return STATUS_NOT_SUPPORTED, self._error_body(), sess_id, tree_id, hid, next_session_id
            resp = struct.pack("<HH", 9, 64 + 8) + struct.pack("<I", len(out_buf)) + out_buf
            return STATUS_SUCCESS, resp, sess_id, tree_id, hid, next_session_id

        if cmd == SMB2_SET_INFO:
            info_type = body[2]
            info_class = body[3]
            buf_len = struct.unpack_from("<I", body, 4)[0]
            buf_off = struct.unpack_from("<H", body, 8)[0]
            in_buf = req[buf_off : buf_off + buf_len]
            status = self._apply_set_info(h, info_type, info_class, in_buf)
            if status != STATUS_SUCCESS:
                return status, self._error_body(), sess_id, tree_id, hid, next_session_id
            return STATUS_SUCCESS, struct.pack("<H", 2), sess_id, tree_id, hid, next_session_id

        return STATUS_NOT_SUPPORTED, self._error_body(), sess_id, tree_id, hid, next_session_id

    def _read_handle_id(self, cmd: int, body: bytes, inherited_hid: int) -> int:
        pos_map = {
            SMB2_CLOSE: 8,
            SMB2_FLUSH: 8,
            SMB2_READ: 16,
            SMB2_WRITE: 16,
            SMB2_QUERY_DIRECTORY: 8,
            SMB2_QUERY_INFO: 24,
            SMB2_SET_INFO: 16,
        }
        pos = pos_map.get(cmd, 8)
        persistent, _volatile = struct.unpack_from("<QQ", body, pos)
        if persistent == 0xFFFFFFFFFFFFFFFF:
            return inherited_hid
        return persistent

    def _build_create_resp(
        self, hid: int, create_action: int, fs_path: Optional[Path], is_dir: bool
    ) -> bytes:
        mtime = time.time()
        eof = 0
        if fs_path is not None and fs_path.exists():
            st = fs_path.stat()
            mtime = st.st_mtime
            eof = 0 if is_dir else st.st_size
        ft = unix_to_filetime(mtime)
        alloc = ((eof + 4095) // 4096) * 4096
        attrs = FILE_ATTRIBUTE_DIRECTORY if is_dir else FILE_ATTRIBUTE_ARCHIVE
        return struct.pack(
            "<HBBIQQQQQQIIQQII",
            89,
            0,
            0,
            create_action,
            ft,
            ft,
            ft,
            ft,
            alloc,
            eof,
            attrs,
            0,
            hid,
            hid,
            0,
            0,
        )

    def _list_dir_entries(
        self, h: OpenHandle, pattern: str
    ) -> List[Tuple[str, bool, int, float]]:
        items: List[Tuple[str, bool, int, float]] = []
        now = time.time()
        if h.fs_path is None and h.share_kind == "root":
            inc_dir, out_dir = self.settings_mgr.ensure_directories()
            quar_dir = self.settings_mgr.quarantine_path()
            items.extend(
                [
                    (".", True, 0, now),
                    ("..", True, 0, now),
                    ("Incoming", True, 0, inc_dir.stat().st_mtime),
                    ("Output", True, 0, out_dir.stat().st_mtime),
                    ("Quarantine", True, 0, quar_dir.stat().st_mtime),
                ]
            )
        elif h.fs_path is not None and h.fs_path.is_dir():
            st = h.fs_path.stat()
            items.append((".", True, 0, st.st_mtime))
            items.append(("..", True, 0, st.st_mtime))
            for child in sorted(h.fs_path.iterdir(), key=lambda p: p.name.lower()):
                if child.name.startswith("."):
                    continue
                cst = child.stat()
                is_d = child.is_dir()
                items.append((child.name, is_d, 0 if is_d else cst.st_size, cst.st_mtime))

        if pattern and pattern not in ("*", "*.*"):
            pat_low = pattern.lower()
            if "*" not in pat_low and "?" not in pat_low:
                items = [e for e in items if e[0].lower() == pat_low]
        return items

    def _pack_dir_entries(
        self,
        entries: List[Tuple[str, bool, int, float]],
        info_class: int,
        max_bytes: int,
        single: bool,
    ) -> Tuple[bytes, int]:
        chunks: List[bytearray] = []
        total = 0
        for name, is_dir, size, mtime in entries:
            name_b = name.encode("utf-16-le")
            ft = unix_to_filetime(mtime)
            alloc = ((size + 4095) // 4096) * 4096
            attrs = FILE_ATTRIBUTE_DIRECTORY if is_dir else FILE_ATTRIBUTE_ARCHIVE
            file_id = hash(name) & 0x7FFFFFFFFFFFFFFF

            if info_class == 0x25:  # FileIdBothDirectoryInformation (104 + name)
                rec = bytearray(
                    struct.pack(
                        "<IIQQQQQQIIIBB24sHQ",
                        0,
                        0,
                        ft,
                        ft,
                        ft,
                        ft,
                        size,
                        alloc,
                        attrs,
                        len(name_b),
                        0,
                        0,
                        0,
                        b"\x00" * 24,
                        0,
                        file_id,
                    )
                    + name_b
                )
            elif info_class == 0x03:  # FileBothDirectoryInformation (94 + name)
                rec = bytearray(
                    struct.pack(
                        "<IIQQQQQQIIIBB24s",
                        0,
                        0,
                        ft,
                        ft,
                        ft,
                        ft,
                        size,
                        alloc,
                        attrs,
                        len(name_b),
                        0,
                        0,
                        0,
                        b"\x00" * 24,
                    )
                    + name_b
                )
            elif info_class == 0x26:  # FileIdFullDirectoryInformation (80 + name)
                rec = bytearray(
                    struct.pack(
                        "<IIQQQQQQIIIIQ",
                        0,
                        0,
                        ft,
                        ft,
                        ft,
                        ft,
                        size,
                        alloc,
                        attrs,
                        len(name_b),
                        0,
                        0,
                        file_id,
                    )
                    + name_b
                )
            elif info_class == 0x02:  # FileFullDirectoryInformation (68 + name)
                rec = bytearray(
                    struct.pack(
                        "<IIQQQQQQIII",
                        0,
                        0,
                        ft,
                        ft,
                        ft,
                        ft,
                        size,
                        alloc,
                        attrs,
                        len(name_b),
                        0,
                    )
                    + name_b
                )
            elif info_class == 0x0C:  # FileNamesInformation (12 + name)
                rec = bytearray(struct.pack("<III", 0, 0, len(name_b)) + name_b)
            else:  # 0x01 FileDirectoryInformation (64 + name)
                rec = bytearray(
                    struct.pack(
                        "<IIQQQQQQII",
                        0,
                        0,
                        ft,
                        ft,
                        ft,
                        ft,
                        size,
                        alloc,
                        attrs,
                        len(name_b),
                    )
                    + name_b
                )

            pad = (8 - (len(rec) % 8)) % 8
            if chunks and total + len(rec) + pad > max_bytes:
                break
            chunks.append(rec)
            total += len(rec) + pad
            if single:
                break

        out = bytearray()
        for idx, rec in enumerate(chunks):
            if idx < len(chunks) - 1:
                pad = (8 - (len(rec) % 8)) % 8
                rec.extend(b"\x00" * pad)
                struct.pack_into("<I", rec, 0, len(rec))
            out.extend(rec)
        return bytes(out), len(chunks)

    def _build_query_info(
        self, h: OpenHandle, info_type: int, info_class: int
    ) -> Optional[bytes]:
        mtime = time.time()
        eof = 0
        if h.fs_path is not None and h.fs_path.exists():
            st = h.fs_path.stat()
            mtime = st.st_mtime
            eof = 0 if h.is_dir else st.st_size
        ft = unix_to_filetime(mtime)
        alloc = ((eof + 4095) // 4096) * 4096
        attrs = FILE_ATTRIBUTE_DIRECTORY if h.is_dir else FILE_ATTRIBUTE_ARCHIVE

        if info_type == 0x01:  # SMB2_0_INFO_FILE
            if info_class == 4:  # FileBasicInformation
                return struct.pack("<QQQQII", ft, ft, ft, ft, attrs, 0)
            if info_class == 5:  # FileStandardInformation
                return struct.pack("<QQIBBBx", alloc, eof, 1, int(h.delete_on_close), int(h.is_dir), 0)
            if info_class == 6:  # FileInternalInformation
                return struct.pack("<Q", h.handle_id)
            if info_class == 7:  # FileEaInformation
                return struct.pack("<I", 0)
            if info_class == 8:  # FileAccessInformation
                return struct.pack("<I", 0x001F01FF)
            if info_class == 14:  # FilePositionInformation
                return struct.pack("<Q", 0)
            if info_class == 35:  # FileAttributeTagInformation
                return struct.pack("<II", attrs, 0)
            if info_class == 34:  # FileNetworkOpenInformation
                return struct.pack("<QQQQQQII", ft, ft, ft, ft, alloc, eof, attrs, 0)
            if info_class == 18:  # FileAllInformation
                name_b = ("\\" + h.rel_path).encode("utf-16-le")
                return (
                    struct.pack("<QQQQII", ft, ft, ft, ft, attrs, 0)
                    + struct.pack("<QQIBBBx", alloc, eof, 1, int(h.delete_on_close), int(h.is_dir), 0)
                    + struct.pack("<Q", h.handle_id)
                    + struct.pack("<I", 0)
                    + struct.pack("<I", 0x001F01FF)
                    + struct.pack("<Q", 0)
                    + struct.pack("<I", 0)
                    + struct.pack("<I", 0)
                    + struct.pack("<I", len(name_b))
                    + name_b
                )
            return None

        if info_type == 0x02:  # SMB2_0_INFO_FILESYSTEM
            _, out_dir = self.settings_mgr.ensure_directories()
            du = shutil.disk_usage(out_dir)
            total_units = max(1, du.total // 4096)
            free_units = max(0, du.free // 4096)
            if info_class == 3:  # FileFsSizeInformation
                return struct.pack("<QQII", total_units, free_units, 8, 512)
            if info_class == 7:  # FileFsFullSizeInformation
                return struct.pack("<QQQII", total_units, free_units, free_units, 8, 512)
            if info_class == 1:  # FileFsVolumeInformation
                vol = "PixelPhotoNAS".encode("utf-16-le")
                return struct.pack("<QIIBB", ft, 0x504E4153, len(vol), 0, 0) + vol
            if info_class == 5:  # FileFsAttributeInformation
                fs_name = "NTFS".encode("utf-16-le")
                return struct.pack("<III", 0x00000003, 255, len(fs_name)) + fs_name
            if info_class == 4:  # FileFsDeviceInformation
                return struct.pack("<II", 0x00000007, 0)
        return None

    def _apply_set_info(
        self, h: OpenHandle, info_type: int, info_class: int, in_buf: bytes
    ) -> int:
        if info_type != 0x01 or h.fs_path is None:
            return STATUS_ACCESS_DENIED

        if info_class == 4 and len(in_buf) >= 36:  # FileBasicInformation (preserve mtime!)
            last_write_ft = struct.unpack_from("<Q", in_buf, 16)[0]
            if last_write_ft > 0 and h.fs_path.exists():
                ts = filetime_to_unix(last_write_ft)
                try:
                    os.utime(h.fs_path, (ts, ts))
                except OSError:
                    pass
            return STATUS_SUCCESS

        if info_class == 13 and len(in_buf) >= 1:  # FileDispositionInformation
            h.delete_on_close = bool(in_buf[0])
            return STATUS_SUCCESS

        if info_class == 20 and len(in_buf) >= 8:  # FileEndOfFileInformation
            eof = struct.unpack_from("<Q", in_buf, 0)[0]
            if not h.is_dir:
                mode = "r+b" if h.fs_path.exists() else "wb"
                with h.fs_path.open(mode) as fp:
                    fp.truncate(eof)
                h.was_written = True
            return STATUS_SUCCESS

        if info_class == 10 and len(in_buf) >= 20:  # FileRenameInformation
            replace_existing = bool(in_buf[0])
            fn_len = struct.unpack_from("<I", in_buf, 16)[0]
            new_rel = in_buf[20 : 20 + fn_len].decode("utf-16-le", errors="ignore").lstrip("\\")
            dest_fs, is_vroot = self._resolve_share_path(h.share_kind, new_rel)
            if dest_fs is None or is_vroot:
                return STATUS_OBJECT_NAME_INVALID
            if dest_fs.exists() and not replace_existing:
                return STATUS_OBJECT_NAME_COLLISION
            dest_fs.parent.mkdir(parents=True, exist_ok=True)
            os.replace(h.fs_path, dest_fs)
            h.fs_path = dest_fs
            h.rel_path = new_rel
            h.was_written = True
            return STATUS_SUCCESS

        return STATUS_SUCCESS
