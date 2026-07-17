# 叮当专家协同演示手册

## 演示能力

- Air3一键广播呼叫在线专家，第一位接听者成为主专家。
- 主专家网页接收眼镜第一视角和语音，可画笔、箭头、圆圈、撤销、清空。
- 主专家可冻结同一画面、截图记录，并邀请刘工旁听语音。
- 刘工加入同一TRTC房间，麦克风可用，标注与邀请按钮保持锁定。
- 新包801与原626包并行安装，不覆盖原应用。

## 首次准备

1. 在TRTC控制台复制应用 `叮当专家协同` 的SDK密钥。
2. 将密钥仅写入 `tmp/trtc_sdk_secret.local`，文件只包含密钥本身。
3. 不要把密钥写入 `.env.example`、网页、APK、截图或聊天记录。
4. 确认TRTC后付费未开通，免费包耗尽后允许服务自动停止。

## 启动服务

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-expert-collab-demo.ps1
```

脚本会输出两个网页地址：

- `expert-wang / 王工`：用于第一位接听并成为主专家。
- `expert-liu / 刘工`：保持在线，等待王工邀请旁听。

停止服务：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-expert-collab-demo.ps1
```

## 构建与安装眼镜端

USB演示使用默认 `127.0.0.1:8787`，安装脚本会执行ADB反向端口映射：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-dingdang-expert-collab-apk.ps1
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-dingdang-expert-collab.ps1
```

局域网无线演示需按电脑局域网IP重新构建：

```powershell
$env:COLLAB_SERVER_URL="http://192.168.1.10:8787"
powershell -ExecutionPolicy Bypass -File scripts\build-dingdang-expert-collab-apk.ps1
```

安装脚本会在安装前后验证原包仍为626，并确认新包为801。

## 演示顺序

1. 打开王工和刘工网页，保持两页在线。
2. Air3点击“呼叫远程专家”。
3. 王工网页点击“接听”，刘工页面显示已由其他专家接听。
4. 验证眼镜第一视角、眼镜麦克风和专家语音。
5. 王工依次演示箭头、画笔、圆圈、撤销、清空。
6. 点击“冻结画面”，确认网页和眼镜显示同一帧；再次点击恢复实时。
7. 点击“截图”，确认右侧截图记录新增缩略图。
8. 王工点击“邀请专家”，刘工点击“加入”，验证旁听语音且标注按钮禁用。
9. Air3或王工点击挂断，确认双方回到等待状态。

## 演示前检查

- TRTC控制台应用状态正常，后付费仍关闭，未订阅录制、翻译或AI能力。
- 电脑、Air3和浏览器时间准确；无线演示处于同一局域网。
- 浏览器允许麦克风；Air3已授予相机和录音权限。
- 原包 `com.codex.air3nativecamera.dingdangexpert.follow` 版本仍为626。
- 新包 `com.codex.air3nativecamera.dingdangexpert.collab` 版本为801。
- 完整流程预演一次，并至少连续通话20分钟观察发热、音频路由和画面稳定性。
