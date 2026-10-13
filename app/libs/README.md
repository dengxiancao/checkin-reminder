# app/libs —— 第三方 SDK 放置目录

本目录**不包含**高德定位 SDK 的 jar。高德 SDK 的再分发受其《服务条款》约束，
公开仓库需由使用者自行下载并遵守其许可，因此该二进制已在根目录 `.gitignore` 中排除。

## 编译前必须放入

| 文件 | 放入位置 | 来源 |
| --- | --- | --- |
| `AMap_Location_V11.3.101*.jar` | 本目录（`app/libs/`） | 高德开放平台 → 下载中心 → Android 定位 SDK |
| `libapssdk.so` | `app/src/main/jniLibs/arm64-v8a/` 与 `app/src/main/jniLibs/armeabi-v7a/` | 同上，定位 SDK 压缩包内附带 |

缺这两个文件时，编译会报找不到 `com.amap.api.location.*` 符号。

> 地图选点所用的 `com.amap.api:map3d` 等依赖由 Gradle 自动下载，无需手动放置。

## 版本

本项目验证使用的定位 SDK 版本为 **V11.3.101**（`AMap_Location_V11.3.101_20260924`）。
使用其他版本时请留意 API 兼容性。
