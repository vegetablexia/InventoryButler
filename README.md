# Inventory Butler · 物品管家

给 Minecraft 的物品栏加上一套泰拉瑞亚风格的**管理工具**：**一键整理**、**垃圾桶**、
**收藏锁定**、**归位标记**，外加会跟着操作变形的**像素鼠标指针**。

- 目标版本：Minecraft **26.2**（Fabric）
- 依赖：Java ≥ 25、Fabric Loader ≥ 0.19.3、Fabric API
- 运行环境：客户端 + 服务端（`environment: *`，联机时两端都要装）
- 协议：MIT

> **名字的由来**：这四个功能合起来就是一位管家做的事 —— 帮你收拾房间（整理）、
> 把不要的拿去倒掉（垃圾桶）、把贵重品锁进保险柜（收藏）、记住每件物品自己的位置（归位）。
> mod id 为 `inventorybutler`，配置文件在 `config/inventorybutler.json`。

---

## 一、功能一览

| # | 功能 | 默认操作 | 一句话说明 |
| --- | --- | --- | --- |
| 1 | **一键整理** | 鼠标中键 / R | 只整理主背包 27 格，快捷栏默认不动；静默执行不弹提示 |
| 2 | **垃圾桶** | Ctrl+左键 / Delete / 按钮 | 点一下销毁，再点一下取回 —— 一次撤销，不是二次确认弹窗 |
| 3 | **收藏锁定** | Alt+左键 | 金色星标跟着物品走：丢不掉、删不掉、整理不动它 |
| 4 | **归位标记** | T | 给格子定一个「家」：物品离开后半透明占位，回来时优先住回这一格 |
| 5 | **垃圾桶按钮** | 点 GUI 右下角的按钮 | 固定在 GUI 右下角、快捷栏正下方，32×27 的五层内凹包边；手上拿着收藏物时显示危险态 |
| 6 | **像素指针** | 按住 Alt / Ctrl | 指针变金色星星 / 垃圾桶（16x16 硬边点阵），抬手前就知道点下去是哪种操作 |
| 7 | **界面内提示** | 自动 | 轻提示画在 GUI 顶上方居中（原版底纹 + 渐隐，队列最多 4 条），界面开着也能看见 |

所有按键都能在 `选项 → 控制 → 按键绑定 → 泰拉式物品栏快捷键` 分类里改。

---

## 二、按键与操作

| 操作 | 效果 |
| --- | --- |
| **鼠标中键**（默认） | 一键整理 —— 只动主背包 27 格，快捷栏 9 格保持你的摆位（`sortIncludeHotbar: true` 可改） |
| **R**（默认，第二绑定） | 同上，留给用惯 R 的人 |
| **Alt + 左键** 点槽位 | 收藏 / 取消收藏（左上角出现金色星标） |
| **T**（默认）点槽位 | 打 / 取消「归位标记」 |
| **Ctrl + 左键** 点槽位 | 把这格物品丢进垃圾桶 |
| **Delete**（默认）指槽位 | 同上（键盘党路径） |
| **点垃圾桶按钮** | 光标上的物品丢进垃圾桶（原有内容被销毁） |
| **空手再点垃圾桶按钮** | 把垃圾桶里的东西**取回来** —— 相当于撤销 |
| **按住 Alt / Ctrl** | 指针变**金色星星 / 垃圾桶**，只在指着能生效的槽位时才变形 |

> **创造模式物品栏**里：中键仍归原版「复制物品」、垃圾桶按钮不画、指针图标不生效
> —— 那边有搜索框，Ctrl 是复制粘贴；右下角也本来自带「销毁物品」格。

### 收藏 vs 归位：两种标记的区别

两个互相独立的标记，可以同时存在，管的事情完全不一样：

| | **收藏**（金色星标） | **归位标记**（半透明图标） |
| --- | --- | --- |
| 按键 | Alt + 左键 | T |
| 管什么 | **不许动** | **放哪儿** |
| 具体表现 | 丢不掉、删不掉、整理时不挪窝、Shift 移不进容器 | 物品不在时半透明占位；物品回背包时优先住回这一格 |
| 存在哪里 | 挂在**物品**上的数据组件，物品去哪标记跟到哪 | 记在**格子**上（服务端按玩家保存） |

冲突时**收藏优先**：某一格既被收藏又被标记，整理时听收藏的（不挪窝）。

### 归位标记怎么用

1. 鼠标指着背包里某一格（快捷栏 0-8 或主背包 9-35）；
2. 按 **T** —— 这一格从此「属于」格里那个物品；
3. 物品离开后，格子上显示**半透明物品图标**（只画图标，无数量、无耐久条）；
4. 物品回到背包时**优先住回这一格**，触发时机有三个：
   - **Shift 快速移动**（从箱子取到背包）
   - **捡起地上的掉落物**（含 `/give`、合成产物）
   - **一键整理**（整理后它一定落在自己的预留格里）

匹配**只看物品本身**，忽略附魔、改名、耐久。取消：指着那格再按 T（空格也行）。
「一个物品只有一个家」—— 把同一物品标到另一格，旧标记自动解除。

### 收藏保护范围

| 场景 | 结果 |
| --- | --- |
| 界面关着按 Q / Ctrl+Q | 拒绝，弹提示 |
| 界面开着按 Q（THROW） | 拒绝，弹提示 |
| 点到界面外丢光标物品 | 拒绝 |
| 丢进垃圾桶（Ctrl+左键 / 按钮 / Delete） | 拒绝，弹提示 |
| Shift+左键移进容器 | 拒绝，弹提示；**从容器拿回不受限** |
| 一键整理 | 收藏物钉在原地不参与排序 |

### 垃圾桶按钮在哪

**固定在 GUI 右下角、快捷栏（最后一行物品栏）的正下方** —— 所有非创造模式的容器界面
（背包、箱子、漏斗、工作台、熔炉……）共用这一个位置，**不再跟着配方书按钮（绿书）跑**：
绿书各界面位置不同、配方书展开时还会盖住旁边的东西，而右下角这块空地在任何界面上都是空的。

按钮整体在 GUI 之外，尺寸 **32×27**（含包边），位置只依赖界面矩形：

| 关系 | 取值 | 效果 |
| --- | --- | --- |
| 槽芯左缘 | `imageWidth - 24` | 与**快捷栏第 9 格精确同列**（槽芯 `x 152..167` ↔ 快捷栏槽位 `x 152..167`） |
| 槽芯顶边 | `imageHeight + 1` | 按钮顶边的 3px 垫层接住 GUI 底边框，两块面板灰连成一片 |
| 按钮右缘 | `imageWidth - 32` | 按钮右侧的「暗灰 2px + 黑 1px」与 GUI 右缘包边**占用同样三列**，竖条纹连续 |

反推得 `buttonX = imageWidth - 32`、`buttonY = imageHeight - 3`。间距随之固定：
槽位框顶边距快捷栏槽位底边 **8px**、槽芯顶边 9px —— 都与界面尺寸无关（快捷栏恒在 `imageHeight - 24`）。

包边五层，逐层照原版容器 GUI 面板边缘：灰主体 → 投影 2px → 黑描边 1px（**顶部开口**，
那里是接驳边）→ 内凹斜面 1px → 槽芯 16×16；圆角**底角 2px 阶梯、顶角 r=0**。
七种颜色里六种取自原版容器贴图。

垃圾桶空着时显示内凹垃圾桶图标（提手 + 盖子 + 透气缝），有东西时直接把那个物品显示出来
—— 一眼就知道「取回」能拿回什么。手上拿着**收藏物**时按钮显示**危险态**：槽芯换成
`#AB7F7F` 加一个深色斜叉，提示「这物品丢不进去」—— 把服务端本来就会拒收的规则提前到悬停阶段，
不用等点下去才知道。判定条件与服务端 `TrashHandler.rejectIfProtected` **逐字一致**。

### 界面内提示

所有轻提示**不再走原版动作栏**（动作栏挂在 HUD 上，容器界面打开时根本不渲染）。
现在分两条路：

- **容器界面开着**：提示画在 GUI 顶上方居中 —— **原版底纹**（底色取自
  `options.getBackgroundColor(0F)`，和原版 tooltip 同源）+ 带投影的白字，
  **渐隐**：淡入 150ms → 停留 2200ms → 淡出 400ms（`textWithBackdrop` 内部 alpha 写死，
  做不了渐隐，所以这一步是手写的 `fill` + `text`）；创造模式界面也画；
- **界面外**（如走路时按 Q）：照旧走原版动作栏。

提示**队列化**：一次只显示一条，期间来的提示排队等着（最多积压 4 条，超出丢最旧的，
宁可丢提示也不让队列无限变长）；**同一条**重复触发只刷新它的计时，不重复排队 ——
连按 Ctrl+左键拦收藏物时不会刷屏。

当前提示文案（中英双语齐全，改文案直接编辑 lang 文件）：

| 翻译键 | 中文文案 | 触发 |
| --- | --- | --- |
| `message.favorite.on` | 已收藏 | Alt+左键收藏 |
| `message.favorite.off` | 已取消收藏 | Alt+左键取消 |
| `message.favorite.protected` | 该物品已被收藏 | 收藏物被丢进垃圾桶 |
| `message.favorite.cannotDrop` | 该物品已被收藏 | 收藏物按 Q / Ctrl+Q |
| `message.favorite.cannotMove` | 该物品已被收藏 | 收藏物 Shift+点击 |
| `message.pin.on` | 已标记归位位置 | T 打标记 |
| `message.pin.off` | 已取消归位标记 | T 取消 |
| `message.sort.failed` | 背包状态异常，整理已取消 | 整理时容量预检不通过（极罕见，见下） |
| `tooltip.trash.empty` | 垃圾桶是空的 | 悬停垃圾桶按钮，且桶是空的 |
| `tooltip.trash.reclaim` | 点击取回 | 悬停垃圾桶按钮，且桶里有东西 |
| `tooltip.trash.blocked` | 该物品已被收藏，丢不进垃圾桶 | 悬停垃圾桶按钮，且光标上拿着收藏物（危险态） |

### 鼠标指针图标

按住 **Alt** 指针变金色星星，按住 **Ctrl** 变垃圾桶，松手立刻变回箭头。

- **16x16 硬边像素画**：每个逻辑像素要么整块着色要么全透明，放大成 2x2 实体方块，
  无抗锯齿过渡 —— 和原版 UI 的像素味一致；
- **只在指针确实指着能生效的槽位时才换**（界面空白处、护甲格、输入框上不换）；
- **走原版自己的光标通道**（`applyCursor` TAIL → `Window.selectCursor`），不抢 GLFW：
  松手后原版下一帧自动恢复箭头，也不和输入框 I 型光标打架；
- 26.2 的 `CursorType` 没有图片光标接口，用构造器型 `@Invoker` 借私有构造器
  `CursorType(String, long)` 包装 `GLFW.glfwCreateCursor` 的句柄；
- 创造模式物品栏里不生效（那边 Ctrl 是复制粘贴）。

---

## 三、设计取舍（为什么这么做）

**收藏标记挂在物品本身，而不是槽位上。**
自定义数据组件（`inventorybutler:favorite`），物品在背包、箱子间移动甚至掉落，
标记都还在 —— 和泰拉瑞亚一致。代价：带标记的那叠**无法与普通同类物品合并**
（数据组件不同 = 不是同一种物品栈），这正是「锁定」想要的效果。

**归位标记反过来，记在「格子」上。**
要表达的是「物品走了以后这格还留着它的位置」，物品没了也得显示占位图标，
所以数据放服务端（`PlacementHandler` 里一张 `UUID -> (背包下标 -> 物品)` 的表），
客户端只收一份用于渲染的副本。

**归位只在「物品进背包」的瞬间动手，不做每 tick 强制归位。**
每 tick 吸回会让玩家连手动挪走都做不到。只插手 `Inventory.add`（拾取 / give / 合成）、
`clicked(...QUICK_MOVE...)`（Shift）和整理算法三个时机，**手动摆放永远优先**。
搬运是**整格交换**而不是「找空位放」—— 交换永不丢东西。

**半透明图标是「盖」出来的。**
26.2 的 `GuiGraphicsExtractor` 没有 alpha / 着色接口。先原样画图标，再用带透明度的
槽位底色（`0xA88B8B8B`）盖住 16x16，视觉上就是幽灵图标，且不显脏。

**大件操作全部由服务端执行。**
收藏、归位、整理、销毁都走网络包交给服务端，客户端只画界面、翻译按键成网络包
—— 联机不会出现「你这边删了、服务端还在」的鬼影，也没有复制物品的风险。

**「丢不掉」要拦两整条链路。**
26.2 的 Q 键有两条完全独立的路：界面关着走 `LocalPlayer/ServerPlayer.drop(boolean)`，
界面开着走 `AbstractContainerMenu.clicked(..., THROW, ...)`，后者与 `drop` 无关。
两条路各拦一道，服务端全部兜底（防绕过）。同理，画在 GUI 外的按钮「点到界面外」
会把光标物品丢出，所以按下与配对的 `mouseReleased` 必须**一起吞掉**。

**整理绝不丢东西：先算清，再落盘。**
整理分两步 —— 池子内容和预留格归属全部先在内存里算出来，确认放得下之后才动背包。
原理上池子必然放得下（合并只会减少占用格数），但「预留了归位标记、物品却不在背包里」
的空格会白占一格容量；所以池子比普通空格多的时候，**先牺牲还没用上的幽灵预留格**，
也不让物品消失（幽灵只是一条提示，物品才是玩家的东西）。真要碰上异常物品栈
（比如单格数量超过 `maxStackSize`）导致彻底放不下，就整单放弃、背包一点不动，
并提示「背包状态异常，整理已取消」—— 而不是把多余的物品悄悄扔掉。

---

## 四、配置

首次启动生成 `config/inventorybutler.json`（重启游戏生效）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `favoriteEnabled` | `true` | 收藏功能总开关 |
| `favoriteProtectFromDrop` | `true` | 关掉后收藏物可以被丢弃，只保留星标 |
| `favoriteProtectFromTrash` | `true` | 关掉后收藏物也能丢进垃圾桶 |
| `favoriteProtectFromSort` | `true` | 关掉后整理会挪动收藏物 |
| `pinEnabled` | `true` | 关掉后 T 键与归位逻辑、半透明图标全部关闭 |
| `cursorIconsEnabled` | `true` | 关掉后指针不再变形 |
| `trashEnabled` | `true` | 关掉后垃圾桶按钮和 Ctrl+左键失效 |
| `sortEnabled` | `true` | 整理功能总开关 |
| `sortIncludeHotbar` | `false` | `true` 时快捷栏 9 格一起参与排序 |

> 老版本的鼠标/滚轮手势配置键（`gesturesEnabled` 等）已废弃，启动时发现会自动清理重写。

---

## 五、多人游戏

- **单人**：开箱即用（内置服务端自动接管）。
- **联机 / 服务器**：**两端都要装**。服务端是垃圾桶与归位标记的权威：
  客户端按键 → 发包 → 服务端校验并执行 → 同步回客户端渲染。
  没装服务端时数据组件不被识别。
- 垃圾桶与归位标记都存在**服务端内存**里：玩家退出（或换世界）即清空，
  重进需要重标。想跨会话保留可改造为挂到玩家持久化数据（Fabric `AttachmentRegistry`）。

---

## 六、构建

需要 JDK 25（Minecraft 26.1 起要求 Java 25）。`build.gradle` 里声明了工具链，
所以**正常不用手动设 `JAVA_HOME`**：

```gradle
java {
	toolchain { languageVersion = JavaLanguageVersion.of(25) }
}
```

```bash
./gradlew build        # 产物在 build/libs/inventorybutler-1.0.2.jar
./gradlew runClient    # 开发环境启动游戏
```

### 工具链排查：报「找不到 Java 25」时

先跑 `./gradlew -q javaToolchains` —— 它会列出 Gradle **实际认到的所有 JDK 及检测来源**。
两个已知原因：

**① Windows 上 Gradle 不扫描 `C:\Program Files\Java`。** 它只看注册表
`HKLM\SOFTWARE\JavaSoft\JDK` 和当前 JVM（`javaToolchains` 会标注 `Detected by: Windows Registry`）。
免安装解压的 JDK —— GraalVM 这类 —— 不会往注册表写条目，于是检测不到。
在**用户级** `~/.gradle/gradle.properties` 里显式列出路径即可（别写进工程的
`gradle.properties`：那是机器相关配置，会污染仓库且对别人无效）：

```properties
org.gradle.java.installations.paths=C:/Program Files/Java/graalvm-jdk-25.0.2+10.1
```

**② foojay 自动下载可能失败。** 它先向 `api.foojay.io` 问地址，再发 **HEAD** 到
`github.com/adoptium/...`。若本机走代理且代理拦 HEAD，会报
`Could not HEAD 'https://github.com/adoptium/temurin25-binaries/releases/download/...'`。
这种情况下自动下载指望不上，只能靠 ① 的本地路径。

> ⚠️ `settings.gradle` 里 foojay 解析器的版本必须 **>= 1.0.0**。0.9.0 引用了 Gradle 9
> 已删除的 `JvmVendorSpec.IBM_SEMERU`，一旦工具链真的被解析就会炸：
> `Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field 'IBM_SEMERU'`
> （0.9.0 在「没有 toolchain 声明」时看不出问题 —— 解析器根本不会被触发。本项目踩过这个坑。）

### 提交前先跑一遍 Mixin 静态自检

下面那个 `@Shadow` 父类字段的坑，编译器抓不到、启动才崩。所以启动游戏之前先用
`tools/verify_mixins.py` 对着 MC jar 核一遍：

```bash
python tools/verify_mixins.py --jar <path/to/client.jar> \
       --javap "C:/Program Files/Java/<jdk>/bin/javap.exe"
```

它读 `*.mixins.json` → 定位 mixin 源文件 → 把每个 `@Shadow` 字段 / 方法与每个 `@Inject`
的目标描述符拿 `javap -p -s` 逐条核对。退出码非 0 就说明有会崩的地方。

### ⚠️ 关于 Mixin 的一个坑

Mixin 0.8.x 的 `@Shadow`「方法沿继承链找、字段不找」，而注解处理器会沿继承链找
—— 所以 shadow 父类字段是「编译 0 警告、启动即崩」
（本项目踩过：`@Shadow Minecraft minecraft` 声明在父类 `Screen` 上）。

### 下载被拦时

- 证书错误（PKIX）：把代理根证书导入 JDK 信任库（`keytool -importcert`）；
- Gradle 本体慢：`gradle-wrapper.properties` 的 `distributionUrl` 换腾讯镜像。

---

## 七、代码结构

```
src/main/java/com/inventorybutler/
├── InventoryButler.java          入口：注册数据组件、网络包、服务端接收器
├── ModComponents.java               inventorybutler:favorite 数据组件
├── FavoriteStacks.java              收藏状态读写
├── ModConfig.java                   JSON 配置（含废弃键自清理）
├── mixin/
│   ├── ServerPlayerDropMixin.java   服务端：拦收藏物丢弃（界面关着那条路）
│   ├── AbstractContainerMenuMixin.java  服务端：拦 THROW / 点界面外 / Shift 移进容器；
│   │                                    Shift 后触发归位
│   └── InventoryAddMixin.java       服务端：物品进背包后触发归位
├── network/
│   ├── FavoriteTogglePayload.java   C2S：切换收藏
│   ├── SortPayload.java             C2S：一键整理
│   ├── TrashPayload.java            C2S：丢进垃圾桶 / 取回
│   ├── PinPayload.java              C2S：切换归位标记
│   ├── MessagePayload.java          S2C：界面内轻提示（翻译键）
│   ├── TrashSyncPayload.java        S2C：垃圾桶内容镜像
│   └── PinSyncPayload.java          S2C：归位标记全量同步
└── server/
    ├── FavoriteHandler.java         收藏切换
    ├── PlacementHandler.java        归位标记（切换 / 归位 / 同步）
    ├── InventorySortHandler.java    整理算法（收藏物钉住、归位物回预留格）
    └── TrashHandler.java            垃圾桶（含取回/撤销）

src/client/java/com/inventorybutler/client/
├── InventoryButlerClient.java    客户端入口：按键绑定、收包、断线清理
├── ClientFeedback.java              提示分流：容器界面内 → ScreenMessage；界面外 → 动作栏
├── ScreenMessage.java               界面内轻提示：队列（最多积压 4 条）+ 渐隐，一次显示一条
├── mixin/
│   ├── AbstractContainerScreenMixin.java  鼠标/键盘事件、按钮命中、提示与按钮绘制、指针上报
│   ├── LocalPlayerDropMixin.java    客户端：拦收藏物丢弃（界面关着那条路）
│   ├── GuiGraphicsExtractorMixin.java    applyCursor TAIL 换指针图标
│   └── CursorTypeInvoker.java       借 CursorType 私有构造器（图片光标）
├── CursorIcons.java                 星星 / 垃圾桶指针：16x16 点阵逐像素生成 + GLFW 光标
├── InventoryOverlay.java            星标 / 幽灵图标 / 垃圾桶按钮（内凹边框）绘制
├── SlotSections.java                槽位分区（哪些格子允许删除、哪些是玩家背包）
├── ClientTrashState.java            垃圾桶客户端镜像
└── ClientPinState.java              归位标记客户端镜像

tools/
└── verify_mixins.py                 启动前的 Mixin 静态自检（见「构建」一节）
```

---

## 八、26.2 API 备忘（实测核对，非猜测）

升级版本编译报「找不到符号」时先来这里对一眼：

| 旧写法 | 26.2 写法 |
| --- | --- |
| `ResourceLocation` | `net.minecraft.resources.Identifier` |
| `ClickType` | `net.minecraft.world.inventory.ContainerInput` |
| `Player.displayClientMessage(Component, boolean)` | `ServerPlayer.sendSystemMessage(Component, boolean)`；客户端本地提示走自绘（见 ScreenMessage） |
| `Minecraft.screen`（public 字段） | **不再是 public**，挪到了 `mc.gui.screen()`；更推荐让界面绘制时自行上报状态 |
| `Screen.hasShiftDown()` 等静态方法 | 已删除。鼠标事件用 `MouseButtonEvent.hasAltDown()/hasShiftDown()/hasControlDown()` |
| `Screen.mouseClicked(double, double, int)` | `mouseClicked(MouseButtonEvent, boolean)` |
| `Screen.mouseReleased(double, double, int)` | `mouseReleased(MouseButtonEvent) -> boolean` |
| `Screen.render(GuiGraphics, ...)` | `extractRenderState(GuiGraphicsExtractor, int, int, float)` |
| `GuiGraphics` | `GuiGraphicsExtractor`（`item()` / `fill()` / `blitSprite()` / `centeredText()`）；**没有 alpha/着色接口**，半透明只能「画完再盖一层半透明底色」 |
| `AbstractContainerScreen.renderSlot(...)` | `extractSlot(GuiGraphicsExtractor, Slot, int, int)` |
| `KeyBindingHelper` | `KeyMappingHelper`（`api.client.keymapping.v1`）；分类用 `KeyMapping.Category.register(Identifier)` |
| `PayloadTypeRegistry.playC2S()/playS2C()` | `serverboundPlay()/clientboundPlay()` |
| `ClientPlayNetworking` | 挪到 `api.client.networking.v1` |
| 换鼠标指针 | 官方通道：`Gui` 每帧末调 `applyCursor(window)` → `Window.selectCursor(CursorType)`，按引用去重。**注入 `applyCursor` TAIL 直接调 `selectCursor`**，恢复由原版自动完成 |
| 图片鼠标指针 | `CursorType` 私有构造器 `CursorType(String, long)`（第二参 = GLFW 句柄），构造器型 `@Invoker("<init>")` 借出；句柄来自 `GLFW.glfwCreateCursor` |
| 配方书按钮（**本项目已不用**，仅备查） | `AbstractRecipeBookScreen.getRecipeBookButtonPosition()`（protected，返回**绝对屏幕坐标**）；按钮精灵 `RecipeBookComponent.RECIPE_BUTTON_SPRITES.get(enabled, hovered)`，尺寸 20x18；位置 背包 `(leftPos+104, topPos + h/2 - 22)`、工作台 `(leftPos+5, h/2-49)`、熔炉 `(leftPos+20, h/2-49)`。垃圾桶按钮早期依赖它，现已改锚 GUI 右下角，`AbstractRecipeBookScreenAccessor` 已删除 |

**坐标系约定**：`extractSlots` 里 `slot.x`/`slot.y` 已是 GUI 局部坐标（调用前已
`translate(leftPos, topPos)`）；点击判定用原版 `isHovering`（内部自己加 `leftPos/topPos`）。

**`mouseClicked` 返回 `true` 不代表处理完**：松开时 `mouseReleased` 一定会被调到，
涉及光标物品的场景必须把配对的 release 一起拦（按钮画在 GUI 外时尤其如此）。

**「物品进背包」的两条路**：捡起/`/give`/合成经过 `Inventory.add`；
Shift 快移**不经过**（走 `moveItemStackTo`），要挂在 `clicked` 的 TAIL。
手动点击搬运故意不管 —— 玩家的手动摆放应当被尊重。

---

## 九、已知限制

- 收藏物不能与普通同类物品合并（设计如此，见「设计取舍」）。
- 垃圾桶、归位标记都只活在服务端内存里：退出/断线即清空，重进需重标。
- 归位标记只认玩家自己的背包 0-35（快捷栏 + 主背包）；箱子、护甲、副手格按 T 无效。
- 归位不把散落的同类物品聚拢 —— 只负责「把某一份送回它的预留格」。
- 整理默认只动主背包 27 格（护甲、副手、合成格永不参与）。
- 创造模式物品栏：不启用中键整理、不画按钮、指针图标不生效（原版行为优先）。
- 服务器也需要安装本 mod（数据组件与服务端校验都依赖它）。

---

## 十、往旧版本移植

当前代码针对 **26.2**（不混淆、无需 Yarn mappings、Java 25）。要点：

| 目标版本 | 需要改动 |
| --- | --- |
| **1.21.11 及以下（有混淆）** | Loom 插件换回 `net.fabricmc.fabric-loom-remap` + Yarn mappings；`java.release` 降 21；GUI 输入事件签名按目标版本重对 |
| **1.21.x** | `Identifier` 叫 `ResourceLocation`；「丢不掉」拦 `Player.drop(boolean)`（当时还在 `Player` 上） |
| **1.20.5 及以下** | 无数据组件：收藏改写 NBT `{inventorybutler:{Favorite:1b}}`；按钮底图换老 `blit` 贴图方式 |

与版本强相关的只有三处：**数据组件（收藏存储）、GUI 事件签名、GUI 绘制 API**。
业务逻辑（排序、归位规则、垃圾桶语义）版本无关。
