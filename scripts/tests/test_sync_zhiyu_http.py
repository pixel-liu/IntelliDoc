import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sync_zhiyu as sync


class RecordingClient(sync.ApiClient):
    def __init__(self):
        super().__init__("http://localhost:8081/api/v1", "token")
        self.calls = []

    def request(self, method, path, payload=None, content_type="application/json"):
        self.calls.append((method, path, payload, content_type))
        return {}


class UploadRequestTests(unittest.TestCase):
    def test_uses_existing_chunk_and_merge_api_with_private_scope(self):
        client = RecordingClient()
        content = b"# one\n\nhello"
        digest = client.upload("zhiyu-one.md", content, "PRIVATE_alice")
        self.assertEqual(digest, hashlib.md5(content).hexdigest())
        self.assertEqual(len(client.calls), 2)
        method, path, payload, content_type = client.calls[0]
        self.assertEqual((method, path), ("POST", "/upload/chunk"))
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        for field in (b'name="orgTag"\r\n\r\nPRIVATE_alice',
                      b'name="isPublic"\r\n\r\nfalse',
                      b'name="fileMd5"\r\n\r\n' + digest.encode(),
                      b'name="fileName"\r\n\r\nzhiyu-one.md',
                      content):
            self.assertIn(field, payload)
        self.assertEqual(client.calls[1][:3],
                         ("POST", "/upload/merge", {"fileMd5": digest, "fileName": "zhiyu-one.md"}))


if __name__ == "__main__":
    unittest.main()
