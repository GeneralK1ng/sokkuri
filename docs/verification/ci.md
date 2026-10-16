# 持续集成

三个工作流（`ci.yml`、`upstream.yml`、`release.yml`）与两个 composite action（`setup-build-env`、`checkout-opencc`）。

## 原则

- **日常门禁就是发布门禁**：`ci.yml` 与 `release.yml` 跑同一条 `./gradlew build`。主干为绿即可发布，不设第二套标准。ABI 校验 `checkKotlinAbi` 挂在 `check` 下，一并生效。
- **对齐结论可复现**：对齐检查只针对 `UPSTREAM` 文件锁定的上游提交。追踪上游最新提交由演练任务负责，它失败只提示，不阻断任何流程。
- **跳过不能冒充通过**：依赖参考克隆的测试在没有 `OPENCC_DIR` 时报告为 skipped。对齐层设置 `OPENCC_DIR` 运行它们，并检查报告里没有 skipped。
- **环境只定义一次**：JDK、Gradle、Android SDK 与 Kotlin/Native 缓存都在 `setup-build-env`。取得 OpenCC 由 `checkout-opencc` 调用根构建的 `fetchOpencc` 完成，与本地是同一份实现。

## 分层

| 层 | 工作流与任务 | 触发 | 内容 | 失败的含义 |
|---|---|---|---|---|
| 1 构建门禁 | `ci.yml` 的 `Build gate` | push 到 `main`、所有 PR、手动 | `./gradlew build --continue`：三端测试（含 `.sok` 回放语料）、tools 测试、ABI 校验 | 阻止合并 |
| 1′ 读取器对拍 | `ci.yml` 的 `sok reader` | 同上 | 安装锁定版本的 Rust，运行 `./gradlew sokConformance`，见 [sok-reader.md](sok-reader.md) | 两个读取器有分歧，或 Rust 侧有缺陷。暂不阻止合并，稳定后再定 |
| 2 上游对齐 | `upstream.yml` 的 pinned 任务 | 每周一、手动、发布前 | 按 `UPSTREAM` 取得 OpenCC；golden 语料逐字节比对；`checkDictionaries`；带 `OPENCC_DIR` 运行依赖克隆的测试并确认没有跳过；两种 tofu 口径的 `upstream-diff`；配置比对 | 阻止发布 |
| 3 发布 | `release.yml` | 推送 `v*` tag | tag 与 `VERSION_NAME` 一致；先跑第 2 层；再 `build` 并 `publishToMavenCentral` | 不上传 |
| 演练 | `upstream.yml` 的 head 任务 | 每周一、手动 | 上游最新提交偏离锁定时，在工作区里演练一次推进锁定：重新生成词典、换入新的 golden 语料，再跑测试、两种口径的比对、配置比对与 Rust 审计；摘要列出推进会改变哪些资源 | 只提示上游有变化 |

## 实现要点

- 全部任务跑在 macos-latest（iOS 模拟器只能在 macOS 上运行）；只读 `gradle.properties` 的版本校验跑在 ubuntu-latest。第 2 层需要的 cmake、clang++ 与 python3 镜像里已有。
- 第 2 层的测试任务加 `--rerun`：环境变量不是任务输入，构建缓存可能复用没有克隆时的 skipped 结果。
- `upstream-diff` 的两种口径分成两个步骤，因为 Gradle 不接受空的 `--args`。
- OpenCC 按提交浅取，约 50 MB；它的 cmake 构建不缓存，首次约 14 秒，之后每种口径约 3 秒。
- 缓存：Gradle 由 `setup-gradle` 负责；`~/.konan` 以 `gradle/libs.versions.toml` 为键，只在编译 Apple target 的任务中恢复；Android SDK 以组件清单为键，改 `android-compileSdk` 时要同步改键与 sdkmanager 参数。
- JDK 用 Azul Zulu 21，与 `gradle/gradle-daemon-jvm.properties` 一致，否则 Gradle 每次另下一份 JDK。
- `gradle/actions/setup-gradle` 停在 v5：v6 把缓存功能移入专有组件，升级就要接受 Gradle 的使用条款，这需要项目决定，不是普通的版本升级。其他 action 用最新主版本。
- `shell: bash` 自带 pipefail，所以 `gradlew … | tee` 保留 Gradle 的退出码；也因此不能用 `yes |` 安装 SDK，`yes` 收到 SIGPIPE 会让步骤失败。
- 失败时上传测试报告与比对日志，保留 14 天。`GITHUB_TOKEN` 只有 `contents: read`，checkout 不保留凭据，发布凭据只出现在 `release.yml` 的发布步骤。
- 第 1 层在 PR 有新提交时取消旧的运行，`main` 上的每次运行都保留。第 1 层不设路径过滤：它是必需检查，被过滤掉的 PR 永远等不到结果。发布不取消。
- 演练的产物只留在 CI 工作区；推进锁定仍由人完成，步骤见 [opencc-clone.md](opencc-clone.md)。
- 公开仓库连续 60 天没有活动时，GitHub 会停用定时工作流，需要在 Actions 页面重新启用。

## 需要手动的仓库设置

在 `main` 的分支保护规则里，把 `Build gate` 设为必需检查。工作流文件无法声明这一点。
