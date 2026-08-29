# VisionRoute APK 交付说明 V0.1

更新时间：2026-06-09

## 当前 GitHub APK

- 手机端 Debug APK：`android/releases/VisionRoute-debug.apk`
- 提交记录：`59c9464 Upload debug APK artifact`
- 上传方式：Git LFS
- 说明：该 APK 由 `android/ai-glasses-poc/app/build/outputs/apk/debug/app-debug.apk` 复制而来，内置当前眼镜端更新包。

## 眼镜端 Bridge APK

- 构建产物：`android/ai-glasses-poc/rokid-bridge/build/outputs/apk/debug/rokid-bridge-debug.apk`
- 手机端内置更新包：`android/ai-glasses-poc/app/src/main/assets/rokid/visionroute_rokid_bridge.apk`
- 说明：手机 App 可通过内置资产更新眼镜端 Bridge；如需单独 ADB 安装眼镜端，可使用构建产物路径。

## 从 GitHub 获取 APK

首次拉取仓库前先启用 Git LFS：

```powershell
git lfs install
git clone git@github.com:vhaozheng/4dai-glasses.git
cd 4dai-glasses
git lfs pull
```

已有仓库更新：

```powershell
git pull
git lfs pull
```

如果 `android/releases/VisionRoute-debug.apk` 文件很小且内容类似 LFS pointer，说明还没有执行 `git lfs pull`。

## 本地重新构建

在 `android/ai-glasses-poc` 下执行：

```powershell
F:\Gradle\gradle-8.6\bin\gradle.bat :rokid-bridge:assembleDebug --no-daemon --console=plain --max-workers=1
Copy-Item -Force .\rokid-bridge\build\outputs\apk\debug\rokid-bridge-debug.apk .\app\src\main\assets\rokid\visionroute_rokid_bridge.apk
F:\Gradle\gradle-8.6\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

重新发布到仓库：

```powershell
Copy-Item -Force .\app\build\outputs\apk\debug\app-debug.apk ..\releases\VisionRoute-debug.apk
git add ..\releases\VisionRoute-debug.apk
git commit -m "Update debug APK artifact"
git push
```

## ADB 安装

手机端：

```powershell
adb -s <phone_serial> install -r android\releases\VisionRoute-debug.apk
```

眼镜端 Bridge：

```powershell
adb -s <glasses_serial> install -r android\ai-glasses-poc\rokid-bridge\build\outputs\apk\debug\rokid-bridge-debug.apk
```

当前习惯：安装后不自动启动 App，避免打断现场调试状态。

## 最近验证

- `:rokid-bridge:assembleDebug`：通过
- `:app:assembleDebug`：通过
- `:app:testDebugUnitTest`：通过
- 最近已安装设备：
  - 手机：`7e98495f`
  - Rokid 眼镜：`1906092613101284`
