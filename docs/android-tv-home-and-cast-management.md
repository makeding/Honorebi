# Android TV HOME 与 Chromecast 共存方案

更新日期：2026-08-02  
目标设备：两台 Xiaomi `MiTV_MZTU0`（Android 14，`river`）

## 目标

- `com.google.android.apps.tv.launcherx` 保持启用，使 Chromecast / MediaShell 能访问其账户与设备模式 Provider。
- HOME 始终进入 `com.beeregg2001.Honorebi/com.beeregg2001.komorebi.MainActivity`。
- 用户不看到、不使用 Google TV Launcher 主界面。
- 优先使用无需恢复出厂、可回滚的方案。

## 2026-08-02 最终落地状态

这轮试验的最终结论不是杀死或冻结 LauncherX，而是：

> 保留 LauncherX 包及其后台 Provider，只禁用能够显示 Google TV 桌面和账户拦截页的 Activity。

### 223 当前状态

目标：`172.24.0.223:5555`。

| 项目 | 当前状态 |
| --- | --- |
| Device Owner | `HonorebiDeviceAdminReceiver`，用户 0 的 Device Owner |
| 用户 | 只剩用户 0；Google 账户数为 0 |
| HOME | 固定解析到 Honorebi `MainActivity` |
| LauncherX 包 | 保持启用，后台 Provider 可以继续工作 |
| LauncherX 网络 | 未封禁；最终没有采用断网方案 |
| MediaShell | 保持启用 |
| Chromecast | `_googlecast._tcp` 与 8009 端口已恢复发现 |
| AirPlay | Honorebi 在开机时重发 MediaTek ready/setup 信号并延迟重试 |

实机验证命令：

```sh
adb -s 172.24.0.223:5555 shell cmd device_policy list-owners
adb -s 172.24.0.223:5555 shell \
  cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME
adb -s 172.24.0.223:5555 shell dumpsys account
```

2026-08-02 的结果：

```text
User 0:
  admin=com.beeregg2001.Honorebi/
        com.beeregg2001.komorebi.HonorebiDeviceAdminReceiver
  DeviceOwner

com.beeregg2001.Honorebi/com.beeregg2001.komorebi.MainActivity

Accounts: 0
```

LauncherX 保持启用，但以下三个组件由 Honorebi 的 Device Owner 策略禁用：

```text
com.google.android.apps.tv.launcherx.home.HomeActivity
com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity
com.google.android.apps.tv.launcherx.profile.core.AccountVerificationActivity
```

223 上还禁用了以下一次性设置包，避免重新进入 Google TV / 小米初始设置流程：

```text
com.google.android.tungsten.setupwraith
com.xiaomi.android.tvsetup.partnercustomizer
com.google.android.partnersetup
com.mitv.setup
```

这些设置包与 LauncherX 的 Cast Provider 不是同一层：禁用它们不等于禁用整个 LauncherX。

### 215 当前状态

目标：`172.24.0.215:5555`。

215 于 2026-08-02 复活后按 223 的最终方案完成配置。执行前确认它只有用户 0、账户数为 0、没有现有 owner，因此无需清账户、删除用户或恢复出厂。

当前实机状态：

| 项目 | 当前状态 |
| --- | --- |
| Device Owner | `HonorebiDeviceAdminReceiver`，用户 0 的 Device Owner |
| HOME | 固定解析到 Honorebi `MainActivity` |
| LauncherX | 包保持 enabled；三个 UI Activity 已禁用 |
| setup 包 | 与 223 相同的四个一次性设置包已禁用 |
| MediaShell | 包为 enabled，`CastReceiverService` 与 `WargService` 正在运行 |
| Chromecast | 本机 `*:8009` 正在监听；局域网 `_googlecast._tcp` 可发现 |
| AirPlay | Honorebi 日志确认重放 ready/setup-complete 信号 |

Device Owner 登记结果：

```text
Success: Device owner set to package
com.beeregg2001.Honorebi/
com.beeregg2001.komorebi.HonorebiDeviceAdminReceiver
```

最终两台 Chromecast 实例都能从局域网发现：

```text
MiTV-MZTU0-612dfb2ec83e6d8a973421caf6b0babb
MiTV-MZTU0-1346141abb6c161413fb9d51888db96b
```

### 同型号设备复刻步骤

下面以 215 为例。登记 Device Owner 前必须先确认只有用户 0 且没有账户；不满足时不要强行继续：

```sh
adb -s 172.24.0.215:5555 shell pm list users
adb -s 172.24.0.215:5555 shell dumpsys account
adb -s 172.24.0.215:5555 shell cmd device_policy list-owners
```

安装 Honorebi 并登记 Device Owner：

```sh
adb -s 172.24.0.215:5555 install -r \
  app/build/outputs/apk/release/app-armeabi-v7a-release.apk

adb -s 172.24.0.215:5555 shell dpm set-device-owner --user 0 \
  com.beeregg2001.Honorebi/com.beeregg2001.komorebi.HonorebiDeviceAdminReceiver
```

保留 Cast 依赖，并关闭一次性 setup 包：

```sh
adb -s 172.24.0.215:5555 shell pm enable --user 0 \
  com.google.android.apps.tv.launcherx
adb -s 172.24.0.215:5555 shell pm enable --user 0 \
  com.google.android.apps.mediashell

adb -s 172.24.0.215:5555 shell pm disable-user --user 0 \
  com.google.android.tungsten.setupwraith
adb -s 172.24.0.215:5555 shell pm disable-user --user 0 \
  com.xiaomi.android.tvsetup.partnercustomizer
adb -s 172.24.0.215:5555 shell pm disable-user --user 0 \
  com.google.android.partnersetup
adb -s 172.24.0.215:5555 shell pm disable-user --user 0 \
  com.mitv.setup
```

启动 Honorebi。它会写入 persistent HOME，并通过 Device Owner API 禁用 LauncherX 的三个 UI Activity：

```sh
adb -s 172.24.0.215:5555 shell am start \
  -n com.beeregg2001.Honorebi/com.beeregg2001.komorebi.MainActivity \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME
```

最后验证：

```sh
adb -s 172.24.0.215:5555 shell cmd device_policy list-owners
adb -s 172.24.0.215:5555 shell \
  cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME
adb -s 172.24.0.215:5555 shell ss -ltn | rg ':8009'
```

### 当前架构

```text
HOME 键
  └─> Honorebi MainActivity

LauncherX 包（保留）
  ├─> AccountModeContentProvider
  ├─> DeviceModeContentProvider
  ├─> UserModeContentProvider
  └─> MediaShell / Chromecast 所需后台依赖

LauncherX 桌面 Activity（禁用）
  ├─> HomeActivity
  ├─> VanillaModeHomeActivity
  └─> AccountVerificationActivity
```

## 已确认的设备行为

1. 禁用整个 `com.google.android.apps.tv.launcherx` 后，MediaShell 会反复报找不到以下 Provider：
   - `com.google.android.apps.tv.launcherx.coreservices.account.AccountModeContentProvider`
   - `com.google.android.apps.tv.launcherx.coreservices.DeviceModeContentProvider`
   - `com.google.android.apps.tv.launcherx.coreservices.UserModeContentProvider`
2. 启用 LauncherX 后，Chromecast 的 `_googlecast._tcp` 广播恢复。
3. Android 的 HOME role 已是 Honorebi，但 LauncherX 的系统 Activity 仍以 `priority=2` 抢先解析 HOME。
4. AccessibilityService 可以收到 `KEYCODE_HOME`，但返回 `true` 后，小米系统策略仍会启动 LauncherX 的 `HomeActivity`。
5. Android 14 普通应用只能通过 `killBackgroundProcesses()` 杀自己的进程。无障碍权限不会提升应用 UID。
6. `adb shell am kill com.google.android.apps.tv.launcherx` 可以工作，但会造成 LauncherX coreservices 和 MediaShell 重建，不适合作为首选 HOME 路由机制。

## 方案优先级

### A. Android 14 Device Policy Management role（试过，未采用）

Android 14 引入了细分的 `MANAGE_DEVICE_POLICY_*` role 权限。当前电视满足以下条件：

- 存在 `android.app.role.DEVICE_POLICY_MANAGEMENT`。
- `android.permission.MANAGE_DEVICE_POLICY_LOCK_TASK` 的保护级别是 `internal|role`。
- `DevicePolicyManager.addPersistentPreferredActivity()` 允许 Device Owner、Profile Owner，或持有该权限的应用调用。
- 非 Device Admin 调用者可以把 `admin` 参数传 `null`。

这条路径理论上可以让 Honorebi 写入持久 HOME 规则，从而绕过 LauncherX 的 intent priority，同时让 LauncherX 包和 Provider 继续运行。

2026-08-02 在 223 清理账户和用户之前，实机不能通过 ADB 加入该 role。Honorebi 已满足 AOSP `roles.xml` 要求的三个 Activity handler，但 role 仍是 `static=true`。当时用户 0 已有 Google 账户，并存在普通用户 10，所以：

```text
cmd role add-role-holder ...
Error: see logcat for details.
java.util.concurrent.ExecutionException: java.lang.RuntimeException: Failed
```

直接执行 `pm grant` 也会被拒绝：

```text
java.lang.SecurityException: Permission
android.permission.MANAGE_DEVICE_POLICY_LOCK_TASK is managed by role
```

结论：不清除现有账户/用户且没有 root/system 签名时，这条路线不可用。后来 223 清除了账户和附加用户，但没有继续依赖临时 role，而是直接登记为 Device Owner。

更重要的是，这条路线并不构成 Device Owner 的稳定替代方案：两者都要求设备先回到没有普通账户和附加用户的 provisioning 状态。通过 ADB 设置的 Device Policy Management role 又不会跨重启保留，因此最终采用 Device Owner。

#### Honorebi 实现

Manifest 权限：

```xml
<uses-permission android:name="android.permission.MANAGE_DEVICE_POLICY_LOCK_TASK" />
```

Role qualification 还要求应用提供以下三个、受
`android.permission.LAUNCH_DEVICE_MANAGER_SETUP` 保护的 Activity handler：

- `ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE`
- `ROLE_HOLDER_PROVISION_MANAGED_PROFILE`
- `ROLE_HOLDER_PROVISION_FINALIZATION`

Honorebi 不负责 Android Enterprise provisioning，因此这些 handler 只返回
`Activity.RESULT_CANCELED`；它们不会修改设备所有权或创建工作资料。

Android 14 上，在获得角色权限后，Honorebi 每次启动都会调用：

```kotlin
val filter = IntentFilter(Intent.ACTION_MAIN).apply {
    addCategory(Intent.CATEGORY_HOME)
    addCategory(Intent.CATEGORY_DEFAULT)
}

val honorebiHome = ComponentName(
    context,
    MainActivity::class.java
)

devicePolicyManager.addPersistentPreferredActivity(
    null,
    filter,
    honorebiHome
)
```

如果角色没有授予，应用会保持普通 HOME 应用行为，不修改系统策略。

#### ADB 角色登记

```sh
adb shell cmd role set-bypassing-role-qualification true
adb shell cmd role add-role-holder --user 0 \
  android.app.role.DEVICE_POLICY_MANAGEMENT \
  com.beeregg2001.Honorebi 0
```

应用写入规则后验证：

```sh
adb shell cmd role get-role-holders --user 0 \
  android.app.role.DEVICE_POLICY_MANAGEMENT

adb shell dumpsys package com.beeregg2001.Honorebi \
  | rg MANAGE_DEVICE_POLICY_LOCK_TASK

adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME
```

期望 HOME 解析结果：

```text
com.beeregg2001.Honorebi/com.beeregg2001.komorebi.MainActivity
```

#### 回滚

必须在 Honorebi 仍持有角色权限时先执行：

```sh
adb shell am start \
  -n com.beeregg2001.Honorebi/com.beeregg2001.komorebi.MainActivity \
  -a com.beeregg2001.Honorebi.action.CLEAR_DEVICE_POLICY_HOME
```

该动作让 Honorebi 调用：

```kotlin
devicePolicyManager.clearPackagePersistentPreferredActivities(
    null,
    context.packageName
)
```

然后移除角色：

```sh
adb shell cmd role remove-role-holder --user 0 \
  android.app.role.DEVICE_POLICY_MANAGEMENT \
  com.beeregg2001.Honorebi 0

adb shell cmd role set-bypassing-role-qualification false
```

#### 尚需验证

- Google TestDPC 文档说明，通过 ADB 设置的 Device Policy Management role 在重启后不会保留。
- 需要实测已经写入的 persistent preferred HOME policy 是否随角色一起被清理。
- 如果仅角色丢失而 HOME policy 保留，此方案可直接使用。
- 如果两者都丢失，可由外部 ADB 守护在开机后重新登记角色并让 Honorebi 重写策略。

### B. 传统 Device Admin（不够用）

普通手机设置中的“设备管理应用 / Device admin apps”对应 `DeviceAdminReceiver` 与 `ACTION_ADD_DEVICE_ADMIN`。用户可以在不恢复出厂的情况下启用它，但它不是 Device Owner 或 Profile Owner。

传统 Device Admin 主要提供锁屏、密码和擦除等旧式策略能力；许多能力已经弃用。它不能：

- 调用 `addPersistentPreferredActivity()` 固定 HOME；
- 禁用其他系统包的单个 Activity；
- 绕过 Android 14 的跨应用进程终止限制；
- 直接管理 LauncherX 的用户 0 组件状态。

因此，仅把 Honorebi 登记为传统“设备管理应用”不能解决当前问题。

### C. Profile Owner / 工作资料（通常不适用）

手机上的 Island、Shelter 等应用通常创建 managed profile，并成为该 profile 的 Profile Owner。它们能冻结、隐藏或挂起工作资料内的应用，但权限边界只覆盖受管理的 profile。

当前 Chromecast、MediaShell、LauncherX 和 Honorebi 都运行在用户 0。新建工作资料后成为 Profile Owner，并不能直接接管用户 0 的 HOME 或冻结用户 0 的 LauncherX。

只有把整套电视应用迁移到一个受管理的完整用户/资料中才可能利用该路径，迁移成本和兼容风险高于 Device Owner，不作为当前优先方案。

### D. Device Owner（223 已采用）

Device Owner 可以可靠调用 `addPersistentPreferredActivity()`，是 Android Enterprise 官方的自定义 HOME 方案。

223 最初不能设置 Device Owner：

- `device_provisioned=1`
- `user_setup_complete=1`
- 用户 0 已有 Google 账户
- 还存在用户 10

本轮没有恢复出厂，而是先移除 Google 账户和附加用户，使设备重新满足 provisioning 条件。随后 Honorebi 成功登记为用户 0 的 Device Owner。当前 `pm list users` 只剩用户 0，`dumpsys account` 显示 0 个账户。

最终使用的是 Honorebi 内置的 `HonorebiDeviceAdminReceiver`，由它负责：

- 写入 persistent preferred HOME；
- 禁用 LauncherX 的两个 HOME Activity；
- 禁用 LauncherX 的账户验证 Activity；
- 在 Honorebi 再次启动时检查并修复策略状态。

对其他不满足 provisioning 条件的设备，最稳妥的通用流程仍然是恢复出厂：

正确顺序是：

1. 恢复出厂。
2. 在初始设置完成、登录 Google 账户之前 provision DPC 为 Device Owner。
3. Device Owner 建立后，再添加 Chromecast / Google TV 所需的 Google 账户。

Google 账户阻止的是“事后成为 Device Owner”，不会撤销已经建立的 Device Owner。Android 官方 provisioning 流程本身也把添加用户账户放在 DPC 成为 Device Owner 之后。

长期维护时仍可考虑让 DPC 与播放器 APK 分离：

- DPC 只负责 persistent preferred HOME policy。
- Honorebi 保持普通应用，方便日常更新。
- LauncherX 包保持启用，不隐藏、不挂起，只管理其 UI Activity。
- DPC 提供明确的清除 HOME policy 与恢复系统桌面的操作。

如果其他设备必须恢复出厂，事前需要备份：

- Honorebi 设置与后端地址；
- 已安装应用清单；
- Jellyfin / Google 等账户状态；
- ADB 与开发者设置；
- 无障碍服务配置。

### E. Accessibility + Shizuku（运行时兜底）

当前 AccessibilityService 能做到：

- 观察 LauncherX HOME 窗口；
- 收到 HOME 键事件；
- 立即启动 Honorebi；
- 用 Accessibility overlay 遮住 LauncherX 的短暂画面。

但无障碍服务本身不能杀其他应用。Shizuku 以 shell UID 运行时可以执行 `am kill`，适合做故障恢复或资源清理，不适合替代持久 HOME policy。

电视已经安装 Shizuku 13.6，但当前仅启动过配对服务；真正的 `shizuku_server` 尚未确认运行。非 root Shizuku 通常需要在每次重启后重新启动。

223 已经建立 Device Owner 和 persistent HOME，因此无障碍守卫与 Shizuku 不再是主路径，只保留作异常状态兜底。

## 没有采用的方案

### 整包禁用、卸载或定时冻结 LauncherX

不可采用。LauncherX 不只是桌面 UI，还承载 MediaShell 需要的设备模式和账户模式 Provider。整包冻结会让 Chromecast 的依赖一起死亡；定时解冻只能制造周期性重建。

### 定时 kill LauncherX

`am kill` 可以杀进程，但 coreservices 与 MediaShell 会被迫重建。它适合作为临时诊断命令，不适合作为 HOME 路由。

### 撤销 LauncherX 的网络权限

没有采用。`INTERNET` 是安装时普通权限，不能像危险运行时权限一样通过普通 `pm revoke` 稳定撤销。利用 netpolicy/firewall 虽可另外实现断网，但可能同时伤害 Cast 所需的设备状态和本地服务发现，而且 223 当前没有对 LauncherX 设置网络阻断。

### 卸载 LauncherX 更新

本轮研究过，但没有保留为最终状态。223 当前仍使用 `/data/app` 中的新版 LauncherX，`/product/priv-app/TVLauncherXPrebuilt` 只是系统 base。最终控制面是组件级 Device Policy，而不是固定到旧版 LauncherX。

## AirPlay 开机自愈

清理 Google TV 设置链后，MediaTek AirPlay 偶尔不会在开机后进入 Ready。Honorebi 已声明：

```xml
<uses-permission android:name="com.mediatek.permission.AirPlay.BroadCast" />
```

`AirPlayStartupRepairReceiver` 接收 `BOOT_COMPLETED` 和 `LOCKED_BOOT_COMPLETED`，由 `AirPlayStartupRepairManager` 重放 MediaTek AirPlay ready/setup-complete 信号，并安排一次延迟重试。相同修复也会在 Honorebi `MainActivity` 启动时执行。

这条逻辑只修复 MediaTek AirPlay 启动竞态，不会禁用或重启 Google Cast。

## 当前风险与后续观察

- Chromecast 已验证能够被 `_googlecast._tcp` 发现并访问，但还需要数小时到数天的持续观察，确认原先按小时死亡的问题完全消失。
- LauncherX 或系统 OTA 可能增加新的 HOME/验证 Activity，或重新启用现有组件；Honorebi 启动时的策略自愈需要继续保留。
- Device Owner 已建立后，再添加 Google 账户通常不会撤销所有权；但账户可能重新激活 Google TV 设置流程，添加前应先备份当前组件状态。
- 215 与 223 目前都已采用相同策略，但 OTA、LauncherX 更新和后续账户变化仍可能让两台机器产生状态漂移，应分别验证。

## 推荐试验顺序

1. 对 223 保持当前 Device Owner + 组件级禁用方案，不再整包 kill/freeze LauncherX。
2. 持续观察 Chromecast 数小时到数天，并记录 MediaShell、LauncherX Provider 和 mDNS 状态。
3. 系统或 LauncherX 更新后，重新验证 HOME resolver 和三个 Activity 的 enabled 状态。
4. 对新设备先清理账户和附加用户；仍不能登记 Device Owner 时再恢复出厂 provision。
5. Accessibility 与 Shizuku 只作为回滚期和异常状态兜底。

## 官方参考

- [DevicePolicyManager.addPersistentPreferredActivity](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#addPersistentPreferredActivity(android.content.ComponentName,%20android.content.IntentFilter,%20android.content.ComponentName))
- [Dedicated devices: custom Home apps](https://developer.android.com/work/dpc/dedicated-devices/cookbook)
- [Build a device policy controller](https://developer.android.com/work/dpc/build-dpc)
- [Google TestDPC](https://github.com/googlesamples/android-testdpc)
- [Android 14 device owner provisioning requirements](https://source.android.com/docs/compatibility/14/android-14-cdd#3911_device_owner_provisioning)
- [Android 14 process-kill behavior](https://developer.android.com/about/versions/14/behavior-changes-all#kill-background-processes)
