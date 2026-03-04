# Supabase + Vercel 部署指南

## 1. 在 Supabase 建表
1. 打开 Supabase 项目后台。
2. 进入 SQL Editor，执行 `sql/init.sql` 全部内容。
3. 在 `Table Editor -> device_rules` 里手动加测试数据：
   - `device_token`: 设备 token
   - `package_name`: 包名（如 `com.example.app`）
   - `allowed`: `true` 或 `false`
   - `expires_at`: 可空；为空时 API 会用默认 TTL

## 2. 部署 Vercel API
1. 在本目录执行：
```bash
npm install
vercel
```
2. 绑定项目后，在 Vercel 项目环境变量配置：
   - `SUPABASE_URL`
   - `SUPABASE_SERVICE_ROLE_KEY`
   - `DEVICE_SIGNING_KEY`（建议 32 字节以上随机字符串）
   - `ADMIN_TOKEN`（网页管理端鉴权）
   - `DEFAULT_TTL_MS`（例如 `900000`）
3. 再执行：
```bash
vercel --prod
```

## 3. 接口测试
接口地址：
- `https://<your-project>.vercel.app/api/device/check`

请求示例：
```bash
curl -X POST "https://<your-project>.vercel.app/api/device/check" \
  -H "Content-Type: application/json" \
  -d "{\"device_token\":\"test-token\",\"package_name\":\"com.example.app\"}"
```

返回示例：
```json
{
  "allowed": true,
  "expires_at": 1760000000000,
  "signature": "hex_hmac_sha256"
}
```

说明：
- 当 `device_token + package_name` 在数据库不存在时，`/api/device/check` 会自动创建一条 `allowed=false` 的记录（默认拒绝）。
- 你在管理端把该记录改为 `allowed=true` 后，客户端下次校验即可放行。

## 4. 网页管理端
- 部署后访问：`https://<your-project>.vercel.app/`
- 首次打开输入 `ADMIN_TOKEN`，即可新增/更新/删除规则
- 管理 API：`/api/admin/rules`

## 5. 与 Android 模块对齐
在 Android 的 `app/build.gradle` 修改：
- `API_BASE_URL` -> 你的 Vercel 地址
- `RESPONSE_SIGNING_KEY` -> 与 `DEVICE_SIGNING_KEY` 完全一致
- `ENFORCE_SIGNATURE` 建议保持 `true`

## 6. 生产建议
- `SUPABASE_SERVICE_ROLE_KEY` 只能在 Vercel 环境变量中使用，不能出现在客户端。
- 建议在 Supabase 打开 PITR/备份。
- 可以给 `device_rules` 增加审计字段（操作者、来源 IP、变更记录）。
