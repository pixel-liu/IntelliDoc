# IntelliDoc 云服务器部署

本目录用于单台 Ubuntu/Debian 云服务器部署：Docker Compose 运行 MySQL、Redis、MinIO、Kafka 和 Elasticsearch；systemd 运行 Spring Boot；Nginx 托管前端并代理 API 与 WebSocket。

建议服务器至少 4 核、8 GB 内存、80 GB SSD。安全组初期只开放 SSH、80 和 MinIO 的 19000 端口；配置域名和 HTTPS 后，开放 443，并关闭公网 19000。MySQL、Redis、Kafka、Elasticsearch 和 MinIO 控制台均只绑定本机地址。

## 本地生成发布包

在 Windows PowerShell 的项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/build-release.ps1
```

产物为 `build/intellidoc-release.zip`。脚本会构建后端和前端，并拒绝打包不含依赖的普通 JAR。发布构建使用项目内的 Maven Central 临时设置，不会修改电脑上的全局 Maven 配置。
如果代码未变化、只更新了部署文件，可以加 `-SkipBuild` 复用已验证的构建产物。

## 服务器准备

服务器使用 Ubuntu 或 Debian 时，上传并解压发布包后可以先安装 Java 17、Docker Compose v2、Nginx、Python 3 venv、curl 和 openssl：

```bash
cd /path/to/intellidoc-release
chmod +x deploy/scripts/*.sh
sudo ./deploy/scripts/bootstrap-host.sh
./deploy/scripts/init-env.sh http://服务器公网IP http://服务器公网IP:19000
```

有域名时推荐使用两个域名：

```bash
./deploy/scripts/init-env.sh https://docs.example.com https://storage.example.com
```

编辑 `deploy/app.env`，填写 `DEEPSEEK_API_KEY` 和 `EMBEDDING_API_KEY`。然后执行预检和安装：

```bash
./deploy/scripts/preflight.sh
sudo ./deploy/scripts/install-release.sh 服务器公网IP
```

使用两个域名时：

```bash
sudo ./deploy/scripts/install-release.sh docs.example.com storage.example.com
```

安装脚本会启动基础设施、安装 LiteParse、复制前后端、启用 systemd 和 Nginx。它不会自动签发 HTTPS 证书；域名解析生效后可以使用 Certbot 为两个 Nginx 站点签发证书。

## 首次管理员

生成的管理员密码保存在 `deploy/app.env` 的 `ADMIN_BOOTSTRAP_PASSWORD`。安装完成后执行：

```bash
sudo systemctl stop intellidoc
sudo /opt/intellidoc/deploy/scripts/create-admin.sh
sudo systemctl start intellidoc
```

创建脚本检测到管理员已经存在时会安全跳过。确认登录成功后，应从 `/etc/intellidoc/app.env` 删除 `ADMIN_BOOTSTRAP_PASSWORD`，或替换为不再使用的随机值。

## 检查

```bash
sudo /opt/intellidoc/deploy/scripts/verify.sh http://服务器公网IP
sudo journalctl -u intellidoc -n 200 --no-pager
```

基础检查通过后，在真实浏览器中完成登录、上传 PDF、等待 Kafka 处理、知识库检索、聊天 WebSocket 和引用预览验证。数据卷位于 Docker volumes，生产使用前还需配置 MySQL 与 MinIO 的定期备份。
