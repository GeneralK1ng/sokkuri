# OpenCC 参考克隆

`OpenCC/` 是上游仓库在仓库根目录下的检出，列在 `.gitignore` 里，不提交，也不要修改。它有四个用途：行为的规范、`tools:dictgen` 生成词典的输入、`tools:upstream-diff` 构建对照的源码、golden 语料的出处。发布产物不依赖它，`./gradlew build` 与发布流程都不读它。

## 锁定

锁定只记录在一处：`sokkuri-runtime/src/commonTest/resources/testcases/UPSTREAM` 的 `Commit:` 行。打包的词典、配置与 golden 语料都由这个提交生成并已提交；克隆只是随时可以按锁重新取得的工作副本。

## 检出与检查

- `./gradlew fetchOpencc` 把克隆检出到锁定的提交，效果同 `git submodule update --init`：克隆不存在时按提交浅取；已存在时只在缺少该提交时拉取；然后以分离头指针检出。跟踪文件有改动的克隆不会被移动。
- `checkOpenccClone` 在克隆不在锁定提交或有改动时失败。`:tools:dictgen:run`、`:tools:dictgen:checkDictionaries` 与 `:tools:upstream-diff:run` 都依赖它，避免把克隆的版本差异误报成 Sokkuri 的问题。用 `--opencc-dir` 指向别处时，可以用 `-x checkOpenccClone` 跳过。
- `upstream-diff` 缓存的 cmake 构建记录对应的克隆提交，克隆换了提交就自动重建。
- CI 的 `checkout-opencc` 调用同一个 `fetchOpencc`。
- 依赖克隆的测试按 `OPENCC_DIR` 找克隆，自己不检查锁定；CI 在运行它们之前已用 `checkDictionaries` 检查过同一个克隆。

## 推进锁定

按顺序：

1. 改 `UPSTREAM` 的 `Commit:` 与 `Date:` 行。
2. `./gradlew fetchOpencc`。
3. `./gradlew :tools:dictgen:run`。
4. 按 `UPSTREAM` 里的命令刷新 golden 语料，并更新其中的用例计数。
5. 带 `OPENCC_DIR` 运行测试，再跑两种口径的 `upstream-diff`。
6. 全部通过后一起提交。

CI 的演练任务按同样的顺序在工作区里执行。

## 为什么不用 git 子模块

需要随包发布上游源码的项目才用子模块（例如 Rust 的 `-sys` crate）；只在开发与验证时用上游、产物生成后提交的项目，用锁文件加按需拉取（例如 `unicode-segmentation` 按固定的 Unicode 版本下载数据）。Sokkuri 属于后者。锁文件已经在 git 里记下了提交，子模块在可复现性上没有额外好处，反而带来 `git pull` 不更新子模块、`commit -a` 误改指针等麻烦。

也否决了：

- 把上游整体或部分放进仓库：几十 MB 源码永久进入历史，而 `upstream-diff` 仍要完整源码，最后两套机制并存。
- 下载 GitHub 的源码压缩包并校验 sha256：GitHub 不保证压缩包字节稳定，校验和会无故失效；按提交经 git 拉取本身就按内容寻址。
