# 中国地名 NewGRF

为 OpenTTD 提供 3000+ 个真实中国地名（省/市/区县，去掉“省/市/区/县”等后缀去重后）。

- `chinese_town_names.nml`：NML 源码（`town_names` 定义一个单part生成器，样式名「中国地名」）
- `lang/english.lng`：GRF 名称/描述字符串
- `chinese_town_names.grf`：编译产物（约 33KB），已内置进 APK
- `names_clean.json`：清洗后的地名表
- `build.sh`：`nmlc -o chinese_town_names.grf chinese_town_names.nml`

数据来源：[modood/Administrative-divisions-of-China](https://github.com/modood/Administrative-divisions-of-China)（MIT）。

APK 内路径：`newgrf/chinese_town_names.grf`，并在 `openttd.cfg` 里预置：

```ini
[newgrf]
chinese_town_names.grf =

[game_creation]
town_name = 21   ; 21 = 第一个 NewGRF 地名生成器
```
