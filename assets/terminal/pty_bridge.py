#!/usr/bin/env python3
"""Real-terminal bridge for The Last Fuck.

Runs a login shell on a real pseudo-terminal (pty) inside the app's Alpine Linux and relays it over
stdin/stdout so the Android app can show it in a terminal emulator.

stdout: raw bytes from the terminal, untouched.
stdin:  framed messages so keystrokes and window size never mix:
        1 byte type + 4 byte big-endian length + payload
        b'D' = bytes typed into the terminal
        b'R' = "cols,rows" window size
The shell, Ctrl-C, job control, vi, top, python REPL etc. all work because the kernel tty does the work.
"""
import fcntl
import os
import pty
import select
import signal
import struct
import sys
import termios


def set_size(fd, cols, rows):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))


def main():
    cols = int(os.environ.get("TERM_COLS", "80"))
    rows = int(os.environ.get("TERM_ROWS", "24"))
    shell = os.environ.get("TERM_SHELL", "/bin/sh")
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(os.environ.get("TERM_CWD", "/workspace"))
        os.execvp(shell, [shell, "-l"])
    set_size(fd, cols, rows)

    out = sys.stdout.buffer.raw if hasattr(sys.stdout.buffer, "raw") else sys.stdout.buffer
    stdin = sys.stdin.buffer.raw if hasattr(sys.stdin.buffer, "raw") else sys.stdin.buffer
    pending = b""
    stdin_open = True
    try:
        while True:
            watch = [fd] + ([stdin] if stdin_open else [])
            ready, _, _ = select.select(watch, [], [], 1.0)
            if fd in ready:
                try:
                    data = os.read(fd, 65536)
                except OSError:
                    data = b""
                if not data:
                    break  # shell exited
                out.write(data)
                out.flush()
            if stdin_open and stdin in ready:
                chunk = os.read(stdin.fileno(), 65536)
                if not chunk:
                    stdin_open = False  # app went away: hang up the shell
                    os.kill(pid, signal.SIGHUP)
                    continue
                pending += chunk
                while len(pending) >= 5:
                    kind = pending[:1]
                    size = struct.unpack(">I", pending[1:5])[0]
                    if len(pending) < 5 + size:
                        break
                    payload, pending = pending[5:5 + size], pending[5 + size:]
                    if kind == b"D":
                        os.write(fd, payload)
                    elif kind == b"R":
                        c, r = payload.decode("ascii", "replace").split(",")
                        set_size(fd, int(c), int(r))  # kernel sends SIGWINCH to the foreground job
    finally:
        try:
            _, status = os.waitpid(pid, 0)
            code = os.waitstatus_to_exitcode(status) if hasattr(os, "waitstatus_to_exitcode") else status >> 8
        except ChildProcessError:
            code = 0
        out.write(("\r\n[shell exited %d]\r\n" % code).encode())
        out.flush()


if __name__ == "__main__":
    main()
