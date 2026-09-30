"""本地 webhook 接收器：验证 Grafana 告警送达。

用法: python3 docker/webhook-receiver.py   （监听 9977，POST 落盘到 /tmp/grafana-webhook.log）
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = "/tmp/grafana-webhook.log"

class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(body + "\n---\n")
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"received":true}')

    def log_message(self, *args):
        pass  # 静默 access log，只留正文

if __name__ == "__main__":
    print(f"webhook receiver on :9977 -> {LOG}")
    HTTPServer(("0.0.0.0", 9977), Receiver).serve_forever()
