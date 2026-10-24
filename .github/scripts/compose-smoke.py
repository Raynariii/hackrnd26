#!/usr/bin/env python3
"""Дымовая проверка собранного стека (docker compose up): тот же путь, что пройдёт жюри по README.

Через web (nginx, :3000) → api → runner: загрузить corpus/00-hello → обзорный граф → запуск на входе из корпуса →
дифф-проверка версии с собой. Любой сбой — ненулевой код выхода.
"""
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:3000/api"
EXAMPLE = pathlib.Path("corpus/00-hello")


def call(method, path, body=None, timeout=180):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read() or b"{}")


def wait_ready(deadline_s=180):
    start = time.time()
    while time.time() - start < deadline_s:
        try:
            call("GET", "/projects/smoke-not-found", timeout=5)
        except urllib.error.HTTPError as e:
            if e.code == 404:  # api отвечает через nginx
                return
        except Exception:
            pass
        time.sleep(3)
    sys.exit("api не поднялся за %d с" % deadline_s)


def main():
    wait_ready()
    files = [{"path": p.name, "content": p.read_text(encoding="utf-8")} for p in sorted(EXAMPLE.glob("*.java"))]
    stdin = sorted((EXAMPLE / "inputs").glob("*.txt"))[0].read_text(encoding="utf-8")

    project = call("POST", "/projects", {"name": "smoke", "files": files})
    pid = project["id"]
    graph = call("GET", f"/projects/{pid}/graph")
    assert any(n["kind"] == "method" for n in graph["nodes"]), "в обзоре нет методов"

    run = call("POST", f"/projects/{pid}/run", {"stdin": stdin})
    assert run["compile"]["ok"], f"не скомпилировалось: {run['compile']}"
    assert run["exitCode"] == 0 and run["stdout"], f"запуск: {run}"

    report = call("POST", f"/projects/{pid}/verify", {})
    assert report["compiled"] and report["behaviorPreserved"], f"verify: {report}"
    print(f"OK: граф {len(graph['nodes'])} узлов, запуск {run['timeMs']} мс, "
          f"verify {report['passed']}/{report['total']}")


if __name__ == "__main__":
    main()
