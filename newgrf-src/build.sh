#!/usr/bin/env bash
# 用 nmlc 编译中文地名 NewGRF。需要：pip install nml
set -euo pipefail
cd "$(dirname "$0")"
nmlc -o chinese_town_names.grf chinese_town_names.nml
echo "已生成 chinese_town_names.grf"
