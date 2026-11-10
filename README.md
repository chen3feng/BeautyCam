# BeautyCam

在三星（以及任何 Android 手机）上复刻小米风格前置自拍美颜的相机 App。

- CameraX 前置取景 + 拍照
- **实时 GPU 预览美颜（约 30fps）**：磨皮、美白、暖色随滑杆即时生效
- ML Kit 人脸检测 / 关键点 / 轮廓
- 自研美颜管线：双尺度磨皮、美白、去红、暖色、柔焦、眼部提亮、唇色、瘦脸、大眼
- 脸、脖子、额头使用同一套肤色曲线，避免“脸白脖子红”
- 拍完可按住画面看原图，确认后保存到 `Pictures/BeautyCam`
- 界面中英双语，跟随系统语言

原理、调研过程和算法细节见 [docs/PRINCIPLES.md](docs/PRINCIPLES.md)。

## 构建

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.chen3feng.beautycam android.permission.CAMERA
adb shell am start -n com.chen3feng.beautycam/.MainActivity
```

要求：Android Studio / SDK（compileSdk 36）、JDK 17+、Android 10+ 手机。

推荐装 **release** 包：debug 包只跑 JIT，第一张照片的美颜比 release 慢约一倍。

## 测试与检查

```bash
./gradlew :app:testDebugUnitTest   # JVM 单元测试（BeautyMath）
./gradlew :app:lintDebug           # Android Lint
./gradlew :app:assembleDebug       # 编译
```

CI（GitHub Actions，见 `.github/workflows/ci.yml`）会跑同样的三项。CI 只做检查，不上传 APK 等构建产物。

## 使用

1. 打开 App，默认前置摄像头，预览已经带实时美颜。
2. 下方四个滑杆默认：磨皮 50、美白 100、瘦脸 0、大眼 50。磨皮/美白会实时改变预览；瘦脸/大眼作用在拍下的照片上（预览暂不做几何变形）。
3. 点快门 → 画面立刻显示当前预览帧，约 0.4 秒后被处理好的照片替换 → 按住画面查看原图 → 保存。
4. 左下角是最近保存的照片，右下角切换前后摄像头。

实测与小米 12 的差距（同一张三星原图）：皮肤 L 70.3 vs 68.0、a 14.8 vs 14.4、饱和度 98.4 vs 97.0、高频纹理 0.94 vs 1.05。

## 调试：导入已有照片

不经过相机，直接把一张图喂给美颜管线：

```bash
adb push your.jpg /data/local/tmp/test.jpg
adb shell run-as com.chen3feng.beautycam cp /data/local/tmp/test.jpg files/test.jpg
adb shell am start -n com.chen3feng.beautycam/.MainActivity \
  --es image_path /data/user/0/com.chen3feng.beautycam/files/test.jpg
```

还可加 `--ei smooth 0 --ei whiten 0 --ei slim 50 --ei eyes 50 --ei enabled 0` 覆盖滑杆，`--ei repeat 5` 重复处理以测量 JIT 预热后的耗时。处理结果另存为 `files/debug_out.jpg`。

## 当前限制

- 实时预览用肤色 mask + 13-tap 双边近似，不做人脸关键点，所以**瘦脸/大眼/唇色/眼妆只在拍下的照片里生效**；预览和最终照片会有轻微差异。
- 6MP 照片的美颜约 0.36 秒（release、首张）；从按下快门到出图约 1.1 秒，其余时间花在 HAL 出图和 JPEG 解码上。
- 瘦脸/大眼是简化 2D 位移场，不是 3D 网格。
- 暂无妆效、发际线、去痣、视频美颜、HDR/夜景。

这些都在 `docs/PRINCIPLES.md` 的“下一步”里有对应方案。
