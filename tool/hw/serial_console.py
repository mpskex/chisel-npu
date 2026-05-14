#!/usr/bin/env python3
"""
serial_console.py — Control the FPGA host via serial console (out-of-band).

Connects to the FPGA host's serial console via a USB-UART adapter (typically
/dev/ttyUSB0, Prolific PL2303 or FTDI) at 115200 8N1. Useful when SSH is
unavailable (e.g. after loading a bad bitstream that prevents network boot).

Assumes a no-password login user (sends username only at the login prompt).

Default port: /dev/ttyUSB0  — override via SERIAL_PORT env var or --port
Default user: (current OS user) — override via SERIAL_USER env var or --user
Login assumes no-password user (sends username only at the login prompt).
"""
import getpass
import re, sys, time, argparse, os
import serial

PORT  = os.environ.get('SERIAL_PORT', '/dev/ttyUSB0')
BAUD  = 115200
# Default to the current OS user; override with SERIAL_USER env var
USER  = os.environ.get('SERIAL_USER', getpass.getuser())

_ANSI   = re.compile(r'\x1b\[[^a-zA-Z]*[a-zA-Z]|\x1b\[\?[0-9]+[hl]')
_PROMPT = re.compile(rb'[\$#] ')
_LOGIN  = re.compile(rb'login:\s*$', re.MULTILINE)
_PASS   = re.compile(rb'[Pp]assword:\s*$')


def _clean(text: str) -> str:
    """Strip ANSI/VT100 escape sequences and carriage returns."""
    return _ANSI.sub('', text).replace('\r', '')


class SerialConsole:
    def __init__(self, port=PORT, baud=BAUD, user=USER, debug=False):
        self.port  = port
        self.baud  = baud
        self.user  = user
        self.debug = debug
        self._s    = None

    def __enter__(self):
        self.open(); return self

    def __exit__(self, *_):
        self.close()

    def open(self):
        self._s = serial.Serial(self.port, self.baud, timeout=0.3)

    def close(self):
        if self._s and self._s.is_open:
            self._s.close()

    def _write(self, data: bytes):
        if self.debug:
            print(f'>> {data!r}', file=sys.stderr)
        self._s.write(data)

    def _read_until(self, patterns, timeout=60) -> bytes:
        buf = b''
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                chunk = self._s.read(512)
            except serial.SerialException:
                time.sleep(0.2)
                continue
            if chunk:
                buf += chunk
                if self.debug:
                    sys.stderr.buffer.write(chunk); sys.stderr.buffer.flush()
                for p in patterns:
                    if p.search(buf):
                        return buf
            else:
                time.sleep(0.05)
        return buf

    def login(self, timeout=30) -> bool:
        """Detect login: or shell prompt, authenticate if needed."""
        self._write(b'\r\n')
        buf = self._read_until([_PROMPT, _LOGIN], timeout=timeout)
        if _PROMPT.search(buf):
            return True
        if _LOGIN.search(buf):
            self._write(self.user.encode() + b'\r\n')
            buf2 = self._read_until([_PROMPT, _PASS], timeout=10)
            if _PASS.search(buf2):
                self._write(b'\r\n')  # empty password
                self._read_until([_PROMPT], timeout=10)
            return True
        # Last resort nudge
        self._write(b'\r\n')
        buf = self._read_until([_PROMPT], timeout=10)
        return bool(_PROMPT.search(buf))

    def run(self, cmd: str, timeout=60) -> str:
        """Send cmd, wait for prompt, return cleaned output."""
        self._write(cmd.encode() + b'\r\n')
        buf = self._read_until([_PROMPT], timeout=timeout)
        text = _clean(buf.decode(errors='replace'))
        lines = text.splitlines()
        # Drop echoed command line and prompt lines
        result = []
        first = True
        for line in lines:
            s = line.strip()
            if first and (not s or cmd.split()[-1] in s or cmd.split()[0] in s):
                first = False
                continue
            if re.search(r'@[\w.-]+:.*[\$#]\s*$', s):
                continue
            result.append(line)
        return '\n'.join(result).strip()

    def pcie_present(self, vid_did='10ee:7028') -> bool:
        out = self.run(f'sudo lspci -nn 2>/dev/null | grep "{vid_did}"', timeout=15)
        return vid_did in out

    def reboot(self, wait=180) -> bool:
        """Reboot host, reconnect, auto-login. Returns True when shell is ready."""
        print('[serial] Rebooting...', file=sys.stderr)
        self._write(b'sudo /sbin/reboot\r\n')
        time.sleep(3)
        # Drain
        for _ in range(5):
            try: self._s.read(512)
            except: pass
            time.sleep(0.5)

        print(f'[serial] Waiting for host ({wait}s max)...', file=sys.stderr)
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline:
            try:
                chunk = self._s.read(128)
                if chunk:
                    break
            except serial.SerialException:
                try: self._s.close(); time.sleep(1); self._s.open()
                except: pass
            time.sleep(1)

        buf = self._read_until([_LOGIN, _PROMPT], timeout=120)
        if _LOGIN.search(buf):
            self._write(self.user.encode() + b'\r\n')
            self._read_until([_PROMPT], timeout=30)
        elif not _PROMPT.search(buf):
            self._write(b'\r\n')
            self._read_until([_PROMPT], timeout=20)
        print('[serial] Host ready.', file=sys.stderr)
        return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', nargs='+')
    ap.add_argument('--reboot', action='store_true')
    ap.add_argument('--port', default=PORT)
    ap.add_argument('--baud', type=int, default=BAUD)
    ap.add_argument('--debug', action='store_true')
    args = ap.parse_args()
    cmd = ' '.join(args.cmd)
    with SerialConsole(port=args.port, baud=args.baud, debug=args.debug) as sc:
        if args.reboot:
            sc.reboot()
        else:
            sc.login()
        out = sc.run(cmd)
        print(out)

if __name__ == '__main__':
    main()
