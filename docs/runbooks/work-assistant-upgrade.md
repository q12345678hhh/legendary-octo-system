# 工作助手升级与回滚运行手册

本文覆盖 WP01-05 引入的托盘客户端升级链路：签名清单、包校验、拒绝降级、健康检查与失败回滚。
客户端升级只由「员工确认安装」触发，服务端没有任何远程执行或远程开启采集的接口。

## 1. 发布前准备

| 项目 | 配置项 | 说明 |
| --- | --- | --- |
| 策略签名私钥 | `PLATFORM_WA_POLICY_KEY` | ECDSA P-256 PKCS#8（Base64），只放在密钥管理系统中，不进仓库 |
| 策略签名公钥 | `PLATFORM_WA_POLICY_PUBLIC_KEY` | SPKI（Base64），下发给客户端用于固定验签 |
| 策略密钥标识 | `PLATFORM_WA_POLICY_KEY_ID` | 客户端按 keyId 固定匹配，轮换时必须换新值 |
| 更新签名私钥 | `PLATFORM_WA_UPDATE_KEY` | 与策略密钥分离，泄露影响面更小 |
| 更新签名公钥 | `PLATFORM_WA_UPDATE_PUBLIC_KEY` | SPKI（Base64） |
| 更新密钥标识 | `PLATFORM_WA_UPDATE_KEY_ID` | 同上 |
| 最低支持版本 | `PLATFORM_WA_MIN_VERSION` | 低于该版本的设备心跳返回 `updateRequired=true` |
| 更新版本 | `PLATFORM_WA_UPDATE_VERSION` | 清单中的目标版本 |
| 更新包地址 | `PLATFORM_WA_UPDATE_URL` | HTTPS，必须是安装包直链 |
| 更新包摘要 | `PLATFORM_WA_UPDATE_SHA256` | 小写十六进制，64 位 |

缺少更新密钥时心跳不返回清单（`updateRequired=false`），不会把设备置于无法升级的状态。

## 2. 发布步骤

1. 版本号自增，构建安装包，记录产物 `work-assistant-<version>.zip`。
2. 计算 `sha256sum work-assistant-<version>.zip`，与清单字段核对。
3. 上传安装包到对象存储，确认 HTTPS 可匿名读取（或带签名 URL，有效期覆盖升级窗口）。
4. 计算清单规范串并签名：

   ```
   version|sha256|url|min|releasedAt
   ```

   例：`0.2.0|<sha256>|https://updates.example/work-assistant/0.2.0.zip|0.1.0|2026-11-11T00:00:00Z`

   使用 `PLATFORM_WA_UPDATE_KEY` 对应的私钥做 `SHA256withECDSAinP1363Format` 签名，Base64 编码。
5. 写入 `PLATFORM_WA_UPDATE_VERSION`、`PLATFORM_WA_UPDATE_URL`、`PLATFORM_WA_UPDATE_SHA256`，
   需要强制升级时同时提高 `PLATFORM_WA_MIN_VERSION`。
6. 灰度：先对内部租户开放，确认心跳返回 `updateRequired` 且客户端能完成验签与安装。

## 3. 客户端升级行为

- 校验清单 keyId 与固定公钥，签名不合法直接拒绝。
- 校验安装包 SHA-256，不一致直接拒绝，不落盘到安装目录。
- 拒绝降级：目标版本不高于当前版本时拒绝暂存。
- 安装后执行健康检查（进程存活 + 心跳成功）；失败时自动 `RestorePrevious`，
  结果返回 `RolledBack`，用户仍停留在旧版本。

## 4. 回滚

自动回滚失败或需要人工回退时：

1. 保持 `PLATFORM_WA_UPDATE_VERSION` 不变或回退到上一个已知良好版本，并重新签名清单。
2. 若必须降级，先临时把 `PLATFORM_WA_MIN_VERSION` 调到不高于目标版本，否则客户端会拒绝降级。
3. 通过企业软件分发渠道推送旧版安装包，托盘客户端不提供远程降级入口。
4. 回滚后核对：心跳 `appVersion` 已回到目标版本，`work_assistant_device.app_version` 与之一致。

## 5. 密钥轮换

1. 生成新的密钥对，新 keyId（如 `update-2027-01`）。
2. 更新服务端 `PLATFORM_WA_UPDATE_KEY`、`PLATFORM_WA_UPDATE_PUBLIC_KEY`、`PLATFORM_WA_UPDATE_KEY_ID`。
3. 客户端固定公钥随版本升级下发；在旧公钥仍被固定期间，**不要删除旧私钥**，
   否则旧版本客户端无法验证新清单（会安全地拒绝升级，而不是被降级）。
4. 策略密钥轮换同理，额外注意：策略版本必须继续单调递增，客户端会拒绝版本回退。

## 6. 撤销设备

```bash
curl -X POST "$API/api/v1/work-assistant/devices/$DEVICE_ID/revoke" \
  -H "X-Tenant-Id: $TENANT" -H "X-Subject-Id: $OPERATOR"
```

撤销后该设备的心跳、策略拉取与片段上传全部返回 403 `DEVICE_REVOKED`，
客户端立即停止采集、清空本地加密队列并把托盘状态标记为已撤销。撤销不可逆，需要重新注册新设备。

## 7. 故障排查

| 现象 | 排查 |
| --- | --- |
| 注册 503 `POLICY_SIGNING_UNAVAILABLE` | 未配置策略私钥或 Base64 非法 |
| 上传 409 `POLICY_OUTDATED` | 管理员刚追加了排除项；客户端下个心跳周期刷新策略后自动重试 |
| 上传 403 `DEVICE_REVOKED` | 设备已被撤销，需重新注册 |
| 上传 400 `INVALID_SIGNATURE` | 客户端时钟偏差超过 5 分钟，或规范串与请求体不一致 |
| 策略 422 `POLICY_UNKNOWN_FIELD` | 请求包含了未知字段（例如试图整体覆盖进程清单），属于预期拒绝 |
| 更新一直 `RolledBack` | 查看客户端健康检查日志；确认新版本能独立启动并通过心跳 |

## 8. 取证脚本

```powershell
powershell -ExecutionPolicy Bypass -File scripts\work-assistant-verify.ps1
```

脚本会生成临时密钥到 `var/wp01-05/`（不进入版本库），跑通注册、补传、暂停零采集、
崩溃恢复、策略追加与撤销，并把证据写入 `docs/evidence/wp01-05/`。
