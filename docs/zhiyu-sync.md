# 将 batter-coder 的知屿文档同步到 IntelliDoc

本地桥接脚本 [sync_zhiyu.py](../scripts/sync_zhiyu.py) 读取同级目录中的 batter-coder/notion-style-personal-site-main/data/knowledge.db，将每篇未删除的笔记转换为独立 Markdown，通过 IntelliDoc 已有的认证上传接口写入当前账号的私人组织空间。它不修改知屿数据库，不直写 IntelliDoc 的 MySQL、MinIO 或 Elasticsearch。

## 首次运行

先确保 IntelliDoc 后端和 MySQL、Redis、Kafka、MinIO、Elasticsearch 正常运行，并能用所选账号登录。Python 3 只使用标准库，无需安装额外依赖。

在 IntelliDoc 项目根目录运行：

~~~powershell
python scripts/sync_zhiyu.py --dry-run
python scripts/sync_zhiyu.py --username 你的IntelliDoc用户名 --limit 3
~~~

第二条命令会交互输入密码。确认这三篇在“知识库”中显示，向量化状态为 COMPLETED，且另一账号无法检索后，再运行：

~~~powershell
python scripts/sync_zhiyu.py --username 你的IntelliDoc用户名
~~~

也可以把已有的 IntelliDoc JWT 放入当前终端的 INTELLIDOC_TOKEN 环境变量后直接运行；脚本不会将密码或 JWT 写入状态文件。不要把令牌传入命令行参数或提交到 Git。

## 更新与恢复

重复执行同一命令即可增量同步。脚本以知屿文档 ID 识别笔记，按转换后的文件内容计算 MD5。同一账号同一内容不会重复上传。更新时先上传新版本；只有确认其向量化状态为 COMPLETED 才删除脚本记录的旧版本。Kafka 异步处理可能尚未结束，此时再次运行即可完成清理。向量化失败会保留旧版本并报告失败，可在 IntelliDoc 知识库中重试向量化后重新运行。

本机同步状态写在 .runtime/zhiyu-sync.json，该目录已经被 Git 忽略。状态绑定 IntelliDoc 用户 ID 和源数据库绝对路径；切换账号或源库时请用不同的 --state 路径。源文档进入回收站或从源库消失后，不会自动删除 IntelliDoc 副本，避免误删；需要人工确认后在 IntelliDoc 中删除。

自定义路径和服务地址：

~~~powershell
python scripts/sync_zhiyu.py --source D:\path\knowledge.db --api-base http://localhost:8081/api/v1 --username 你的IntelliDoc用户名
~~~

## 内容和权限边界

- 标题、父级路径、标签、更新时间、源文档 ID 会写入每篇 Markdown；正文保留标题、列表、待办、引用、代码块和表格。源库回收站文档不会导入。
- 导入器在上传前验证当前账号拥有名为 PRIVATE_<用户名> 的私人组织标签，上传时固定 isPublic=false。它不会把文档上传到默认共享组织。
- 知屿图片位于源项目的 public/uploads，Markdown 中的本地图片链接转换为 http://localhost:3000/uploads/...。因此图片预览依赖源项目仍在 3000 端口运行；图片本身和图片中的文字没有迁移或 OCR。文档文本可独立检索。
- IntelliDoc 现有搜索会同时检索用户可访问的个人、组织和公开文档。当前脚本只确保导入内容属于个人空间；若需要聊天中的“仅搜个人库”开关，还需扩展搜索范围参数。
- IntelliDoc 的嵌入和问答流程可能调用所配置的外部 AI 服务；将本地笔记导入前应确认这些笔记允许按当前服务配置处理。

## 验证

~~~powershell
python -m unittest discover -s scripts/tests -p test_sync_zhiyu.py -v
python scripts/sync_zhiyu.py --dry-run
~~~

脚本返回码非零表示同步出现失败。--dry-run 只读取 SQLite 并转换内容，不访问 IntelliDoc、不保存状态。
