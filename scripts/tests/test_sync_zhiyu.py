import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sync_zhiyu as sync


def source_database(path, rows):
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE documents (id TEXT, parent_id TEXT, title TEXT, tags TEXT, "
               "content TEXT, plain_text TEXT, is_deleted INTEGER, updated_at TEXT, created_at TEXT)")
    for row in rows:
        db.execute("INSERT INTO documents VALUES (?,?,?,?,?,?,?,?,?)",
                   (row["id"], row.get("parent_id"), row["title"], json.dumps(row.get("tags", [])),
                    json.dumps(row["content"]), "", row.get("is_deleted", 0), "2026-10-13", "2026-10-13"))
    db.commit()
    db.close()


class FakeClient:
    def __init__(self, assigned=None):
        self.files = {}
        self.uploads = []
        self.deleted = []
        self.assigned = assigned if assigned is not None else ["PRIVATE_alice"]

    def request(self, method, path, payload=None):
        if method == "GET" and path == "/users/me":
            return {"id": 7, "username": "alice"}
        if method == "GET" and path == "/users/org-tags":
            return {"orgTags": self.assigned}
        if method == "GET" and path == "/documents/uploads":
            return list(self.files.values())
        if method == "DELETE" and path.startswith("/documents/"):
            digest = path.rsplit("/", 1)[-1]
            self.deleted.append(digest)
            self.files.pop(digest)
            return {}
        raise AssertionError((method, path))

    def upload(self, name, content, tag):
        digest = sync.hashlib.md5(content).hexdigest()
        self.uploads.append((name, content, tag))
        self.files[digest] = {
            "fileMd5": digest, "fileName": name, "orgTag": tag,
            "isPublic": False, "status": 1, "vectorizationStatus": "PROCESSING"}
        return digest


class ConversionTests(unittest.TestCase):
    def test_blocknote_structure_and_identity_are_preserved(self):
        parent = {"id": "parent", "title": "专题", "parent_id": None}
        row = {"id": "child", "title": "记录", "parent_id": "parent", "tags": '["RAG"]',
               "updated_at": "2026-10-13", "content": json.dumps([
                   {"type": "heading", "props": {"level": 2}, "content": [{"text": "标题"}]},
                   {"type": "bulletListItem", "content": [{"text": "要点"}]},
                   {"type": "codeBlock", "props": {"language": "python"}, "content": [{"text": "print(1)"}]},
                   {"type": "table", "content": {"rows": [
                       {"cells": [{"content": [{"text": "列"}]}, {"content": [{"text": "值"}]}]},
                       {"cells": [{"content": [{"text": "a"}]}, {"content": [{"text": "b"}]}]}]}},
                   {"type": "image", "props": {"url": "/uploads/p.png", "caption": "图"}}
               ])}
        output = sync.render(row, {"parent": parent, "child": row}).decode()
        for expected in ("来源文档 ID：child", "专题 / 记录", "标签：RAG",
                         "## 标题", "- 要点", "print(1)", "| --- | --- |",
                         "http://localhost:3000/uploads/p.png"):
            self.assertIn(expected, output)

    def test_distinct_source_ids_do_not_collapse_by_md5(self):
        template = {"title": "同名", "content": "[]", "tags": "[]", "updated_at": "same"}
        first = dict(template, id="one")
        second = dict(template, id="two")
        self.assertNotEqual(sync.hashlib.md5(sync.render(first, {})).digest(),
                            sync.hashlib.md5(sync.render(second, {})).digest())


class SyncTests(unittest.TestCase):
    def test_upload_then_promote_then_retire_old_only_after_new_index_is_complete(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "knowledge.db"
            state = Path(folder) / "state.json"
            row = {"id": "one", "title": "笔记", "content": [{"type": "paragraph", "content": [{"text": "第一版"}]}]}
            source_database(source, [row])
            client = FakeClient()
            self.assertEqual(sync.sync(client, source, state)["uploaded"], 1)
            first = next(iter(client.files))
            self.assertEqual(client.deleted, [])
            client.files[first]["vectorizationStatus"] = "COMPLETED"
            self.assertEqual(sync.sync(client, source, state)["unchanged"], 1)
            db = sqlite3.connect(source)
            db.execute("UPDATE documents SET content=? WHERE id='one'",
                       (json.dumps([{"type": "paragraph", "content": [{"text": "第二版"}]}]),))
            db.commit()
            db.close()
            self.assertEqual(sync.sync(client, source, state)["uploaded"], 1)
            self.assertEqual(client.deleted, [])
            second = next(key for key in client.files if key != first)
            self.assertEqual(sync.sync(client, source, state)["processing"], 1)
            client.files[second]["vectorizationStatus"] = "COMPLETED"
            self.assertEqual(sync.sync(client, source, state)["retired"], 1)
            self.assertEqual(client.deleted, [first])

    def test_wrong_org_does_not_write(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "knowledge.db"
            source_database(source, [{"id": "one", "title": "笔记", "content": []}])
            client = FakeClient(["DEFAULT"])
            with self.assertRaisesRegex(RuntimeError, "私人组织标签"):
                sync.sync(client, source, Path(folder) / "state.json")
            self.assertEqual(client.uploads, [])

    def test_trashed_source_is_not_uploaded(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "knowledge.db"
            source_database(source, [{"id": "one", "title": "已删", "content": [], "is_deleted": 1}])
            client = FakeClient()
            self.assertEqual(sync.sync(client, source, Path(folder) / "state.json")["uploaded"], 0)


if __name__ == "__main__":
    unittest.main()
