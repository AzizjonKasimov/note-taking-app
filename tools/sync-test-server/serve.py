"""Serves bare git repos over smart HTTP, so the app's sync can be tried on an emulator.

The app talks to GitHub over HTTPS with a token as the password. This stands in for GitHub on the
PC: git's own `git http-backend` answers clones, fetches, and pushes, and every request must carry
HTTP basic auth whose password is the test token you choose. It listens on 127.0.0.1 only; an
Android emulator reaches it at http://10.0.2.2:<port>/<name>.git.

    python -I tools/sync-test-server/serve.py --root <folder of bare repos> --token <test token>

Make a bare repo to serve with `git clone --bare <repo> <root>/<name>.git`. Nothing here is a real
secret: use a made-up token, never a GitHub one.
"""

import argparse
import base64
import os
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def find_backend() -> str:
    exec_path = subprocess.check_output(["git", "--exec-path"], text=True).strip()
    for name in ("git-http-backend", "git-http-backend.exe"):
        path = os.path.join(exec_path, name)
        if os.path.exists(path):
            return path
    sys.exit("git-http-backend was not found next to git")


class Handler(BaseHTTPRequestHandler):
    root = ""
    token = ""
    backend = ""

    def do_GET(self) -> None:
        self.serve()

    def do_POST(self) -> None:
        self.serve()

    def authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            _, _, password = base64.b64decode(header[6:]).decode("utf-8").partition(":")
        except ValueError:
            return False
        return password == self.token

    def read_body(self) -> bytes:
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            parts = []
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    break
                parts.append(self.rfile.read(size))
                self.rfile.readline()
            return b"".join(parts)
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def serve(self) -> None:
        if not self.authorized():
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="sync test"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        path, _, query = self.path.partition("?")
        body = self.read_body()
        env = {
            "GIT_PROJECT_ROOT": self.root,
            "GIT_HTTP_EXPORT_ALL": "1",
            "PATH_INFO": path,
            "QUERY_STRING": query,
            "REQUEST_METHOD": self.command,
            "CONTENT_TYPE": self.headers.get("Content-Type", ""),
            "CONTENT_LENGTH": str(len(body)),
            "HTTP_CONTENT_ENCODING": self.headers.get("Content-Encoding", ""),
            "GIT_PROTOCOL": self.headers.get("Git-Protocol", ""),
            # A user name turns on pushes (receive-pack) in http-backend.
            "REMOTE_USER": "tester",
            "REMOTE_ADDR": "127.0.0.1",
            "PATH": os.environ.get("PATH", ""),
            "SYSTEMROOT": os.environ.get("SYSTEMROOT", ""),
        }
        result = subprocess.run([self.backend], input=body, capture_output=True, env=env)
        head, sep, payload = result.stdout.partition(b"\r\n\r\n")
        if not sep:
            head, sep, payload = result.stdout.partition(b"\n\n")
        status = 200
        headers = []
        for line in head.decode("latin-1").splitlines():
            key, _, value = line.partition(":")
            if key.lower() == "status":
                status = int(value.strip().split()[0])
            elif key:
                headers.append((key.strip(), value.strip()))
        self.send_response(status)
        for key, value in headers:
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        if result.stderr:
            sys.stderr.write(result.stderr.decode("utf-8", "replace"))

    def log_message(self, format: str, *args) -> None:
        sys.stderr.write("%s %s\n" % (self.command, format % args))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", required=True, help="folder holding the bare repos")
    parser.add_argument("--token", required=True, help="the made-up token clients must send")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    Handler.root = os.path.abspath(args.root)
    Handler.token = args.token
    Handler.backend = find_backend()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Serving {Handler.root} on http://127.0.0.1:{args.port} (emulator: http://10.0.2.2:{args.port})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
