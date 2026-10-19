# 固定 Android 发布签名

所有 Android 更新必须使用同一个 keystore。不要在 CI 中临时生成 keystore，否则用户无法覆盖安装更新包。

首次配置时，在本地生成一次 keystore：

```bash
keytool -genkeypair -v -keystore puzhe-release.keystore \
  -storepass '替换为强密码' -keypass '替换为强密码' -alias puzhe \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname 'CN=PuZhe Chess, OU=Chess Lab, O=DeepShawn, C=CN'
base64 -w0 puzhe-release.keystore
```

在 GitHub 仓库 Settings → Secrets and variables → Actions 中创建以下四个 **Repository secrets**：

- `ANDROID_KEYSTORE_BASE64`：上面命令输出的完整 Base64
- `ANDROID_KEYSTORE_PASSWORD`：keystore 密码
- `ANDROID_KEY_ALIAS`：`puzhe`
- `ANDROID_KEY_PASSWORD`：密钥密码

发布工作流会在缺少任一 Secret 时停止，不会生成临时签名。keystore 文件本身不要提交到 Git 仓库。
