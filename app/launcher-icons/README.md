# Droy launcher artwork

Nine original AI-generated black-cat illustrations form the built-in launcher
icon set. The background is warm-white cotton card stock (approximately
`#EEE6D9`) with fine paper fibers. The square source PNGs in this directory are
authoring inputs; they are not copied into APK assets.

| Source | Icon |
| --- | --- |
| `01-closeup.png` | Closeup · 怼脸（默认） |
| `02-sunglasses.png` | Sunglasses · 扶墨镜 |
| `03-headphones.png` | Headphones · 耳机墨镜 |
| `04-programmer.png` | Programmer · 程序员 |
| `05-hat-scarf.png` | Hat and scarf · 帽子围巾 |
| `06-sleep-curled.png` | Curled sleep · 蜷团睡 |
| `07-sleep-side.png` | Side sleep on a pillow · 枕头侧睡 |
| `08-sleep-belly-up.png` | Belly-up sleep · 仰睡 |
| `09-sleep-slumped.png` | Slumped sleep · 靠墙睡 |

The closeup is the default. Each desktop launch has a 2% chance of choosing
uniformly from the other eight icons when at least one hour has passed since
the last successful change. The selected icon and cooldown survive app/process
restarts. A custom `--app-icon` / `droyAppIcon` build retains its supplied logo
and does not randomly replace it with the built-in cat set.
Rotation changes the desktop launcher alias only. The installed application
identity and the logo shown in app settings keep the default branding icon.

默认使用怼脸图标。从桌面打开 App 时，在距离上次成功切换已满一小时的情况下，
有 2% 概率从其余八张中等概率选择一张。已选图标与冷却时间在 App/进程重启后保留。
使用 `--app-icon` / `droyAppIcon` 的自定义构建保留传入图标，不启用随机换猫图标。
随机切换只作用于桌面启动入口；已安装应用的身份图标及 App 设置中的品牌图标仍使用默认图标。

## Export and check

From the repository root, with JDK 17 on `PATH`:

```sh
java -Djava.awt.headless=true tools/launcher-icons/GenerateLauncherIcons.java
java -Djava.awt.headless=true tools/launcher-icons/GenerateLauncherIcons.java --check
```

Optional visual comparison of circle and rounded-square launcher masks:

```sh
java -Djava.awt.headless=true tools/launcher-icons/GenerateLauncherIcons.java \
  --preview ../artifacts/launcher-icons/icon-mask-review.png
```

The exporter writes five legacy PNG densities (`48/72/96/144/192px`), one
`108dp` adaptive layer per icon, and adaptive XML into `app/src/main/res`.
`catalog.json` records each source SHA-256 and its resource name. `--check`
compares all exported bytes and the catalog with a fresh export, without
changing tracked files. Source changes require rerunning the exporter.

Adaptive avatars use `72dp` artwork to retain their close framing. Full-body
sleeping poses use `60dp` so their paws and tails fit common masks. Exterior
paper is separated from the foreground while enclosed eye marks, accessories
and the sage pillow stay opaque. The common paper layer fills the overscan
region so the artwork has no opaque square edge when the launcher moves it.
The source PNGs stay unchanged by this preparation.

在仓库根目录使用 JDK 17 运行上面的导出命令；`--check` 只检查，不修改文件。
修改原图后需重新导出。头像保留较大的近景构图；全身睡姿适当留出安全区，
以保留圆形和圆角桌面图标中的四肢、尾巴与枕头。系统决定最终图标遮罩。
