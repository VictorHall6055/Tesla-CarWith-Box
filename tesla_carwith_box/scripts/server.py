#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tesla CarWith Box - Asynchronous HTTP / MJPEG Streamer & Touch Gateway
Multi-threaded, non-blocking input dispatcher with persistent settings.
"""

import os
import sys
import time
import subprocess
import threading
from urllib.parse import urlparse, parse_qs
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

MODDIR = "/data/adb/modules/tesla_carwith_box"
WEBROOT = os.path.join(MODDIR, "webroot")
CTRL_SCRIPT = os.path.join(MODDIR, "scripts", "tesla_control.sh")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080

class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

def run_cmd(args):
    try:
        res = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
        return res.stdout.strip()
    except Exception:
        return ""

def async_input(args):
    """Fire-and-forget input command to avoid blocking HTTP worker threads"""
    try:
        subprocess.Popen(["/system/bin/input"] + args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

def get_disp_id():
    try:
        res = subprocess.run(["/system/bin/dumpsys", "display"],
                             capture_output=True, text=True, timeout=1.5)
        lines = res.stdout.splitlines()
        for i, line in enumerate(lines):
            if "Display " in line and ":" in line:
                for j in range(i, min(i + 5, len(lines))):
                    if "com.miui.carlink(" in lines[j]:
                        return line.strip().split()[1].replace(":", "")
    except Exception:
        pass
    return "12"

class TeslaStreamHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)

        if path in ("/", "/tesla", "/tesla.html"):
            self.serve_file(os.path.join(WEBROOT, "tesla.html"), "text/html; charset=utf-8")
        elif path in ("/config", "/index.html"):
            self.serve_file(os.path.join(WEBROOT, "index.html"), "text/html; charset=utf-8")
        elif path == "/api/ping":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b"pong")
        elif path == "/api/status":
            out = run_cmd(["/system/bin/sh", CTRL_SCRIPT, "status"])
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(out.encode("utf-8") if out else b'{}')
        elif path == "/api/audio":
            mode = params.get("mode", ["native"])[0]
            out = run_cmd(["/system/bin/sh", CTRL_SCRIPT, "set_audio_mode", mode])
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(out.encode("utf-8") if out else b'{"status":"ok"}')
        elif path == "/api/unblock":
            out = run_cmd(["/system/bin/sh", CTRL_SCRIPT, "unblock_hotspot"])
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(out.encode("utf-8") if out else b'{"status":"ok"}')
        elif path == "/api/vehicle_name":
            name = params.get("name", ["Xiaomi SU7-Tesla"])[0]
            out = run_cmd(["/system/bin/sh", CTRL_SCRIPT, "set_vehicle_name", name])
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(out.encode("utf-8") if out else b'{"status":"ok"}')
        elif path == "/api/start_carlife":
            out = run_cmd(["/system/bin/sh", CTRL_SCRIPT, "start_carlife"])
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(out.encode("utf-8") if out else b'{"status":"ok"}')
        elif path == "/api/touch":
            action = params.get("action", ["tap"])[0]
            disp = get_disp_id()

            if action == "tap":
                x = params.get("x", ["0"])[0]
                y = params.get("y", ["0"])[0]
                async_input(["-d", disp, "tap", x, y])
            elif action == "down":
                x = params.get("x", ["0"])[0]
                y = params.get("y", ["0"])[0]
                async_input(["-d", disp, "motionevent", "DOWN", x, y])
            elif action == "move":
                x = params.get("x", ["0"])[0]
                y = params.get("y", ["0"])[0]
                async_input(["-d", disp, "motionevent", "MOVE", x, y])
            elif action == "up":
                x = params.get("x", ["0"])[0]
                y = params.get("y", ["0"])[0]
                async_input(["-d", disp, "motionevent", "UP", x, y])
            elif action == "swipe":
                x1 = params.get("x1", ["0"])[0]
                y1 = params.get("y1", ["0"])[0]
                x2 = params.get("x2", ["0"])[0]
                y2 = params.get("y2", ["0"])[0]
                dur = params.get("dur", ["250"])[0]
                async_input(["-d", disp, "swipe", x1, y1, x2, y2, dur])
            elif action == "zoom":
                dir_ = params.get("dir", ["in"])[0]
                x = params.get("x", ["640"])[0]
                y = params.get("y", ["360"])[0]
                if dir_ == "in":
                    async_input(["-d", disp, "tap", x, y])
                    time.sleep(0.06)
                    async_input(["-d", disp, "tap", x, y])
                else:
                    x_int = int(x)
                    y_int = int(y)
                    async_input(["-d", disp, "swipe", str(x_int - 100), str(y_int), str(x_int - 20), str(y_int), "200"])

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
        elif path == "/api/exec":
            cmd = params.get("cmd", [""])[0]
            out = run_cmd(["/system/bin/sh", "-c", cmd]) if cmd else ""
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(out.encode("utf-8"))
        elif path == "/api/stream":
            self.serve_mjpeg_stream()
        else:
            safe_filename = os.path.basename(path)
            full_path = os.path.join(WEBROOT, safe_filename)
            if os.path.exists(full_path):
                ext = os.path.splitext(safe_filename)[1].lower()
                mime = "text/html"
                if ext == ".js": mime = "application/javascript"
                elif ext == ".css": mime = "text/css"
                elif ext == ".png": mime = "image/png"
                elif ext == ".svg": mime = "image/svg+xml"
                self.serve_file(full_path, mime)
            else:
                self.send_response(404)
                self.end_headers()

    def serve_file(self, filepath, content_type):
        if os.path.exists(filepath):
            with open(filepath, "rb") as f:
                content = f.read()
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(content)
        else:
            self.send_response(404)
            self.end_headers()

    def serve_mjpeg_stream(self):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-cache, private")
        self.send_header("Pragma", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

        last_check = time.time()
        disp_id = get_disp_id()

        try:
            while True:
                now = time.time()
                if now - last_check > 3.0:
                    disp_id = get_disp_id()
                    last_check = now

                if disp_id != "0":
                    cmd = ["/system/bin/screencap", "-d", disp_id, "-p"]
                else:
                    cmd = ["/system/bin/screencap", "-p"]

                p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                img_data, _ = p.communicate()
                if img_data:
                    idx = img_data.find(b"\x89PNG")
                    if idx != -1:
                        clean_data = img_data[idx:]
                        self.wfile.write(b"--frame\r\n")
                        self.wfile.write(b"Content-Type: image/png\r\n")
                        self.wfile.write(f"Content-Length: {len(clean_data)}\r\n\r\n".encode("utf-8"))
                        self.wfile.write(clean_data)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()
                time.sleep(0.03)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

def main():
    # 启动时确保放行热点端口
    run_cmd(["/system/bin/sh", CTRL_SCRIPT, "unblock_hotspot"])
    server = ThreadingHTTPServer(("0.0.0.0", PORT), TeslaStreamHandler)
    print(f"Tesla Streamer running asynchronously on 0.0.0.0:{PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()

if __name__ == "__main__":
    main()
