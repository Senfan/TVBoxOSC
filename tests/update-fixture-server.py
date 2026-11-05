#!/usr/bin/env python3
"""Loopback-only APK fixture server; /slow/ downloads exercise TV progress/cancel."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import time


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        self.slow = self.path.startswith('/slow/')
        if self.slow:
            self.path = self.path[len('/slow'):]
        try:
            super().do_GET()
        except (BrokenPipeError, ConnectionResetError):
            pass  # A cancelled client is an expected acceptance-test outcome.

    def copyfile(self, source, output):
        if not getattr(self, 'slow', False):
            return super().copyfile(source, output)
        for block in iter(lambda: source.read(65536), b''):
            output.write(block)
            output.flush()
            time.sleep(0.10)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory', required=True)
parser.add_argument('--port', type=int, default=19979)
args = parser.parse_args()
server = ThreadingHTTPServer(('127.0.0.1', args.port), partial(Handler, directory=args.directory))
print(f'TVBox test fixture on 127.0.0.1:{args.port}', flush=True)
server.serve_forever()
