#!/usr/bin/env python3
"""Import the sibling 知屿 SQLite notes into the signed-in user's IntelliDoc private space."""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / "batter-coder" / "notion-style-personal-site-main" / "data" / "knowledge.db"
STATE = ROOT / ".runtime" / "zhiyu-sync.json"
CHUNK = 5 * 1024 * 1024

def read_documents(path):
    if not path.is_file():
        raise ValueError(f"找不到源数据库：{path}")
    db = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    try:
        db.execute("BEGIN")
        return [dict(row) for row in db.execute(
            "SELECT id,parent_id,title,tags,content,plain_text,is_deleted,updated_at "
            "FROM documents ORDER BY created_at,id")]
    finally:
        db.close()

def inline(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(map(inline, value))
    if not isinstance(value, dict):
        return ""
    value_text = str(value.get("text") or "")
    if value.get("type") == "link" and value.get("href"):
        label = inline(value.get("content")) or value_text or value["href"]
        return f"[{label}]({value['href']})"
    return value_text + inline(value.get("content"))

def block_markdown(block, depth=0):
    kind = block.get("type")
    props = block.get("props") or {}
    content = block.get("content")
    value = inline(content).rstrip("\n") if kind == "codeBlock" else inline(content).strip()
    indent = "  " * depth
    if kind == "heading":
        line = "#" * max(1, min(6, int(props.get("level") or 1))) + " " + value
    elif kind == "bulletListItem":
        line = f"{indent}- {value}"
    elif kind == "numberedListItem":
        line = f"{indent}1. {value}"
    elif kind == "checkListItem":
        line = f"{indent}- [{'x' if props.get('checked') else ' '}] {value}"
    elif kind == "codeBlock":
        fence = chr(96) * max(3, max((len(match.group()) for match in re.finditer(chr(96) + "+", value)), default=0) + 1)
        line = f"{fence}{str(props.get('language') or '').replace(chr(96), '')}\n{value}\n{fence}"
    elif kind == "quote":
        line = "\n".join("> " + part for part in value.splitlines())
    elif kind == "divider":
        line = "---"
    elif kind == "table":
        rows = (content or {}).get("rows") or []
        cells = [[inline(cell.get("content")).replace("\n", " ").replace("|", r"\|")
                  for cell in row.get("cells", [])] for row in rows]
        width = max(map(len, cells), default=0)
        normalized = [row + [""] * (width - len(row)) for row in cells]
        if normalized:
            normalized.insert(1, ["---"] * width)
        line = "\n".join("| " + " | ".join(row) + " |" for row in normalized)
    elif kind == "image":
        url = str(props.get("url") or "")
        caption = str(props.get("caption") or props.get("name") or "图片")
        if url.startswith("/uploads/"):
            url = "http://localhost:3000" + url
        line = f"![{caption}]({url})" if url else f"[图片：{caption}]"
    else:
        line = value
    children = [block_markdown(child, depth + 1) for child in block.get("children") or []]
    return "\n\n".join(part for part in [line, *children] if part)

def ancestry(row, by_id):
    parts = [row.get("title") or "未命名文档"]
    visited = {row["id"]}
    parent = row.get("parent_id")
    while parent and parent in by_id and parent not in visited:
        ancestor = by_id[parent]
        parts.append(ancestor.get("title") or "未命名文档")
        visited.add(parent)
        parent = ancestor.get("parent_id")
    return " / ".join(reversed(parts))

def render(row, by_id):
    try:
        parsed_tags = json.loads(row.get("tags") or "[]")
        tags = [tag for tag in parsed_tags if isinstance(tag, str)] if isinstance(parsed_tags, list) else []
    except json.JSONDecodeError:
        tags = []
    try:
        blocks = json.loads(row["content"])
        if not isinstance(blocks, list):
            raise ValueError("正文不是块数组")
        body = "\n\n".join(filter(None, (block_markdown(item) for item in blocks if isinstance(item, dict))))
    except (json.JSONDecodeError, ValueError, TypeError):
        body = row.get("plain_text") or ""
    lines = [
        "# " + (row.get("title") or "未命名文档"),
        "来源：知屿个人知识库",
        "来源文档 ID：" + row["id"],
        "文档路径：" + ancestry(row, by_id),
        "标签：" + ("、".join(tags) if tags else "无"),
        "源文档更新时间：" + (row.get("updated_at") or "未知"),
    ]
    return ("\n".join(lines) + "\n\n" + body + "\n").encode("utf-8")

def filename(row):
    title = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "-", row.get("title") or "未命名文档")
    title = title.strip(" .")[:70] or "未命名文档"
    identifier = re.sub(r"[^a-zA-Z0-9_-]", "-", row["id"])
    return f"zhiyu-{title}-{identifier}.md"

def load_state(path, user_id, source):
    if not path.exists():
        return {"version": 1, "userId": user_id, "source": str(source.resolve()), "documents": {}}
    state = json.loads(path.read_text(encoding="utf-8"))
    if state.get("version") != 1 or state.get("userId") != user_id or state.get("source") != str(source.resolve()):
        raise ValueError("同步状态属于其他用户或数据库，请指定对应的 --state")
    return state

def save_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)

class ApiClient:
    def __init__(self, base, token=None):
        self.base = base.rstrip("/")
        self.token = token

    def request(self, method, path, payload=None, content_type="application/json"):
        headers = {"Authorization": "Bearer " + self.token} if self.token else {}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode() if content_type == "application/json" else payload
            headers["Content-Type"] = content_type
        request = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=120) as response:
                raw = response.read()
        except HTTPError as error:
            message = error.read().decode("utf-8", errors="replace")[:400]
            raise RuntimeError(f"{method} {path} 返回 HTTP {error.code}：{message}") from error
        except URLError as error:
            raise RuntimeError(f"无法连接 IntelliDoc：{error.reason}") from error
        result = json.loads(raw) if raw else {}
        if isinstance(result, dict) and result.get("code", 200) != 200:
            raise RuntimeError(f"{method} {path} 失败：{result.get('message')}")
        return result.get("data", result) if isinstance(result, dict) else result

    def upload(self, name, content, tag):
        digest = hashlib.md5(content).hexdigest()
        total_chunks = (len(content) + CHUNK - 1) // CHUNK
        for index, start in enumerate(range(0, len(content), CHUNK)):
            piece = content[start:start + CHUNK]
            boundary = "zhiyu-" + os.urandom(12).hex()
            fields = {"fileMd5": digest, "chunkIndex": index, "totalSize": len(content),
                      "fileName": name, "totalChunks": total_chunks, "orgTag": tag, "isPublic": "false"}
            parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
                     for key, value in fields.items()]
            parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="chunk.md"\r\nContent-Type: text/markdown\r\n\r\n'.encode(),
                          piece, b"\r\n", f"--{boundary}--\r\n".encode()])
            self.request("POST", "/upload/chunk", b"".join(parts), f"multipart/form-data; boundary={boundary}")
        self.request("POST", "/upload/merge", {"fileMd5": digest, "fileName": name})
        return digest

def own_private_tag(client):
    user = client.request("GET", "/users/me")
    tag = "PRIVATE_" + user["username"]
    assigned = client.request("GET", "/users/org-tags").get("orgTags") or []
    if tag not in assigned:
        raise RuntimeError(f"当前账号缺少自己的私人组织标签 {tag}，已停止导入")
    return str(user["id"]), tag

def checked(files, digest, tag, name=None):
    item = files.get(digest)
    if item and (item.get("orgTag") != tag or item.get("isPublic") is not False
                 or (name and item.get("fileName") != name)):
        raise RuntimeError(f"文件 {digest} 的所有者/权限/文件名不符合导入记录，已停止")
    return item

def sync(client, source, state_path, limit=None):
    rows = read_documents(source)
    active = [row for row in rows if not row["is_deleted"]]
    by_id = {row["id"]: row for row in rows}
    if limit:
        active = active[:limit]
    user_id, tag = own_private_tag(client)
    state = load_state(state_path, user_id, source)
    files = {item["fileMd5"]: item for item in client.request("GET", "/documents/uploads")}
    stats = {"uploaded": 0, "unchanged": 0, "processing": 0, "failed": 0, "retired": 0}
    for row in active:
        name = filename(row)
        digest = hashlib.md5(render(row, by_id)).hexdigest()
        entry = state["documents"].get(row["id"], {})
        pending_digest = entry.get("pendingMd5")
        if pending_digest:
            pending = checked(files, pending_digest, tag)
            if pending and pending.get("vectorizationStatus") == "COMPLETED":
                previous = entry.get("previousMd5")
                if previous and previous != pending_digest:
                    old = checked(files, previous, tag)
                    if old:
                        client.request("DELETE", "/documents/" + previous)
                        files.pop(previous, None)
                        stats["retired"] += 1
                entry = {"md5": pending_digest}
                state["documents"][row["id"]] = entry
                save_state(state_path, state)
            elif pending and pending.get("vectorizationStatus") == "FAILED":
                stats["failed"] += 1
                print(f"向量化失败，保留旧版本：{name}")
                continue
            elif pending:
                stats["processing"] += 1
                continue
            else:
                entry.pop("pendingMd5", None)
                entry.pop("previousMd5", None)
                save_state(state_path, state)
        current = checked(files, entry.get("md5"), tag) if entry.get("md5") else None
        if current and current.get("vectorizationStatus") == "COMPLETED" and entry["md5"] == digest:
            stats["unchanged"] += 1
            continue
        existing = checked(files, digest, tag, name)
        if not existing or existing.get("status") != 1:
            client.upload(name, render(row, by_id), tag)
            stats["uploaded"] += 1
            print(f"已提交：{name}")
        files = {item["fileMd5"]: item for item in client.request("GET", "/documents/uploads")}
        if not checked(files, digest, tag, name):
            raise RuntimeError(f"上传后找不到文件 {digest}，请检查服务状态")
        state["documents"][row["id"]] = {"md5": entry.get("md5"), "pendingMd5": digest,
                                      "previousMd5": entry.get("md5")}
        save_state(state_path, state)
    inactive = [row for row in rows if row["is_deleted"] and row["id"] in state["documents"]]
    missing = [key for key in state["documents"] if key not in by_id]
    if inactive or missing:
        print(f"源库已有 {len(inactive) + len(missing)} 篇已同步文档被删除或移除；IntelliDoc 副本未自动删除。")
    return stats

def main(argv=None):
    parser = argparse.ArgumentParser(description="同步同级知屿文档到 IntelliDoc 私人空间")
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--state", type=Path, default=STATE)
    parser.add_argument("--api-base", default="http://localhost:8081/api/v1")
    parser.add_argument("--username", help="无 INTELLIDOC_TOKEN 时用于交互登录")
    parser.add_argument("--limit", type=int, help="先同步前 N 篇活跃文档")
    parser.add_argument("--dry-run", action="store_true", help="只读取转换源文档，不访问 IntelliDoc")
    args = parser.parse_args(argv)
    if args.limit is not None and args.limit < 1:
        parser.error("--limit 必须大于 0")
    try:
        rows = read_documents(args.source)
        active = [row for row in rows if not row["is_deleted"]]
        selected = active[:args.limit] if args.limit else active
        if args.dry_run:
            by_id = {row["id"]: row for row in rows}
            size = sum(len(render(row, by_id)) for row in selected)
            print(f"源库 {len(rows)} 篇，活跃 {len(active)} 篇，本次转换 {len(selected)} 篇，合计 {size} 字节。")
            print("只读预检通过；未调用 IntelliDoc，也未写入状态。")
            return 0
        client = ApiClient(args.api_base, os.environ.get("INTELLIDOC_TOKEN"))
        if not client.token:
            if not args.username:
                parser.error("请设置 INTELLIDOC_TOKEN，或传 --username 交互输入密码")
            password = getpass.getpass("IntelliDoc 密码：")
            client.token = client.request("POST", "/users/login",
                                          {"username": args.username, "password": password})["token"]
        stats = sync(client, args.source, args.state, args.limit)
        print("同步结果：" + "，".join(f"{key}={value}" for key, value in stats.items()))
        return 1 if stats["failed"] else 0
    except (ValueError, sqlite3.Error, RuntimeError, KeyError) as error:
        print(f"同步失败：{error}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
