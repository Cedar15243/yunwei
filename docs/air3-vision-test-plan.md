# INMO Air3 拍照识别最小测试方案

## 目标

先跑通一个最小闭环：

1. 眼镜端触发抓取一帧图像。
2. 将图像上传到本地或公网服务。
3. 服务先返回固定文本 `你好`。
4. Unity 端把返回文本显示在眼镜屏幕上。

第一轮先验证链路，不急着接真实 AI。链路稳定后，再把服务端 `text` 替换为视觉模型返回结果。

## 文档结论

来自 `D:/Users/59979/Desktop/运维眼镜开发/眼镜开发文档.pdf`：

- Air3 SDK 是 Unity SDK，文档版本为 `INMO Air3 SDK for Unity v0.9.x`。
- Air3 基于 Android 14，Unity 需使用 2022 LTS 且不低于 `2022.3.62F2C1`，Android API Level 34。
- 眼镜系统版本需不低于 `v3.4.xxx`。
- `ArPoseManager.instance.GetAir3ImageData()` 可获取 6DoF 图像数据。
- 图像数据为 `480x640` 灰度图，包含 `timestamp`。
- 获取 6DoF 图像前，需要开启 `is6Dof`。
- `GetAir3CameraParams()` 可获取相机内参和畸变参数，但不能在 Unity `Start()` 中立即调用，需要等待服务连接。
- 戒指/Touchpad 输入要使用 `inmolib_common.Input`，不要误用 `UnityEngine.Input`。
- Unity 空间 UI 需要 Collider 才能被戒指射线点击；overlay 类型 UI 不支持戒指点击。

来自 `D:/Users/59979/Desktop/运维眼镜开发/眼镜说明书.pdf`：

- 系统有相机应用。
- Touchpad 支持相机键拍照。
- 后台白名单可以避免应用被一键清理。
- 说明书显示当前最新版本：`INMO AIR3 V3.13.034`。

## 当前服务端测试接口

已在当前 Node 服务中新增测试端点：

```text
POST /air3/vision-test
```

请求 JSON：

```json
{
  "imageBase64": "base64 encoded image"
}
```

响应 JSON：

```json
{
  "ok": true,
  "text": "你好",
  "imageBytes": 12345,
  "timestamp": "2026-06-02T00:00:00.000Z"
}
```

本地验证命令：

```powershell
npm start

Invoke-RestMethod http://127.0.0.1:8787/air3/vision-test `
  -Method POST `
  -ContentType "application/json; charset=utf-8" `
  -Body '{"imageBase64":"dGVzdA=="}'
```

眼镜真机访问时，不能使用 `127.0.0.1` 指向电脑本机。需要使用下面其中一种：

- 同一局域网：`http://电脑局域网IP:8787/air3/vision-test`
- 公网/异地：用内网穿透暴露为 HTTPS 地址
- 真机本地服务：服务直接跑在眼镜内，但第一轮不建议

## Unity 端最小脚本思路

场景准备：

1. 用 SDK 的 `SDK Sample Scene` 或新建场景。
2. 拖入 `InmoAir3 Player.prefab`。
3. 确认 `ArPoseManager` 的 `is6Dof` 已开启。
4. 放一个可见的 UI 文本，例如 TextMeshPro，用来显示状态和 AI 返回文本。
5. 放一个空间按钮，按钮所在物体需要 Collider，或者先用戒指按键触发。

示例脚本：

```csharp
using System;
using System.Collections;
using System.Text;
using inmolib_common;
using TMPro;
using UnityEngine;
using UnityEngine.Networking;

public class Air3VisionTest : MonoBehaviour
{
    [SerializeField] private TMP_Text resultText;
    [SerializeField] private string endpoint = "http://电脑局域网IP:8787/air3/vision-test";

    private const int ImageWidth = 480;
    private const int ImageHeight = 640;

    public void CaptureAndSend()
    {
        StartCoroutine(CaptureAndSendRoutine());
    }

    private void Update()
    {
        if (inmolib_common.Input.GetKeyDown(KeyCode.Return))
        {
            CaptureAndSend();
        }
    }

    private IEnumerator CaptureAndSendRoutine()
    {
        if (resultText != null)
        {
            resultText.text = "正在抓取图像...";
        }

        if (!ArPoseManager.instance.is6Dof)
        {
            resultText.text = "请先开启 6DoF";
            yield break;
        }

        ArPoseManager.ImageData imageData = ArPoseManager.instance.GetAir3ImageData();
        if (imageData.timestamp == 0 || imageData.image == null || imageData.image.Length == 0)
        {
            resultText.text = "未获取到图像";
            yield break;
        }

        var texture = new Texture2D(ImageWidth, ImageHeight, TextureFormat.R8, false);
        texture.LoadRawTextureData(imageData.image);
        texture.Apply();

        byte[] pngBytes = texture.EncodeToPNG();
        Destroy(texture);

        string body = JsonUtility.ToJson(new VisionRequest
        {
            imageBase64 = Convert.ToBase64String(pngBytes)
        });

        using var request = new UnityWebRequest(endpoint, UnityWebRequest.kHttpVerbPOST);
        request.uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(body));
        request.downloadHandler = new DownloadHandlerBuffer();
        request.SetRequestHeader("Content-Type", "application/json; charset=utf-8");

        resultText.text = "正在上传 AI...";
        yield return request.SendWebRequest();

        if (request.result != UnityWebRequest.Result.Success)
        {
            resultText.text = "上传失败：" + request.error;
            yield break;
        }

        VisionResponse response = JsonUtility.FromJson<VisionResponse>(request.downloadHandler.text);
        resultText.text = string.IsNullOrWhiteSpace(response.text) ? "AI 未返回文本" : response.text;
    }

    [Serializable]
    private class VisionRequest
    {
        public string imageBase64;
    }

    [Serializable]
    private class VisionResponse
    {
        public bool ok;
        public string text;
        public int imageBytes;
        public string timestamp;
    }
}
```

注意：如果预览或识别方向不对，先把 `ImageWidth` 和 `ImageHeight` 对调成 `640x480` 再测一次。文档写的是 `480x640`，但不同 SDK 示例可能按纹理宽高处理。

## 第一轮真机验证清单

- 眼镜系统版本是否不低于 `v3.4.xxx`。
- Unity 工程能否导入 `InmoAir3SDK_0.9.1.unitypackage`。
- APK 是否能安装到 Air3。
- `is6Dof` 开启后，`GetAir3ImageData()` 是否能稳定返回非空图像。
- 眼镜是否能访问电脑服务地址。
- 服务端是否收到上传请求，并返回 `你好`。
- 眼镜屏幕是否能清晰显示 `你好`。
- 如果用灰度图识别效果不够，是否能调用 Android 原生相机或系统相机获取彩色照片。

## 后续真实 AI 接入

第一轮测试端点只返回固定 `你好`。接真实 AI 时，保留眼镜端请求格式，服务端改为：

1. 接收 `imageBase64`。
2. 解码图片。
3. 调用视觉模型或企业 AI 服务。
4. 返回 `{ "text": "模型回答内容" }`。

这样 Unity 端不需要大改，只需要继续显示 `response.text`。
