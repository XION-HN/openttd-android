#!/usr/bin/env bash
# 从 BaNaNaS CDN 下载 Chinese True Town Names（zbx1425，GPL/CC 见 license.txt）
set -euo pipefail
cd "$(dirname "$0")"
URL="https://bananas-cdn.openttd.org/newgrf/5a425801/b10930fc85f8c693d5aa08cff30202ef/5a425801-Chinese_True_Town_Names-1.0.tar.gz"
curl -fL -o cttn.tar.gz "$URL"
tar -xzf cttn.tar.gz
cp -f Chinese_True_Town_Names-1.0/Chinese_True_Town_Names.grf ./Chinese_True_Town_Names.grf
cp -f Chinese_True_Town_Names-1.0/license.txt ./license.txt
rm -rf Chinese_True_Town_Names-1.0 cttn.tar.gz
echo "已更新 Chinese_True_Town_Names.grf"
