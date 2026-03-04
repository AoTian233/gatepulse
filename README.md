# UniversalDeviceGate (LSPosed)

## 功能
- Hook App 启动早期：`Instrumentation.callApplicationOnCreate`
- 基于 `(device_token, package_name)` 做远程白名单校验
- 本地缓存 `allowed + expires_at`，避免每次启动联网
- 返回 `allowed=false` 时直接结束进程
- 支持 HMAC-SHA256 响应签名校验（默认强制）

## API 协议
- URL：`BuildConfig.API_BASE_URL`
- Method：`POST`
- Content-Type：`application/json`
- 请求：
```json
{
  "device_token": "uuid-generated-once",
  "package_name": "com.example.app"
}
```
- 响应：
```json
{
  "allowed": true,
  "expires_at": 1760000000000,
  "signature": "hex_hmac_sha256"
}
```
- `expires_at` 为毫秒时间戳
- `signature` 对 payload `device_token|package_name|allowedBit|expires_at` 做 HMAC-SHA256

## Android 配置
修改 [app/build.gradle](C:\Users\Dehao\Desktop\AI\x\app\build.gradle) 的 `defaultConfig`：
- `API_BASE_URL`
- `RESPONSE_SIGNING_KEY`
- `ENFORCE_SIGNATURE`
- `FAIL_OPEN` (network/cache miss fallback)
- `DEFAULT_CACHE_TTL_MS`
- `ENABLE_LOG`

## 构建
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Build `app` APK (`debug` or `release`).
4. Install APK on rooted phone with LSPosed.

## LSPosed setup
1. Enable this module in LSPosed.
2. In scope, select only the apps you want controlled.
3. Reboot or force-stop target apps.
4. Check LSPosed log for tag `DeviceGate`.

## 后端部署（Supabase + Vercel）
- 后端代码在 `backend/`
- 详细步骤看 [backend/README.md](C:\Users\Dehao\Desktop\AI\x\backend\README.md)

## GitHub Actions 自动编译 APK
- 工作流文件：[android-apk.yml](C:\Users\Dehao\Desktop\AI\x\.github\workflows\android-apk.yml)
- 触发方式：
  - push 到 `main`
  - PR
  - 手动触发（Actions 页面）
- 产物：
  - `app-debug-apk`
  - `app-release-apk`（默认 unsigned）
