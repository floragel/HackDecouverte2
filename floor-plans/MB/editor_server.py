"""Local server for the MB graph editor.

Serves this folder on http://127.0.0.1:8765 and lets the editor save:
  POST /api/graph   -> MB-graph.json        (previous version kept as MB-graph.backup.json)
  POST /api/export  -> export/floorplan-MB-<floor>.json  (format of the Android app)

Run:  python editor_server.py      then open http://127.0.0.1:8765/editor.html
Only listens on this computer (127.0.0.1). Standard library only.
"""
import json, os, re, shutil, sys, webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 8765
GRAPH = os.path.join(HERE, "MB-graph.json")
# Map edits (doors, extra walls, passable furniture) read by the app: app/src/main/assets/maps/MB-<n>-overlay.json
OVERLAYS = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "maps"))
OVERLAY_PATH = re.compile(r"^/api/overlay/([1-7])$")


def overlay_file(floor):
    return os.path.join(OVERLAYS, f"MB-{floor}-overlay.json")


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=HERE, **k)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        m = OVERLAY_PATH.match(self.path)
        if not m:
            return super().do_GET()
        path = overlay_file(m.group(1))
        if not os.path.exists(path):
            return self._reply(200, {"floorId": f"MB-{m.group(1)}"})
        with open(path, encoding="utf-8") as f:
            return self._reply(200, json.load(f))

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(n).decode("utf-8"))
            m = OVERLAY_PATH.match(self.path)
            if m:
                if not isinstance(data, dict) or data.get("floorId") != f"MB-{m.group(1)}":
                    return self._reply(400, {"error": "not an overlay for this floor"})
                os.makedirs(OVERLAYS, exist_ok=True)
                with open(overlay_file(m.group(1)), "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=1)
                return self._reply(200, {"saved": f"app/src/main/assets/maps/MB-{m.group(1)}-overlay.json"})
            if self.path == "/api/graph":
                if not isinstance(data, dict) or "nodes" not in data or "edges" not in data:
                    return self._reply(400, {"error": "not a graph"})
                if os.path.exists(GRAPH):
                    shutil.copyfile(GRAPH, os.path.join(HERE, "MB-graph.backup.json"))
                with open(GRAPH, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=1)
                return self._reply(200, {"saved": "MB-graph.json", "nodes": len(data["nodes"]), "edges": len(data["edges"])})
            if self.path == "/api/export":
                out = os.path.join(HERE, "export")
                os.makedirs(out, exist_ok=True)
                written = []
                for plan in data:
                    fid = str(plan.get("floor_id", ""))
                    if not re.fullmatch(r"MB-[0-9S]{1,3}", fid):
                        continue
                    name = f"floorplan-{fid}.json"
                    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
                        json.dump(plan, f, ensure_ascii=False, indent=1)
                    written.append("export/" + name)
                return self._reply(200, {"written": written})
            return self._reply(404, {"error": "unknown endpoint"})
        except Exception as e:  # report instead of crashing the server
            return self._reply(500, {"error": str(e)})

    def log_message(self, fmt, *args):
        if "POST" in (args[0] if args else ""):
            sys.stderr.write("%s\n" % (fmt % args))


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    url = f"http://127.0.0.1:{PORT}/editor.html"
    print("MB graph editor running at", url, "(Ctrl+C to stop)")
    if "--no-browser" not in sys.argv:
        webbrowser.open(url)
    srv.serve_forever()
