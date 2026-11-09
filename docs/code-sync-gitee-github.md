# Gitee → GitHub 代码同步操作手册

> 适用仓库：`advisory`
> gitee：https://gitee.com/buile/advisory.git
> github：https://github.com/ZhuangJiang/advisory.git
> 最后核对：2026-11-09
>
> **当前状态（2026-11-09 11:05）**
>
> - gitee：`master` + `feat/advisory-platform-mvp` 两个分支，最新 `f378d45`（60 提交）✅
> - github：仅 `feat/advisory-platform-mvp`，最新 `f378d45`；**`master` 待补推** ⏳
>   原因：HTTPS 直连被墙，本地代理客户端（`127.0.0.1:62665`）已退出，`CONNECT` 返回 502。
> - 补推命令（网络恢复或改用 SSH 后一条搞定）：`git push github --all`
> - 后续增量同步按 §4

## 0. 一句话方案

**gitee 是唯一写入源（`origin`），github 是只读镜像（`github`）。** 每次往 gitee 提交后，用一条命令把同一批 commit 推到 github，历史原样复制，commit hash 一一对应，不是「复制文件」而是「搬运引用」。

## 1. 开工前的现状

| 项 | 实测值 | 影响 |
|---|---|---|
| 本地分支 | `master`（1 提交，初始设计稿）、`feat/advisory-platform-mvp`（59 提交，当前开发分支） | `master` 是 `feat` 的祖先，推 `feat` 即包含全部历史 |
| gitee 分支 | 仅 `feat/advisory-platform-mvp` | gitee 的默认分支就是这个 |
| github 分支 | 勘察时为「空仓库，无任何分支」；**现已有 `feat/advisory-platform-mvp`** | 首推无冲突，未使用 `--force` |
| 现有远程 | `origin` → gitee | 需新增 `github` |
| tag | 无 | 有 tag 时补一条 `git push github --tags` |
| 仓库体积 | 1.5 MB，最大文件 < 5 MB | 远低于 GitHub 单文件 100 MB 限制，无需 LFS |

结论：这是一次「干净的首推 + 长期增量跟随」，不涉及历史改写。

## 2. 为什么是单向同步

双向同步（有人在 github 提 PR 并合并，同时 gitee 也在提交）会让两条历史分叉，之后每次同步都要人工解冲突，且 commit hash 不再对应，`git log` 对账失效。

规则定死：

- **写**：只往 gitee 提交，只推 gitee。
- **读**：github 用于海外访问、CI、公开只读。
- **例外**：确实有人在 github 改了代码，走第 6 节的「回灌」流程人工合并，不做自动双向。

## 3. 一次性初始化（已完成，保留作换机/重建时的参考）

### 3.1 添加 github 远程

```bash
cd /e/webapps-ai/advisory
git remote add github https://github.com/ZhuangJiang/advisory.git
git remote -v
```

预期输出四行（`origin` 两条 + `github` 两条）：

```
origin  https://gitee.com/buile/advisory.git (fetch)
origin  https://gitee.com/buile/advisory.git (push)
github  https://github.com/ZhuangJiang/advisory.git (fetch)
github  https://github.com/ZhuangJiang/advisory.git (push)
```

### 3.2 首次全量推送（保留提交记录）

```bash
git push github feat/advisory-platform-mvp
```

成功标志：

```
 * [new branch]      feat/advisory-platform-mvp -> feat/advisory-platform-mvp
```

**三个不要**，每一个都会毁掉 58 条提交记录：

| 错误做法 | 后果 |
|---|---|
| 在 github 侧新建仓库时勾选「Add a README」 | 远端多一条无关根提交，首推被拒（`non-fast-forward`），只能强推 |
| 用 `--depth 1` 浅克隆再推 | 历史被压成一条 |
| 在空目录 `git init` + `git add .` + 推 | 58 条提交变成 1 条 `init`，`git log` 从此无意义 |

> 仓库已存在且若误加了 README，见 §7.3 的处理方式。

### 3.3 是否要推 `master`

`master` 只有 1 条初始提交，且是 `feat` 的祖先，其内容已在 github 的 `feat` 历史里，**技术上可以不推**。

**本仓库的实际决定：保留 `master`（两个平台对称）**。已执行：

```bash
git push origin master      # gitee：已补上
git push github master      # github：待网络恢复后补，或直接 git push github --all
```

> 规则只有一条：两边要么都有 `master`，要么都没有。单边存在会让分支列表不对称，以后 `--all` 同步时容易漏。

### 3.4 校验首推结果

```bash
git rev-parse HEAD
git ls-remote github refs/heads/feat/advisory-platform-mvp
git ls-remote origin  refs/heads/feat/advisory-platform-mvp
```

三条命令输出的 hash **必须完全相同**（首次执行时均为 `f7ff891…`）。不一致说明推的不是同一个提交。

### 3.5 确认 GitHub 默认分支

github 空仓库首次推送某分支后，GitHub 通常会把该分支设为默认分支。若网页上仍显示「默认分支 main 不存在」：

`Settings → Branches → Default branch`，改为 `feat/advisory-platform-mvp`。
**不要**为了迁就 GitHub 去改名分支——gitee 那边也会跟着变，得不偿失。

## 4. 日常同步（提交之后）

### 4.1 最省事：一条命令推两边（推荐）

配置一个 git alias，之后每次 `git sync` 等价于「推 gitee + 推 github」：

```bash
git config --global alias.sync '!f() { b=$(git rev-parse --abbrev-ref HEAD); git push origin "$b" && git push github "$b"; }; f'
```

> **本仓库已配置**（仓库级，未加 `--global`，不影响你其他项目；换机器需重配，或把 `--global` 加上）。

用法：

```bash
git add -A && git commit -m "feat(xxx): ..."
git sync
```

用了 `&&`：gitee 推失败就不会去推 github，避免两边进度错位。

### 4.2 标准两步（需要看清每一步输出时）

```bash
git push origin feat/advisory-platform-mvp    # 1. 先 gitee
git push github feat/advisory-platform-mvp    # 2. 再 github
```

### 4.3 多分支 / tag 全量镜像

分支有增减、或打了 tag 时用这条，比逐个分支写名字稳：

```bash
git push github --all
git push github --tags
```

> `--all` 会推本地所有分支（含 `master`），所以先按 §3.3 决定 `master` 的去留，保持两个平台一致。

### 4.4 不推荐：一个 remote 挂两个 pushurl

```bash
git remote set-url --add --push origin https://gitee.com/buile/advisory.git
git remote set-url --add --push origin https://github.com/ZhuangJiang/advisory.git
```

看起来更省事（`git push origin` 自动推两个），但有个坑：push 会同时更新本地的 `refs/remotes/origin/*`，当两边 hash 出现任何差异时，`origin/xxx` 到底代表哪一边就说不清了，后续 `git status`、`git log origin/xxx` 全部失真。**只在确定两边永远一致时使用**。要撤销：

```bash
git config --unset-all remote.origin.pushurl
git remote set-url --add --push origin https://gitee.com/buile/advisory.git
```

## 5. 对账：确认「真的同步了」

| 目的 | 命令 |
|---|---|
| 比最新提交 | `git rev-parse HEAD origin/feat/advisory-platform-mvp github/feat/advisory-platform-mvp` |
| 查 github 落后多少 | `git fetch github && git log --oneline github/feat/advisory-platform-mvp..HEAD` |
| 查 gitee 落后多少 | `git fetch origin && git log --oneline origin/feat/advisory-platform-mvp..HEAD` |
| 比文件树（不看历史） | `git diff --stat github/feat/advisory-platform-mvp HEAD` |
| 比 tag | `git ls-remote --tags github` |

零输出 = 已对齐。有输出 = 列出的就是还没同步的提交，直接按 §4 推一次。

## 6. 反向回灌：github 上有新提交，要回到 gitee

### 6.1 只回灌个别提交（推荐）

```bash
git fetch github
git log --oneline HEAD..github/feat/advisory-platform-mvp   # 看差哪些
git cherry-pick <sha1> <sha2>                                # 逐个摘
git push origin feat/advisory-platform-mvp
git push github feat/advisory-platform-mvp
```

### 6.2 整体合并（改动多、无冲突时）

```bash
git fetch github
git merge github/feat/advisory-platform-mvp
# 解冲突 → 提交
git push origin feat/advisory-platform-mvp
git push github feat/advisory-platform-mvp
```

回灌后 **hash 会变**（cherry-pick 产生新提交），这是正常的：内容一致、历史分叉。之后以 gitee 为准继续单向推即可。

## 7. 排错

### 7.1 `Authentication failed` / 弹窗后 403

GitHub 自 2021 年起**不接受账号密码**，必须用 PAT 或 SSH。

```bash
# 方式一：SSH（推荐，一次配好长期有效）
ssh-keygen -t ed25519 -C "zhuangjianglong@klcgis.com.cn"
# 公钥 ~/.ssh/id_ed25519.pub 粘到 GitHub → Settings → SSH and GPG keys
git remote set-url github git@github.com:ZhuangJiang/advisory.git

# 方式二：PAT（classic token，勾 repo 权限）
# 密码位置填 token，用户名填 ZhuangJiang
```

本机 `credential.helper=manager`（Git Credential Manager），首次推送会弹浏览器授权，属正常。若缓存了错误凭据：

```bash
git credential-manager-manager delete   # 或 Windows 凭据管理器里删除 git:https://github.com
```

### 7.2 `Repository not found`

三种可能：仓库名/用户名拼错、token 无该仓库权限、私有仓库未授权。先用 `git ls-remote https://github.com/ZhuangJiang/advisory.git` 单独验证，能列出分支就说明权限没问题。

### 7.3 `! [rejected] ... (non-fast-forward)`

说明 github 上已有 gitee 没有的提交（多半是建仓库时勾了 README，或有人直接改了）。

```bash
git fetch github
git log --oneline github/feat/advisory-platform-mvp    # 确认这些提交是否有价值
```

- 无价值（只是自动生成的 README / LICENSE）→ 覆盖：
  ```bash
  git push --force-with-lease github feat/advisory-platform-mvp
  ```
- 有价值 → 走 §6 回灌，别强推。

用完 `--force-with-lease` 而不是 `--force`：前者在远端被别人动过时会拒绝，避免误删他人提交。

### 7.4 `refusing to merge unrelated histories`

两个仓库各自有独立根提交（典型：github 建库时生成了 README）。首次合并时：

```bash
git pull github feat/advisory-platform-mvp --allow-unrelated-histories
```

或者按 §7.3 判断后直接强推覆盖。

### 7.5 推送卡住 / 超时

先别急着换命令，三条命令就能定性问题在哪一层：

```bash
curl -m 15 --noproxy '*' -o /dev/null -w "%{http_code}\n" https://github.com   # 直连
curl -m 5  --proxy http://127.0.0.1:<你的端口> -o /dev/null -w "%{http_code}\n" https://github.com
ssh -o ConnectTimeout=10 -T git@github.com 2>&1 | head -1
```

| 结果 | 含义 |
|---|---|
| 直连 `000` / 超时 | 443 被墙，直连这条路不通 |
| 代理返回 `502` / `000` | 代理进程已退出或节点挂了（`netstat` 里该端口只有 `TIME_WAIT`、没有 `LISTENING` 就是这种） |
| SSH 回 `Permission denied (publickey)` | **链路是通的**，只缺密钥——这不是网络问题 |

> `Permission denied (publickey)` 是好消息，说明 TCP 握手成功。真正不通表现为 `Connection timed out`。

**HTTPS 走不通时改 SSH**（本机 2026-11-09 实测：HTTPS 直连超时，而 `github.com:22` 与 `ssh.github.com:443` 均连通）：

```bash
ssh-keygen -t ed25519 -C "zhuangjianglong@klcgis.com.cn" -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub          # 整行粘到 https://github.com/settings/ssh/new
ssh -T git@github.com              # 期望：Hi ZhuangJiang! You've successfully authenticated
git remote set-url github git@github.com:ZhuangJiang/advisory.git
git push github --all
```

若 22 端口也不通，让 SSH 走 443——`~/.ssh/config`：

```
Host github.com
  HostName ssh.github.com
  Port 443
  User git
```

> 本机已生成 `~/.ssh/id_ed25519`（无密码），公钥尚待添加到 GitHub；添加后执行上面第 3 步即可验证。

如果只是想让 HTTPS 恢复，配置代理（按自己本地端口改）：

```bash
git config --global http.https://github.com.proxy http://127.0.0.1:7890
git config --global https.https://github.com.proxy http://127.0.0.1:7890
# 取消
git config --global --unset http.https://github.com.proxy
```

> 注意：若 shell 里已存在 `HTTPS_PROXY` 环境变量，即使 git 没配 proxy 也会走它；代理进程一退出，git 就会报 `CONNECT tunnel failed, response 502`。排查时用 `env | grep -i proxy` 一并确认。

**另一个可选项：SSH over 443** 的 config 同上，把 `Port 443` 保留即可。

### 7.6 提交在 GitHub 上不显示作者头像

本机提交邮箱是 `zhuangjianglong@klcgis.com.cn`。若该邮箱未加入 GitHub 账号（`Settings → Emails`），提交不会关联到你的头像，但**代码和历史完全不受影响**。介意的话把邮箱加进去，或后续用 `git config user.email` 改成 GitHub 账号邮箱。

### 7.7 大小写文件名冲突

Windows 文件系统不区分大小写，Linux 区分。若历史里有过 `Foo.java` → `foo.java` 的改名，github 上可能出现两个文件。当前仓库无此问题，新增文件时避免只改大小写。

### 7.8 误提交敏感文件

`.env`、`*.log`、`target/`、`node_modules/`、`.workbuddy/` 已在 `.gitignore` 里，正常不会被推。万一已经提交进去了，光删文件不够（历史里还在），需要 `git filter-repo` 重写历史并**强推两边**，属于高危操作，单独评估后再做。

推送前自查：

```bash
git status --short          # 推送前看清有哪些未跟踪/改动
git diff --cached --stat    # 暂存区里有什么
```

> 注意：`output/` 下的文档产物已在 `f7ff891` 中提交入库（含 .docx 二进制）。此类生成物通常不需要进版本库，建议后续加入 `.gitignore`；已提交的部分不影响同步，但会让仓库随每次生成而膨胀。

## 8. 可选：让它更自动

| 方案 | 说明 | 代价 |
|---|---|---|
| `git sync` alias（§4.1） | 已在本文档内，够用 | 无 |
| Gitee「仓库镜像管理」 | gitee 仓库设置里可配 Push 镜像直推 github，全自动 | 是否可用取决于 gitee 套餐，免费版通常只给 Pull 镜像；配之前先在设置页确认 |
| GitHub Actions 定时拉取 | 在 github 侧配 workflow，cron 从 gitee clone 后推送 | 需要在 github secrets 存 gitee 凭据，且每 5 分钟起跑一次成本不低 |
| 本地 post-push hook | `git push` 后自动推 github | hook 里再 push 有递归风险，不如 alias 直观 |

建议：先用 `git sync`，团队真的频繁到忘记推，再考虑 Gitee 镜像。

## 9. 操作检查清单

首推：

- [ ] `git remote add github …`，`git remote -v` 确认两条 push 地址
- [ ] `git push github --all`（分支多时比逐个写名字稳）
- [ ] `git rev-parse HEAD` 与 `git ls-remote github …` 的 hash 相同
- [ ] `git ls-remote --heads origin` 与 `git ls-remote --heads github` 的**分支列表一致**
- [ ] GitHub 网页能看到 60 条提交、分支名正确、默认分支已设置

日常每次提交后：

- [ ] `git sync`（或手动两条 push）
- [ ] `git log --oneline github/feat/advisory-platform-mvp..HEAD` 无输出

定期（每周/发版前）：

- [ ] `git fetch github && git fetch origin`，两侧对账
- [ ] `git push github --all && git push github --tags`
