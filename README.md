# IntelliDoc

IntelliDoc 是一个面向企业知识管理的 RAG（检索增强生成）系统。系统支持文档上传、解析、向量检索和基于知识库的对话，并通过组织标签实现多租户数据隔离。

## 核心功能

- 文档分片上传、断点续传、解析与索引
- 语义检索与关键词混合检索
- 基于知识库上下文的流式 AI 对话
- 会话与引用来源持久化
- 公开、私有及组织级知识库权限控制
- 用户、角色、组织标签和系统配置管理

## 技术架构

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.4、Spring Security、Spring Data JPA、WebSocket |
| 前端 | Vue 3、TypeScript、Vite、Naive UI、Pinia、UnoCSS |
| 数据存储 | MySQL、Redis、Elasticsearch、MinIO |
| 异步处理 | Kafka |
| 文档处理 | Apache Tika、LiteParse、可选 OCR 服务 |
| AI 服务 | 可配置 LLM 与 Embedding Provider |

文档处理与问答流程：

```text
文档上传 -> 解析与分块 -> Embedding -> Elasticsearch 索引
用户提问 -> 混合检索 -> 上下文组装 -> LLM 生成 -> WebSocket 流式返回
```

## 项目结构

```text
.
├── src/main/java/com/yizhaoqi/intellidoc/  # Spring Boot 后端
├── src/main/resources/                     # 后端配置与静态资源
├── frontend/                               # Vue 3 前端
├── docs/                                   # 部署配置、数据库脚本与项目文档
├── scripts/                                # 辅助测试脚本
├── .env.example                            # 环境变量模板
├── infra.sh                                # 本地基础服务管理脚本
└── pom.xml                                 # Maven 配置
```

## 环境要求

- JDK 17
- Maven 3.8.6+
- Node.js 18.20+
- pnpm 8.7+
- MySQL 8
- Redis 7
- Elasticsearch 8.10
- Kafka
- MinIO

## 本地启动

### 1. 配置环境变量

复制根目录配置模板并填写数据库、缓存、对象存储、检索服务、JWT、LLM 和 Embedding 等配置：

```bash
cp .env.example .env
```

后端会自动读取根目录 `.env`。请勿提交包含密钥的 `.env`。

### 2. 启动基础服务

可使用 Docker Compose 启动 MySQL、Redis、MinIO、Kafka 和 Elasticsearch：

```bash
cd docs
docker compose up -d
```

也可以根据本机环境调整 `infra.sh` 中的服务路径，然后按需管理 MinIO、Kafka 和 Elasticsearch：

```bash
./infra.sh start
./infra.sh status
./infra.sh logs elasticsearch
```

Windows 本地开发可使用一键脚本；它会复用已启动的服务，并只记录自己启动的进程：

```powershell
.\scripts\start-local.ps1
.\scripts\stop-local.ps1
```

停止脚本会停止启动脚本登记的前后端、Kafka、MinIO 和 Elasticsearch；不会停止脚本运行前已存在的服务。

若 Kafka 的本地数据目录已经损坏，启动脚本会保留数据并报告失败；不会自动删除 topic 或消费位点。

### 3. 启动后端

```bash
mvn spring-boot:run
```

后端默认监听 `http://localhost:8081`，API 前缀为 `/api/v1`。

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

前端开发地址为 `http://localhost:9527`。`pnpm dev` 使用 test 模式，服务地址由 `frontend/.env.test` 控制。

## 常用命令

```bash
# 后端编译与测试
mvn -q -DskipTests compile
mvn test

# 前端检查与构建
cd frontend
pnpm typecheck
pnpm lint
pnpm build
```

## 配置说明

- 后端：根目录 `.env`、`src/main/resources/application*.yml`
- 前端：`frontend/.env*`
- 数据库初始化：`docs/databases/ddl.sql`
- Docker 基础服务：`docs/docker-compose.yaml`
- Nginx 示例：`docs/nginx.conf`

生产环境至少应设置独立的数据库凭据、对象存储凭据、AI 服务密钥和强随机 `JWT_SECRET_KEY`，并关闭不需要的管理员初始化与公开注册配置。

## License

见 [LICENSE](LICENSE)。
