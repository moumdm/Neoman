# Newman — 本地漫画阅读器（Android 原生应用）

Newman 是一款**完全离线**的本地漫画阅读 App（Kotlin + Jetpack Compose + Material 3），专为手机竖屏与平板横屏阅读设计，不联网、不收集数据、不消耗任何流量。

## 功能总览

### 格式支持（全部本地直读，无需提前解压）
| 格式 | 支持方式 |
|---|---|
| ZIP / CBZ | 流式按需读取，大文件顺序翻页零二次解压 |
| RAR / CBR | junrar 8.1.1（RAR4 + RAR5），首次打开预处理到私有缓存 |
| 7Z / CB7 | commons-compress 1.28.0，同上 |
| PDF | 系统 PdfRenderer 渲染，首次打开预渲染后零延迟 |
| EPUB | 解析 container.xml → OPF → spine，按阅读顺序取图 |
| MOBI / AZW / AZW3 | PDB 记录魔数扫描（**实验性**，复杂 KF8 可能不全） |
| JPG / PNG / WebP / BMP / GIF | 单文件或文件夹直读 |

### 阅读体验
- **三种阅读方向**：右→左（日漫）/ 左→右 / 上→下（条漫连续滚动，无分页切割感）
- **方向与布局按书记忆**：在阅读设置中选择后只对本书生效，不受全局设置影响；选「跟随全局」恢复默认联动
- **单页 / 双页跨页**：平板横屏自动适配，RTL 模式右页在前
- **翻页效果**：平移 / 仿真（透视旋转近似）/ 淡入 / 直接切换
- **翻页方式**：手势滑动（左右 30% 区域点击）、音量键上下翻页、自动翻页（0~120 秒可调）、条漫自动滚动（0~3000 px/s）
- **画面**：
  - 横向翻页（单页/双页）：0.5~4 倍无级缩放（双指捏合，放大后单指拖动看细节）、双击快速放大
  - 条漫模式：**整条缩放** —— 捏合时整条漫画（所有页）等比放大，放大后横向拖动平移、纵向照常连续滚动，双击 1x/2x 切换
  - 白边自动裁剪、0/90/180/270 旋转、横幅跨页自动拆页（拆分顺序随阅读方向）
- **细节**：阅读进度自动记忆（每本书）、书签标记与跳转、深浅主题 + 跟随系统、护眼暖色滤镜、自定义屏幕亮度、纯黑/深灰/纯白/米黄背景

### 书库管理
- **自动清理失效记录**：刷新书库时自动识别源文件已被删除的书并移除记录（仅移动/重命名回来可重新上架）
- **批量管理**：书架长按或菜单进入多选模式，支持全选、批量移除、批量标记已看完/未观看
- **删除源文件**：移除确认框中可勾选「同时删除源文件」（需文件夹授权含写入权限，重新导入一次即可）
- 书架滚动位置在进出阅读器后自动保留

## 环境要求
- **Android Studio**：Ladybug 2024.1.1 或更新（Koala 也可）
- JDK 17（Android Studio 自带）
- Android SDK Platform 34（Studio 会自动下载）

## 编译步骤
1. 解压本目录，用 Android Studio **Open** 打开 `ComicReader`（即本工程根目录，内含 `settings.gradle.kts`）
2. 等待 Gradle Sync 完成（首次会自动下载依赖，建议配置国内镜像，见下方常见问题）
3. 手机开启「开发者选项 → USB 调试」，连接电脑
4. 点击工具栏 **Run 'app'**（绿色三角）即可安装运行

### 命令行编译（可选）
```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug

# Release 签名包
./gradlew assembleRelease   # 需先在 app/build.gradle.kts 配置 signingConfig
```
产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用说明
1. 首页点右上角「导入文件夹」，选择存放漫画的目录（SAF 授权后可持续访问，重启不失效）
2. 书架会自动扫描 ZIP/CBZ/CBR/7Z/PDF/EPUB/MOBI 文件与纯图片文件夹（深度 3 层）
3. 也可以直接用系统文件管理器「打开方式 → Newman」打开单个漫画文件
4. 阅读中点击屏幕中间呼出菜单；底部滑条快速跳页；右上角书签图标管理书签；齿轮打开阅读设置
5. 阅读方向、单双页设置会**按书记忆**，其余为全局设置（可在书架右上角 → 设置中调整默认值）

## 架构速览
```
app/src/main/java/com/local/comicreader/
├── MainActivity.kt          # 入口、导航、音量键总线、外部文件打开
├── data/                    # 数据层：模型 / 偏好持久化 / SAF 书库扫描
├── parser/                  # 格式解析层：各格式的 PageProvider 实现
├── reader/                  # 阅读器：ViewModel、翻页引擎、缩放画布、图像处理
├── library/                 # 书架：封面网格、导入、扫描
├── settings/                # 全局设置页
└── ui/                      # 主题（深色 / 浅色 / 跟随系统）
```

## 已知限制（诚实说明）
- **MOBI 为实验性**：通过记录魔数扫描提取整页图片，对 KF8/AZW3 内部容器可能提取不全
- **仿真翻页**为透视旋转近似效果，非完整物理翻页卷曲
- RAR/7Z/PDF 首次打开需要预处理（进度提示），大文件耗时取决于设备 IO；处理后写入应用私有缓存，可在设置中一键清理
- 加密压缩包暂不支持

## 常见问题
**Q：Gradle Sync 很慢 / 失败？**
在工程根目录 `build.gradle.kts` 和 `settings.gradle.kts` 中把 `google()` / `mavenCentral()` 替换为国内镜像，例如：
```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/central") }
```

**Q：为什么没有申请任何权限？**
全部文件访问通过 Android SAF（存储访问框架）完成，授权由系统管理，无需 READ_EXTERNAL_STORAGE。

**Q：支持哪些 Android 版本？**
Android 8.0（API 26）及以上。
