# Trivy 工具目录

D 平台的 DevSecOps 安全扫描依赖 Trivy。

## 后端配置

`application.yml` 中：
```yaml
devops:
  security:
    trivy-path: ./tools/trivy
```

`SecurityScanService.resolveTrivyPath()` 按以下顺序查找，**跨平台自适应**：

1. 若配置是 `trivy` 或为空 → 走系统 PATH
2. 若配置是**目录** → 目录内找 `trivy.exe` (Windows) 或 `trivy` (Linux)
3. 若配置路径无后缀 → 自动补 `.exe` (仅 Windows)
4. 绝对路径：直接用
5. 相对路径：先 JVM 工作目录，再 `${user.home}`
6. 都不存在：返回原值让 ProcessBuilder 报错

## 下载

### Linux / macOS

```bash
# 方式 1: 官方脚本
curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b ./tools/trivy

# 方式 2: 手动下载
# https://github.com/aquasecurity/trivy/releases 下载 trivy_X.X.X_Linux-64bit.tar.gz
# 或 macOS: trivy_X.X.X_macOS-64bit.tar.gz
# 解压后把 trivy 放到本目录
chmod +x ./trivy
./trivy --version
```

### Windows

```bash
# 从 https://github.com/aquasecurity/trivy/releases 下载
# trivy_X.X.X_windows-64bit.zip
# 解压后把 trivy.exe 放到本目录
./trivy.exe --version
```

## 验证

```bash
# Linux/Mac
./trivy --version

# Windows
./trivy.exe --version
```

## 注意

本目录被 `.gitignore` 忽略（仅忽略 .exe/.bin），每个开发者自行下载。
