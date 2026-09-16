# PulseKit iOS 第三方插件审计契约

## 目标

PulseKit 在应用交给第三方重签名、打包或注入广告 SDK 后，向后台提供一条可复核的运行时证据链：

1. 最终安装包实际包含和加载了哪些第三方二进制；
2. 哪个二进制在什么时间调用了哪个敏感系统 API；
3. 它申请或使用了什么系统权限，调用前后授权状态如何变化；
4. 它向哪个服务器、哪个路径发送了什么类别和结构的数据；
5. 监控本身是否正常安装，是否存在 hook 缺失、被替换或停止上报。

客户端只陈述事实。允许、可疑、违规等结论由服务端规则产生，以便不发新版 SDK 也能调整策略。

## 证据对象

### 1. BuildArtifact

每个随包分发或运行中加载的 Mach-O 记录：

- 镜像名、bundle 内相对路径、Mach-O UUID；
- `__TEXT` 段 SHA-256（排除会因重签变化的 code-signature blob）；
- Team ID、签名证书摘要、关键 entitlements；
- framework bundle id、版本、PrivacyInfo.xcprivacy 摘要；
- 首次加载时间、加载地址和所属 App build。

后台将运行时清单与我方发布前生成的基线清单比较，新增、替换和缺失的镜像分别形成证据。只上报文件名不足以发现“同名替换”。

### 2. SensitiveCall

每次敏感行为至少记录：

- 稳定事件 ID、设备/安装/会话/App build；
- Objective-C class、selector、规范化方法签名；
- 发起调用的 Mach-O UUID、镜像指纹和调用栈指纹；
- 调用次数、首次/末次发生时间；
- 参数的类别、长度和结构，不上传 token、口令、联系人内容、剪贴板原文等值；
- 权限调用前后的授权状态以及系统最终回调结果。

首批覆盖范围：剪贴板、IDFV/IDFA、定位、相册、相机、麦克风、通讯录、蓝牙、通知、Tracking Transparency、日历/提醒事项、运动与健康数据、Keychain 枚举和 WebView 注入接口。

每一种 IMP 参数签名单独实现并测试。签名不匹配会破坏调用栈，不能用一个通用函数指针覆盖所有 selector。

### 3. NetworkFlow

一个网络请求的证据需要把“谁、向哪里、发送什么”关联起来：

- 调用模块及其 Mach-O 指纹；
- 请求 ID、开始/结束时间、耗时、成功/失败；
- scheme、host、显式端口、path、HTTP method；
- query 字段名、header 字段名、Content-Type；
- body 类型、字节数、JSON/form 字段路径和字段类型；
- 响应状态码、响应字节数、重定向目标；
- TLS 主机、协议版本和证书公钥摘要（可取得时）；
- 本地数据流匹配结果，例如 `idfv -> ads.example.com`、`clipboard -> api.example.com`。

PulseKit 不把完整 query、Authorization/Cookie 值或原始 body 上传到服务器。SDK 在设备内暂存敏感 API
返回的数据，并在出站请求的 URL、header 和 body 中做匹配；匹配后只上报来源类别、编码方式、位置、
长度和使用每次安装密钥生成的 HMAC。这样后台能证明某类数据发生了流转，但无法从遥测库还原原值。

`NSURLSessionTask.resume` 只能覆盖 Foundation 网络栈。还需分别覆盖 Network.framework、CFNetwork、
WKWebView 和 POSIX `connect/send/sendto`；后台必须展示每条证据来自哪个采集器，不能把“没有观察到”
表述成“没有发生”。

### 4. PermissionEvidence

权限证据由静态声明和运行时行为组成：

- Info.plist 中所有 `*UsageDescription`；
- App entitlements、后台模式、URL scheme、Associated Domains、Keychain group；
- PrivacyInfo.xcprivacy 中声明的 collected data、tracking、accessed API reason；
- 权限请求 API 的调用模块、请求时间、请求前状态、用户选择后的状态；
- 已声明但未使用、已使用但缺少声明、重签后新增声明分别告警。

### 5. MonitorHealth

每次启动上报监控版本、预期 hook 数、实际 hook 数、缺失 selector 和每个 hook 当前 IMP 指纹。定时复核
IMP，发现被后装 SDK 覆盖时形成 `monitor_hook_replaced` 事件并重新建立可安全串联的 hook。服务端对某个
已上线 build 长时间没有健康心跳单独告警，避免把“SDK 被移除”误判为“一切正常”。

## 数据和性能边界

- 默认模式只采集元数据、结构、HMAC 和指纹；原始隐私数据不离开设备。
- hook 热路径只做有界快照并写入固定容量队列；解析 JSON、计算摘要和符号化放到 PulseKit 工作线程。
- 所有字符串、body、调用栈和队列都有硬上限，并记录截断和丢弃计数。
- PulseKit 自己的上报连接必须带内部标记并从网络审计中排除，防止递归采集。
- 证据事件使用稳定 ID，服务端按事件 ID 幂等落库，并保留客户端时间与服务端接收时间。

## 完成顺序

1. **网络元数据闭环**：method、字段名、body 类型/长度/结构、状态码、耗时、调用模块；覆盖 data、upload、download task。
2. **权限闭环**：相册、相机、麦克风、通讯录、定位、ATT，记录调用与最终状态。
3. **制品指纹与签名基线**：Mach-O UUID、`__TEXT` 摘要、签名/entitlements/Privacy Manifest，后台做 build diff。
4. **本地数据流匹配**：敏感来源到网络目的地的 HMAC 证据，不上传原值。
5. **扩大网络覆盖**：WKWebView、Network.framework、CFNetwork、POSIX socket，并明确每种采集器的覆盖边界。
6. **防绕过与健康检查**：hook 完整性复核、缺失心跳、SDK 被移除或停止上报的告警。

## 当前实现与缺口

当前版本已经实现：随包镜像清单和后加载镜像、Objective-C runtime 类/实例方法/类方法清单及 ABI
type encoding、每个类的方法摘要和截断状态、调用模块归因、按真实 ABI 区分的零至三参数
Objective-C hook、定位/相册/相机/麦克风/通讯录/通知/ATT/日历/提醒事项/健康权限入口、剪贴板、
IDFV/IDFA、UserDefaults、Cookie 和 WebView 桥接/脚本调用，以及 `NSURLSessionTask.resume` 目的地。
UserDefaults 的 key/value、Cookie 内容、WebView 脚本文本和权限回调内容均不采集。监控健康事件会同时
上报期望 hook 数、实际 hook 数、待加载 selector 和已生效 selector，避免把“未监控”误判为“未发生”。
此外已经覆盖最终 App 的 UsageDescription、
后台模式、URL scheme 数量、主包/Framework Privacy Manifest 的来源及具体 collected data、tracking、
tracking domains、required-reason API 声明，以及服务端按模块和行为聚合告警。PulseKit 自身的动态
Framework 也携带 PrivacyInfo.xcprivacy，声明实际使用的 UserDefaults 原因与采集类别。每个随包加载的
Mach-O 会单独形成 `module_artifact` 事件，携带 App 内相对路径和由 dyld 内存直接读取的 `LC_UUID`；
服务端把 UUID 存为独立字段，因此同名二进制替换可被区分。主程序的 Code Signature 也会被有界解析，
上报 entitlement 键与已审查的关键值（Team ID、application identifier、推送环境、Associated Domains、
App Groups、Keychain Groups、get-task-allow）；不调用私有 SecTask API，也不读取整份可执行文件。

当前网络事件包含 method、去掉参数值的目的地、query/header 字段名、Content-Type 和 body 大小；
敏感调用同时携带最近调用方符号（符号未被裁剪时）、相对 Mach-O 基址的调用点偏移和归一化调用栈
指纹，并携带最多 8 帧的可读调用路径（镜像、符号、镜像内偏移）。原始进程地址不会上报；发布包即使
裁剪了符号，也能以镜像 UUID + 偏移交给对应 dSYM 还原。同一插件通过不同内部调用路径触发同一个
系统 API 时会形成独立证据。
尚没有响应状态、耗时和 body 字段结构，也未覆盖 Network.framework、CFNetwork 和 POSIX socket。
权限事件目前证明“哪个模块调用了申请入口”，尚没有安全替换 completion block 来记录最终授权结果；
蓝牙、运动和 Keychain C API 也尚未覆盖。模块清单已有 UUID，但还没有
`__TEXT` 代码指纹和签名证书摘要；
没有本地数据流匹配；监控也尚未覆盖纯 Swift、C/C++ 和低层网络调用。这些
边界必须在后台可见，不能把当前版本描述为完整审计。

“内部方法实现分析”分成两个边界：运行时能列出已注册到 Objective-C runtime 的类与方法，并证明在
敏感系统边界上实际执行的方法和调用路径；静态制品分析负责纯 Swift/C/C++、导入符号、未加载镜像以及
代码段是否被替换。PulseKit 不会对任意 Objective-C 方法做通用 swizzle：未知 IMP 签名会破坏调用栈，
全量拦截也会显著改变宿主性能。完整实现仍需要在最终 IPA/xcarchive 进入我方环境后增加独立的 Mach-O
静态分析流水线，并以 LC_UUID/代码指纹把静态结果与运行时证据关联。
