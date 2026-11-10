# 小米美颜原理调研 & BeautyCam 实现说明

本文分三部分：

1. 小米 12 的相机到底怎么实现美颜（基于真机 APK / 系统库 / EXIF 的实测）
2. 三星 S25+ 为什么“差一点”
3. BeautyCam 如何在不依赖小米 HAL 的前提下复刻，以及每个算法步骤的数学含义

---

## 1. 小米的实现：不是“一个滤镜”，而是一条相机管线

### 1.1 真机证据

从 `Xiaomi 12 (2201123G)` 上拉取 `MiuiCamera.apk`（116 MB）并反编译后，可以看到三套关键组件：

| 组件 | 证据 | 作用 |
|---|---|---|
| 人脸检测/关键点 | `libMegviiFacepp-0.5.2.so`、`libmegface.so`、`libmiface.so`、`libcvface_api.so`、`libFace3D_hlos.so` | 旷视 Face++ 的人脸框、关键点、3D 姿态 |
| 美颜引擎 A | `libarcsoft_beautyshot.so`、`camera_arcsoft_beautyshot`、`jni_arcsoft_beautyshot` | ArcSoft BeautyShot（第三方美颜 SDK） |
| 美颜引擎 B | `libmialgoengine.so`、`libmialgo_ie_capture.so`、`libmialgo_ie_preview.so`、`libmialgo_image_colourkeeping.so`、`libmialgo_ai_vision.so` | 小米自研 mialgo / 影像大脑，负责增强、色彩一致性、分割等 |
| 人像专项 | `libmiai_portraitsupernight.so`、`libbeauty_face_interface.so` | 人像超级夜景、脸部接口 |

DEX 里明确存在三种美颜后端：

```java
BEAUTY_ALGO_ARCSOFT   = 1
BEAUTY_ALGO_TRUESIGHT = 2   // 小米自研 TrueSight
BEAUTY_ALGO_TSMIXARC  = 3   // TrueSight + ArcSoft 混合
```

`BeautyShot` 类加载 `camera_beauty_mpbase` / `camera_arcsoft_beautyshot` / `jni_arcsoft_beautyshot`，并提供两个 native 入口：

```java
processByBeautyLevel(AssetManager, byte[] yuv, ...)
processBySmoothLevel(AssetManager, byte[] yuv, ...)
```

也就是说，至少有一条路径是**在 YUV 域、拍照管线内部**完成美颜，而不是拍完再套滤镜。

### 1.2 参数不是 UI 上的 3 个滑杆，而是 30+ 个 vendor tag

`BeautyConstant.BEAUTY_VENDOR_TAG_MAP` 把每个美颜参数映射成 Camera2 的 `CaptureRequest` vendor key，交给 HAL/ISP：

| 参数 | vendor tag |
|---|---|
| 磨皮 | `BEAUTY_SKIN_SMOOTH` |
| 美白 | `BEAUTY_WHITEN` |
| 肤色 | `BEAUTY_SKIN_COLOR` |
| 瘦脸 | `BEAUTY_SLIM_FACE` |
| 大眼 | `BEAUTY_ENLARGE_EYE` |
| 立体（光影） | `BEAUTY_SOLID` |
| 鼻子/鼻尖/鼻梁 | `BEAUTY_NOSE` / `BEAUTY_NOSE_TIP` / `BEAUTY_SLIM_NOSE` |
| 颧骨/太阳穴/下颌/下巴 | `BEAUTY_CHEEKBONE` / `BEAUTY_TEMPLE` / `BEAUTY_JAW` / `BEAUTY_CHIN` |
| 嘴唇/酒窝/微笑 | `BEAUTY_LIPS` / `BEAUTY_RISORIUS` / `BEAUTY_SMILE` |
| 发际线/蓬松头发 | `BEAUTY_HAIRLINE` / `BEAUTY_HAIR_PUFFY` |
| 眼线/眉毛染色/唇彩/腮红 | `BEAUTY_PUPIL_LINE` / `BEAUTY_EYEBROW_DYE` / `BEAUTY_JELLY_LIPS` / `BEAUTY_BLUSHER` |
| 头/身/肩/腿/全身/臀 | `BEAUTY_HEAD_SLIM` / `BEAUTY_BODY_SLIM` / `BEAUTY_SHOULDER_SLIM` / `BEAUTY_LEG_SLIM` / `WHOLE_BODY_SLIM` |
| 妆效类型/强度 | `FRONT_MAKEUPS_TYPE` / `MAKEUPS_STRENGTH` |
| 去痣/祛斑 | `BEAUTY_NEVUS_WIPE_ENABLE` |
| 美颜模式（男/女/自定义） | `BEAUTY_MODE` |

这说明小米的美颜**不是后期 App 滤镜**，而是相机 HAL/算法引擎在 RAW/YUV 阶段就参与的“拍摄级美颜”。这也解释了为什么它的肤质、光影和肤色看起来比纯后期磨皮自然。

### 1.3 默认值：并不是“全 0”，而是按场景/性别/年龄给了一套基线

`BeautyConstant.getTsDefaultValue()`（TrueSight 路径）里写死的默认值：

| 参数 | 默认值 |
|---|---|
| 磨皮 | 40 |
| 瘦脸 | 50（新美颜模式/无妆模式 40） |
| 大眼 | 20 |
| 立体 | 50 |
| 美白 | 30（仅女性新美颜模式） |
| 妆效 | 50（部分区域 30 或 0） |
| 头型收窄 | 20 |
| 妆效预设 | 80 |

另外：

- `DEFAULT_SMOOTH_LEVEL = 60`（部分区域 30）
- `DEFAULT_SHRINK_FACE_LEVEL = 15`（部分区域 40）
- `FRONT_MAKEUPS_LEVEL = 70`
- 人像模式下默认只开磨皮 40，其他为 0；但一旦用户进入美颜面板，等级 1–6 会整体缩放这套参数。

DEX 里还有按年龄/性别命名的资源：

```
face_beauty_age_alias_female_0_7
face_beauty_age_alias_female_18_30
face_beauty_age_alias_female_45_60
face_beauty_age_alias_male_18_30
...
```

以及一条日志字符串：

```
Human scene mode detected, auto set beauty level from %s to %s
```

这说明它会结合**人脸属性（性别/年龄）+ 场景检测**自动调美颜强度。用户感觉“小米自动开了美颜”，很可能就是这条链路。

### 1.4 小米美颜的实际视觉特征（用两张自拍量化）

同一时间、同一房间、前置摄像头、Portrait 模式。分析用 YuNet 在 640px 缩略图上检测人脸，再以眼睛/嘴巴关键点为基准取“脸颊+额头+鼻梁+下巴”的皮肤 ROI（排除眼/眉/唇），避免框到头发或脖子：

| 指标（脸部皮肤区） | 小米 12 | 三星 S25+ | BeautyCam | 结论 |
|---|---:|---:|---:|---|
| 皮肤亮度 L | 68.0 | 66.2 | 70.3 | 小米略亮，BeautyCam 接近 |
| 皮肤 L p10（暗部） | 57.7 | 49.8 | 58.0 | 小米抬了阴影 |
| 皮肤 a（红-绿） | 14.4 | 15.1 | 14.8 | 小米更少红 |
| 皮肤 b（黄-蓝） | 22.0 | 21.0 | 23.3 | 小米更暖 |
| 皮肤饱和度 | 97.0 | 97.8 | 98.4 | 基本一致 |
| 高频能量 hf | 1.05 | 2.17 | 0.94 | 小米少约 52% 纹理 |
| Laplacian 能量 | 6.76 | 14.14 | 5.92 | 小米少约 52% 边缘/皱纹 |
| 局部对比度 | 3.60 | 5.57 | 2.79 | 小米更平 |
| 全图 L50 | 59.2 | 49.4 | 56.9 | 小米中间调高约 10 |
| 全图 L25 | 19.2 | 20.4 | 23.1 | 暗部接近，说明不是简单提亮 |
| 全图锐度 | 33.2 | 57.0 | 41.3 | 三星锐化重得多 |

> 注意：两张照片构图/背景不同，全图指标不可直接比；脸部皮肤指标更可靠。BeautyCam 列是同一张三星原图经 App 处理后的结果。

结论：小米的“好看”主要来自四件事：

1. **磨皮更彻底**，而且连皱纹边缘也柔化了（Laplacian 约为三星的一半），不是只糊毛孔。
2. **中间调抬高**，不是整体曝光拉高（L50 +10，L25 基本不变）。
3. **肤色更少红、更暖**，饱和度基本不变。
4. **锐化克制**（33 vs 57），没有三星那种“毛孔被锐化放大”的感觉。

---

## 2. 三星为什么“差一点”

三星相机也有完整的 BeautyNode：

```
com.samsung.android.camera.core2.node.beauty.BeautyNodeBase
BeautyPhotoMaker / BeautyVideoMaker
samsung.android.control.beautyFaceRetouchLevel
samsung.android.control.bodyBeautyParameters
samsung.android.control.videoBeautyFace
```

UI 里也存在这些 key：

```
BEAUTY_TAB_FRONT_PHOTO_SMOOTHNESS_LEVEL
BEAUTY_TAB_FRONT_PHOTO_EYES_LEVEL
BEAUTY_TAB_FRONT_PHOTO_TONE_LEVEL
BEAUTY_TAB_FRONT_PHOTO_AUTO_BEAUTY
BEAUTY_TAB_FRONT_PHOTO_SELECT_BEAUTY_OPTIONS
BEAUTY_TAB_BACK_PHOTO_EYES_LEVEL / TONE_LEVEL / AUTO_BEAUTY / BODY_BEAUTY
```

但在你的 S25+ 上实测：**前置 Portrait 模式的 Face 面板只暴露了 “Skin smoothness” 一个转盘**（我们抓到的 UI 层级里只有一个 `dialer_title=Skin smoothness`，当前值 4–5）。Eyes / Tone / Auto beauty 这些 key 在代码里存在，但该模式/该地区没有放出对应 UI。

进一步把两种前置模式都抓了一遍 UI 层级：

| 模式 | 顶栏入口 | 美颜面板内容 |
| --- | --- | --- |
| 前置 Photo | Filters / Flash / Motion photo / Resolution / Watermark | **没有美颜入口**（Quick controls 里也只有 Exposure / Ratio / Timer / Settings） |
| 前置 Portrait | **Face** / Lighting / Portrait effects | 只有 `Skin smoothness` 一个转盘 |

也就是说，三星不是“只开启了磨皮”，而是**只放出了磨皮这一个功能**，而且它只存在于 Portrait 模式；普通拍照模式连入口都没有。（一个未验证的例外：部分机型/地区会在检测到人脸时才显示美颜入口，抓取时前置画面里没有人脸。）

再加上：

- 三星默认肤色更中性、更冷；
- 锐化更强（全图 57 vs 小米 33）；
- Portrait 模式的虚化更重、曝光更暗；
- 没有小米那套“瘦脸/大眼/立体/妆效/发际线”的组合。

所以不是“三星磨皮算法差”，而是**它只让你调一个参数，且整体色彩/锐化风格更写实**。

---

## 3. 能不能把小米相机 App 直接装到三星？

**不能实际可用。** 原因不是“APK 能不能装”，而是它依赖一整套小米设备侧的东西：

1. **系统签名/特权权限**：`MiuiCamera` 在 `/product/priv-app`，是特权系统应用，依赖 MIUI 私有 framework 类和 signature 级权限。
2. **小米 vendor tag**：美颜参数通过 `com.xiaomi.*` 的 Camera2 vendor key 下发给 HAL。三星的 HAL 不认这些 key。
3. **小米专有算法库**：`libmialgoengine.so`、`libarcsoft_beautyshot.so`、`libMegviiFacepp` 等都在 `/vendor/lib64`，与小米的 ISP/平台绑定，且有 ArcSoft/旷视的授权限制。我们尝试 `adb pull` 时直接 `Permission denied`。
4. **相机 HAL/ISP 不同**：三星是 CameraCore2/UniHAL，小米是高通 CAMX + 小米算法引擎。美颜是在管线内部跑的，换个 HAL 就不存在。
5. **S25+ 无法方便 root/改 system**：Knox、bootloader 解锁限制，改系统会破坏 Knox/OTA。

社区里确实有“MIUI 相机移植包”，但基本都要求目标设备有兼容的小米 HAL，或者只能跑基础拍照，美颜/人像会崩。对 S25+ 不现实。

**可行的替代路线**（也是本项目选的）：不移植 App，而是在三星上做一个自己的相机 App，把“美颜管线”用 CameraX + ML Kit + 自研算法重建。这样不依赖任何厂商私有 HAL。

---

## 4. BeautyCam 的实现

### 4.1 架构

```
CameraX (front)
 ├─ Preview ──SurfaceTexture──> BeautyGLView (GLSL)
 │                                ├─ YCbCr 肤色 mask（无关键点）
 │                                ├─ 13-tap 双边近似
 │                                └─ 同款 tone 曲线
 │                                → 30fps 实时预览
 │
 └─ ImageCapture ──JPEG──> ML Kit Face Detection
                              │  face box / 5 landmarks / contours
                              ▼
                    ┌─────────────────────┐
                    │   BeautyProcessor   │
                    │                     │
                    │ 1. skin mask        │  face + neck/forehead − eyes
                    │ 2. warp (optional)  │  slim face / enlarge eyes
                    │ 3. two-scale smooth │  guided filter ×2
                    │ 4. skin tone        │  YCbCr 曲线
                    │ 5. soft focus       │  Gaussian blend in mask
                    │ 6. eyes / lips      │  brighten / warm
                    │ 7. global grade     │  midtone bell + warm WB
                    └─────────────────────┘
                              │
                              ▼
                    Bitmap → MediaStore
```

拍照请求通过 `Camera2Interop` 固定 `CONTROL_AE_ANTIBANDING_MODE = 50HZ`，在 PAL 地区的 LED 灯下减少滚动快门明暗带。如果以后要在 60Hz 地区使用，这里应改成按地区/频率自动选择。

> **排障记录：脸上的斜向暗带不是频闪。** 曾经出现过“成片脸上有一条斜的暗 stripe、原图（按住查看原图）却没有”的现象，一开始误判为灯光频闪，试过 50Hz anti-banding 和手动 20ms 曝光都无效。真正原因是 GPU 上传 mask 时的 **像素对齐**：mask 是 1 字节/像素的 `R8` 纹理，`glTexSubImage2D` 默认 `GL_UNPACK_ALIGNMENT = 4`，而 2121 宽的图像每行不是 4 的倍数，驱动会按 2124 字节步长读，于是 mask 每行右移 3 像素、每 707 行绕回一次——mask 被“剪切”成斜条纹。tone 阶段用 `1 - 0.87*m` 把 mask 的边界直接放大成一条斜的明暗带；磨皮阶段因为 guided filter 本身很平滑，所以不明显。宽度是 4 的倍数（如 1080）时不会出现，所以只在部分拍摄分辨率下复现。修复：上传 mask 前后 `glPixelStorei(GL_UNPACK_ALIGNMENT, 1)`。以后所有非 4 字节对齐的行宽上传都要注意这一点。

### 4.2 每一步在做什么

#### ① 皮肤 mask

- 用 ML Kit `FaceContour.FACE` 画人脸多边形；
- 眼睛用 `LEFT_EYE / RIGHT_EYE` 完整挖掉（保持锐利）；
- 眉毛和嘴唇只做**部分**挖洞（眉毛保留约 50% 磨皮，嘴唇保留约 40%），而不是完全排除。完全挖掉会在五官周围留下“没磨皮、没提亮”的色块/白圈，放大后非常突兀；
- 向下延伸脖子、向上延伸额头（到发际线），用 YCbCr 肤色判断排除头发、衣服、项链，再做垂直/水平淡出；
- 转成 `FloatArray`（0–1），用约 3.5% 脸宽的 box blur 羽化。

这对应小米的“人脸区域 + 五官排除”。小米用旷视关键点，我们用 ML Kit contour，思路一致；区别是小米的 3D 关键点能更精确地贴合五官，我们只能用椭圆近似，所以必须靠“部分保留 + 宽羽化”来藏边界。

#### ② 双尺度磨皮（核心）

普通双边/高斯磨皮的问题是：要么磨不掉皱纹，要么把眼睛/鼻翼糊掉。小米的做法是**频率分离 + 边缘保护**。我们用 guided filter 实现：

```
low1 = guided(guide, img, r1, eps1)   // 细尺度：毛孔
low2 = guided(guide, low1, r2, eps2)  // 粗尺度：皱纹/不均
d1 = img - low1
d2 = low1 - low2
out = low2 + d2 * (1 - 0.8·s·mask) + d1 * (1 - s·mask)
```

- `guided` 用积分图/滑动窗口 box blur，O(n)；
- `eps` 控制“多大的边缘才保留”：eps 越大越平滑；
- `mask` 让磨皮只发生在皮肤上，眼睛/嘴唇/头发不受影响；
- 两个尺度分别对应毛孔和皱纹，所以能像小米那样把 Laplacian 也降下来；
- 默认磨皮强度 `s = 0.50`，比最初的 0.8 更接近小米的纹理量（hf 0.94 vs 1.05）。

**GPU 实现**（`GpuGuidedFilter`）：成片的**磨皮 + 肤色 + 柔焦**整条链路跑在离屏 EGL/GLES 3.0 上，一次上传、一次回读。**细尺度** guided filter 在**半分辨率**计算（半径同步减半，物理窗口不变），**粗尺度**在**1/4 分辨率**计算（它本身就是一个很平滑的图像，降采样看不出来），最后在**全分辨率**做 `low2 + (low1−low2)·k2 + (ch−low1)·k1` 的细节合成，所以毛孔/皱纹的高频没有被降采样吞掉。guide 的统计量 `mean(guide)` / `mean(guide²)` 与通道无关，**每个尺度只算一次**给三个通道共用（blur shader 的 `uMode = 2`）；每个通道再算 `mean(src)` / `mean(guide·src)`（`uMode = 3`）。float 目标为 `R32F`，mask 为 `R8`。通道结果存进 `RGBA8` 工作纹理（和 CPU 版本每阶段 `store()` 的 8 位精度一致），`tone` 与柔焦的 H/V 两遍直接在这些纹理上做，中间不再回 CPU。像素用直接 `IntBuffer` 批量上传/回读（ARGB int 的字节序正好和 `GL_RGBA` 的 B,G,R,A 对应，回读时自动换回来）。设备不支持 `GL_EXT_color_buffer_float` / `GL_OES_texture_float_linear` 或任何一步失败时，自动退回 CPU 版本（逐通道重跑，保证不出现“部分通道磨了、部分没磨”）。

**按需跳过**：总开关关闭时直接返回原图；`smooth = 0` 时不算 guide、不跑 guided filter 和柔焦；`whiten = 0` 时跳过 tone；`slim`/`eyes` 为 0 时跳过对应的几何 warp，`eyes`/`lips` 为 0 时跳过五官增强。所以只开一项时只付一项的代价。

**性能优化记录**（6MP 从 ~0.93 s 压到 ~0.57 s）：

1. **mask 降到 1/4 分辨率**（`MASK_SCALE = 0.25`）。mask 本身是大幅羽化的平滑权重，全分辨率构建（`Bitmap` + `Canvas` 画 contour + `getPixels` + `FloatArray` + box blur）是 CPU 侧最贵的一步。现在在 1/4 尺寸上建，shader 采样时用双线性自动上采样；羽化半径跟着降到 1/4，物理范围不变。CPU 回退路径用 `upsampleMask()` 做同样的双线性放大。这一项把 mask 阶段从 200–460 ms 降到 ~50 ms。
2. **guide 改到 GPU 算**。guide 是整幅图的 luma，以前在 CPU 上 `FloatArray(n) { luma(pixels[it]) }`，现在多一个 `GUIDE_SHADER` pass 从已上传的 `texImgA` 直接写出 `R32F`（注意纹理里是 B,G,R,A，所以 `0.299*t.b + 0.587*t.g + 0.114*t.r`）。省掉一次全图遍历和一次 float 上传。
3. **旋转 + 镜像合成一次变换**（`MainActivity.orient()`）。以前是两次 `Bitmap.createBitmap`，现在一个 `Matrix`：先 `postRotate`，再以旋转后坐标系里的 `(pw/2, ph/2)` 为轴 `postScale(-1, 1)`（90°/270° 时宽高互换）。结果与两步完全等价，但少一次 6MP 的位图拷贝。
4. **检测分辨率 640 → 512**。ML Kit 的开销随像素数走，512 边长对 contour 精度没有可见影响。detect 从 ~300 ms 降到 ~185 ms。
5. **粗尺度 guided filter 放到 1/4 分辨率 + guide 统计量共享**。这是 GPU 侧最大的一笔：粗尺度半径 33（半分辨率）意味着 67 次采样/像素，是整条链路里最贵的 pass；它输出的本来就是低频图像，降到 1/4 分辨率（半径同步 /4）后与原图 RMSE 0.15%、2% 容差下只有 6 个像素不同。再加上三个通道本来就在重复计算同一份 guide 统计，改成一个尺度算一次。smooth+tone+soft 从 ~240 ms 降到 ~180 ms。
6. **瘦脸权重可分离**（`warp()`）。瘦脸的权重 `exp(-(nx²/1.1 + ny²/1.4))·|nx|` 是可以分离的：先按列算一次 `exp(-nx²/1.1)·|nx|`，按行算一次 `exp(-ny²/1.4)`，每个像素只剩一次乘法。原来每个像素调一次 `exp()`，一张 6MP 图约 130 万次。warp（只开瘦脸）从 ~330 ms 降到 ~220 ms，数值完全一致。

实测 6MP 分阶段（磨皮 50 / 美白 50 / 大眼 30）：`detect≈185 mask≈50 warp≈110 smooth+tone+soft≈180 eyes≈15 lips≈10 encode≈5`，合计 ~0.57 s。不启用瘦脸/大眼时 warp 整段跳过，约 0.44 s；瘦脸 + 大眼都开时 warp ≈285 ms，合计 ~0.74 s。现在最大头是 ML Kit 人脸检测。

#### ③ 肤色

在 YCbCr 域做，避免 LAB 转换的开销：

```
Y  += (3.5·(1−Y/255) + 0.4) · mask · whiten  // 暗部权重更大
Cr -= 3.0 · mask                             // 少红
Cb -= 0.2 · mask                             // 略暖
Cb/Cr 向 128 收缩 8%                         // 轻微降饱和
```

对应小米实测的“更亮、更少红、更暖、饱和度基本不变”。皮肤自身的提亮刻意做得很小，因为下面的全局中间调抬升已经会把皮肤抬亮；如果两处都加满，脸会比脖子白一大截。脖子/额头延伸区用同一个 `mask`，所以肤色能连续过渡。

#### ④ 柔焦

在皮肤 mask 内混入 20% 的 Gaussian blur。这是把 Laplacian 拉低的关键——纯边缘保护滤波会刻意保留皱纹边缘，而小米的观感是连边缘一起柔化。最初用 55%，实测纹理明显少于小米，降到 20% 后 hf 从 0.75 回到 0.94（小米 1.05）。

#### ⑤ 眼部/唇部

- 眼：按眼 contour 的椭圆区域提亮 Y、加对比、降饱和。不再人工点 catchlight——真实的高光已经在照片里，再画一个白点会显得假，而且预览里也没有；
- 唇：按嘴唇 contour 提 Cr、加饱和。

#### ⑥ 全局色调

```
Y += 30 · exp(-((Y−135)/70)²) · (1 − 0.87·mask)   // 中间调钟形抬升
Cb -= 0.7                                           // 暖
Cr += 0.3
```

钟形曲线只抬中间调，不抬暗部，正好匹配小米 L50 +10 / L25 不变的特征。皮肤上乘以 `(1 − 0.87·mask)`，只让皮肤吃到 13% 的抬升——否则脸会比背景和脖子亮太多。实测全图 L50 56.9（小米 59.2），皮肤 L 70.3（小米 68.0）。

#### ⑦ 瘦脸/大眼（可选）

- 瘦脸：以人脸中心为原点，对左右两侧做向内的水平位移场，权重 `exp(-(nx²/1.1+ny²/1.4))·|nx|`，再用双线性采样；
- 大眼：以眼中心为原点做径向放大，`scale = 1 − amount·0.12·(1−d)²`。

默认磨皮 50、美白 100、瘦脸 0、大眼 50；滑杆可调。每次改动写入 `SharedPreferences("beauty_settings")`（开关 + 4 个滑杆值），下次启动恢复；`BeautySettings.kt` 里的默认值只在没有存档时使用。调试导入（`--es image_path`）会临时覆盖滑杆，那条路径不写存档，避免把测试参数存成用户设置。

### 4.3 与小米的差异 / 已知限制

| 项目 | 小米 | BeautyCam |
|---|---|---|
| 美颜发生位置 | HAL/ISP，YUV 域，拍摄级 | 预览在 GPU；成片磨皮/肤色/柔焦在 GPU，五官/几何在 CPU |
| 实时预览 | 实时 | 实时（S25+ 实测 30fps） |
| 人脸关键点 | 旷视 3D + 大量关键点 | 预览用肤色 mask；成片用 ML Kit 5 点 + contour |
| 瘦脸/大眼 | 精细 3D 网格 | 简化 2D 位移场（仅成片） |
| 妆效/发际线/去痣 | 有 | 暂无 |
| 处理耗时 | 实时/毫秒级 | 预览 30fps；成片 ~0.9–1.0 秒 / 6MP |
| 依赖 | 小米 HAL + 私有库 | 纯 CameraX + ML Kit，任何 Android 都能跑 |

### 4.4 实时预览（GPU）

`BeautyGLView` 是一个 `GLSurfaceView`，同时实现 `Preview.SurfaceProvider`：

```text
CameraX Preview ──> SurfaceTexture ──> GLSL ──> 屏幕
```

- `SurfaceRequest` 给出 buffer 分辨率和 `TransformationInfo`（旋转、镜像）；
- `onFrameAvailable` 触发一次 `requestRender()`，所以是“有帧才画”，不空转；
- 顶点 shader 只做两件事：用 `SurfaceTexture` 的 transform matrix（已含传感器旋转）把显示坐标映射到 buffer；再把 quad 按 4:3 缩小做 letterbox，让预览的取景和保存下来的 3:4 照片一致。前置流在 S25+ 的 HAL 里已经左右镜像过，所以 shader 不再额外镜像，否则预览和成片会左右相反；
- 片元 shader 做三件事：
  1. **肤色 mask**：YCbCr 阈值 + `smoothstep`，再用 `r−b` 和饱和度下界排除米色墙面，用饱和度上界排除嘴唇；
  2. **13-tap 双边近似**：offset 4px / 10px，颜色差权重 `exp(−Δc²·60)`，保留眼睛/眉毛/嘴唇边缘；
  3. **同款 tone**：`Y += (3.5(1−Y/255)+0.4)·m`、`Cr −= 3.0m`、`Cb −= 0.2m`、8% 降饱和、全局 bell `30·exp(−((Y−135)/70)²)·(1−0.87m)`。
- 磨皮/美白滑杆直接改 uniform，下一帧生效；开关关闭时 shader 直接输出原图。
- 拖滑杆时 `requestRender()` 没有新相机帧可 `updateTexImage`，所以 `onDrawFrame` 会**用上一帧纹理按新参数重画一遍**（而不是先 `glClear` 再 return）。否则每次滑杆回调都会闪一帧黑，连续拖动时就是肉眼可见的闪烁。
- 实测 S25+ 1080p 预览稳定 30fps。

限制：预览没有关键点，所以**瘦脸/大眼/唇色/眼妆只在拍下的照片里生效**；肤色 mask 是逐像素的，极端光线下可能把皮肤色背景也轻微提亮。

### 4.5 拍照延迟到底花在哪

常见疑问：“小米为什么是毫秒级？我把美颜全关也要几百毫秒。”答案是——**“毫秒级”只指预览，成片从来不是毫秒级**，小米只是把它藏起来了。

预览和成片是两条完全不同的链路：

| | 预览 | 成片 |
|---|---|---|
| 预算 | 16 ms/帧（60fps） | 没有硬预算 |
| 分辨率 | 1080p ≈ 2 MP | 12–50 MP |
| 美颜做法 | 1–2 个 GPU pass，肤色 mask（不跑关键点） | 多尺度、关键点、几何变形 |
| 跑在哪 | GPU shader | ISP / DSP / 原生 C++ |

在 S25+ 上打时间戳实测（12 MP，4000×3000，**美颜开关关闭**）：

```text
shutter -> callback    794–1022 ms   ← 相机 HAL 自己（传感器读出 + 处理 + JPEG 编码）
decode 12MP JPEG         52–113 ms
orient（旋转 + 镜像）     38–40 ms
beauty                   16–17 ms    ← 关了美颜只剩 6 MP 缩放
────────────────────────────────────
总计                     ~0.9–1.2 s
```

**美颜只占 1.5%。** 所以关掉美颜几乎感觉不到变化：瓶颈在“按下快门到拿到 JPEG”这 0.8–1 秒。

**还有一个隐藏大头：JIT 预热。** 同一个进程里连拍 4 张，第一张和后面几张差了一倍多（6MP，磨皮 50 / 美白 50 / 瘦脸 50 / 大眼 30）：

| | detect | mask | warp | smooth+tone+soft | 合计 |
| --- | --- | --- | --- | --- | --- |
| debug，第 1 张 | 189 | 57 | 251 | 202 | **733 ms** |
| debug，第 4 张 | 30 | 9 | 123 | 61 | **237 ms** |
| release，第 1 张 | 69 | 15 | 69 | 180 | **358 ms** |
| release，第 4 张 | 56 | 10 | 59 | 58 | **197 ms** |

debug 包是 debuggable 的，ART 只做 verify、靠 JIT 边跑边编译，所以第一张照片有一大半时间在解释执行/编译热循环。**release 包会被 AOT 预编译，第一张直接从 733 ms 降到 358 ms**（`cmd package compile -m speed` 对 debuggable 包无效，别指望它）。所以日常使用应该装 release 包。

剩下的第一张 vs 第 N 张差距（358 → 197 ms）是 GPU 那条链的 CPU 侧（`IntBuffer` 上传/回读、GL 命令提交）还没被 JIT 编译，可以通过启动时跑一次小图预热再省掉，但收益不如换 release 包大。

试过 `CAPTURE_MODE_MINIMIZE_LATENCY`：794 ms vs `MAXIMIZE_QUALITY` 的 916 ms，只快 ~120 ms，代价是少帧合成降噪，不值得换，所以保持 `MAXIMIZE_QUALITY`。

小米/三星自家相机快的原因：

1. **处理发生在 HAL 内部**，不在 App 层。ISP 在硬件里做 HDR/降噪/锐化，DSP 做美颜，全程原生代码——没有 Java 对象、没有 `Bitmap`、没有多余拷贝。第三方 App 拿不到这条私有通路，那 0.8 秒基本绕不开。
2. **美颜作用在降采样后的工作图上**（通常 2–4 MP），最后把原始高频细节层贴回去。
3. **UI 在掩盖延迟**：按快门瞬间立刻显示**预览帧**当作占位图，快门动画同步播放，真正的高质量成片在后台处理完再替换。三星相机也是这样——按下后先看到图，半秒后画面“变”成处理后的成片。

我们原来的做法是：HAL 出 JPEG → 解码 → 旋转 → 处理 → 再编码，每一步都是全分辨率往返，中间还弹一个“处理中”遮罩，把 1 秒完整暴露给用户。

优化方向（按收益排序）：

1. **消除感知延迟（收益最大，几乎零成本）——已实现**。按快门时立刻把预览最后一帧显示成结果占位图，处理完再换成成片。实现：`BeautyGLView.snapshot()` 在 `onDrawFrame` 里、交换缓冲之前用 `glReadPixels` 把当前帧读回（`GL_RGBA` + `copyPixelsFromBuffer` 到 `ARGB_8888` 正好对上 Android 的内存字节序），垂直翻转后按 `quadScale/quadOffset` 裁掉 letterbox；`MainActivity.capture()` 拿到后立刻显示在结果层上并撤掉转圈遮罩，成片好了再替换。用一个 `captureToken` 防止晚到的占位图盖掉新结果。实测按下快门后 **~0.35 s 就有图**，而原来要等 HAL 的 0.8–1 s。三星相机也是这个行为：先看到图，半秒后画面“变”成处理后的成片。
2. **显示用缩略图**：把 6 MP 的 `Bitmap` 直接塞给 `ImageView`，首帧要上传 24 MB 到 GPU；显示用 1–2 MP 的副本，保存时才用全图。
3. **省掉像素级旋转（~40 ms）**：ML Kit 的 `InputImage` 支持 `rotationDegrees`，可以在传感器方向上检测，最后保存时写 EXIF 方向标记、显示时用 `Matrix`。
4. **降低处理分辨率**：6 MP → 4 MP，美颜从 ~620 ms 降到 ~400 ms，检测从 ~190 降到 ~130，画质损失很小（6 MP 本身也是降采样来的）。
5. **YUV 直出**：让 `ImageCapture` 输出 `YUV_420_888`，省掉解码的 50–110 ms，但保存要自己编码 JPEG，收益一般。

### 4.6 下一步可以做什么

1. **预览也做几何美颜**：`ImageAnalysis` 每 N 帧跑一次 ML Kit，把 face box/landmarks 传给 shader，做瘦脸/大眼；或直接用 MediaPipe FaceMesh。
2. ~~**拍照也走 GPU**~~：已完成——双尺度 guided filter、`tone`、`softFocus` 已迁到同一个离屏 GLES 3.0 管线（见 ②），6MP 全开从 ~4–6 秒降到 **~0.57 秒**（见 4.5）。剩下的眼部/唇部增强、几何 warp、无脸时的全局调色仍在 CPU。
3. **CPU 性能**：只对 Y 通道做双尺度，Cb/Cr 做单尺度（CPU 回退路径）。
4. **更强的人脸网格**：MediaPipe FaceMesh（468 点）做瘦脸/大眼/唇形，效果会比现在的 2D 位移自然。
5. **HDR/夜景**：CameraX 的 `Camera2Interop` 或 `NightMode`；三星的夜景算法不对外开放。
6. **视频美颜**：`CameraX VideoCapture` + GL 实时管线。
7. **妆效/去痣**：用语义分割（头发/皮肤/嘴唇）+ 局部 LUT。

---

## 5. 构建与运行

**日常使用请装 release 包。** debug 包是 debuggable 的，ART 只做 verify、靠 JIT 边跑边编译，第一张照片会比 release 慢一倍（见 4.5）。release 用 debug keystore 签名，可以直接 `adb install`：

```bash
cd BeautyCam
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.chen3feng.beautycam android.permission.CAMERA
adb shell am start -n com.chen3feng.beautycam/.MainActivity
```

开发时用 debug 包（改代码后增量构建快）：

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 本机 SDK 里装了 `android-37.2`，它的 `source.properties` 写着 `AndroidVersion.ApiLevel=37.2`。AGP 8.9/8.10 自带的 lint 用 `toInt()` 解析这个字段，会直接抛 `NumberFormatException: For input string: "37.2"`（debug lint 和 `lintVitalRelease` 都中招）。项目已升级到 **AGP 8.13.2 + Gradle 8.13**，新 lint 支持这种小版本号，`lintDebug` 与 `assembleRelease` 都能正常跑。

调试导入一张已有照片（不经过相机）：

```bash
adb push your.jpg /data/local/tmp/test.jpg
adb shell run-as com.chen3feng.beautycam cp /data/local/tmp/test.jpg files/test.jpg
adb shell am start -n com.chen3feng.beautycam/.MainActivity \
  --es image_path /data/user/0/com.chen3feng.beautycam/files/test.jpg
```

可选的调试参数（都不写入持久化设置）：`--ei enabled 0/1`、`--ei smooth/whiten/slim/eyes 0-100`、`--ei repeat N`（同一张图连续处理 N 次，用来看 JIT 预热后的耗时）。

> 注意：直接读 `/sdcard/Android/data/...` 在 Android 16 上会被 scoped storage 拦住；用 `run-as` 拷到应用内部 `files/` 再传路径最稳。

## 6. 代码索引

| 文件 | 内容 |
|---|---|
| `app/src/main/java/com/chen3feng/beautycam/BeautyProcessor.kt` | 成片美颜管线（检测/肤色/柔焦/五官） |
| `app/src/main/java/com/chen3feng/beautycam/GpuGuidedFilter.kt` | 成片 GPU 管线：离屏 GLES 3.0 磨皮 + 肤色 + 柔焦 |
| `app/src/main/java/com/chen3feng/beautycam/BeautyGLView.kt` | 实时预览 GPU 美颜（GLSL） |
| `app/src/main/java/com/chen3feng/beautycam/MainActivity.kt` | CameraX、拍照、保存、对比 |
| `app/src/main/java/com/chen3feng/beautycam/BeautySettings.kt` | 参数 |
| `app/src/main/java/com/chen3feng/beautycam/BeautyMath.kt` | 与 Android 无关的纯算法（box blur、双线性、肤色判定等），可直接在 JVM 上测 |
| `app/src/test/java/com/chen3feng/beautycam/BeautyMathTest.kt` | JUnit 单元测试 |
| `../analysis/analyze.py` | 两张自拍的量化对比 |
| `../analysis/beautify.py` | Python 原型（调参用） |
