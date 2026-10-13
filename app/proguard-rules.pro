# 职责：app 模块混淆规则，保留高德 SDK 与 Room/Compose 相关类，避免 release 崩溃

# ---------------- 高德地图/定位/搜索 SDK ----------------
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-keep class com.amap.location.** { *; }
-keep class com.amap.ams.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
-dontwarn com.amap.location.**

# 官方定位 SDK（AMap_Location_V11.x）内部引用了可选的高精度 GNSS 融合模块，
# 该模块并未打包在定位 SDK 内，属于“运行时按需加载”的可选依赖，
# R8 会因此报 Missing class，这里按官方建议抑制该警告。
-dontwarn com.amap.ams.gnss.GnssSoftLocator

# 高德 SDK 内部使用的反射与序列化
-keep class com.amap.api.location.AMapLocation { *; }
-keep class com.amap.api.location.AMapLocationClient { *; }
-keep class com.amap.api.location.AMapLocationClientOption { *; }
-keep class com.amap.api.fence.** { *; }
-keep class com.amap.api.maps.** { *; }
-keep class com.amap.api.services.** { *; }

# ---------------- Room ----------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------- Kotlin 协程 ----------------
-dontwarn kotlinx.coroutines.**

# 保留行号，便于线上崩溃定位
-keepattributes SourceFile,LineNumberTable
