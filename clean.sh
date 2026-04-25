#!/bin/bash

# ZeroLink 终极清道夫脚本
# 目标：强力清除所有变体备份、修正逻辑冗余、恢复源码纯净度

echo "[*] 🧹 开始执行深度大扫除..."

# 1. 清理所有形式的源码备份 (MainActivity.kt.bak2, ServerMain.kt.bak_final 等)
echo "[+] 正在强力粉碎奇葩后缀备份文件..."
# 使用扩展正则匹配 .bak 后面跟任何字符的文件
find . -type f -regextype posix-extended -regex ".*\.bak(_)?[a-zA-Z0-9]*" -delete

# 2. 清理根目录下的时间戳 APK
echo "[+] 正在清理冗余 APK 产物..."
rm -f ./ZeroLink_*.apk

# 3. 核心文件逻辑去重 (防止 patch.sh 反复运行导致的逻辑堆叠)
# 针对 ServerMain.kt，我们采用“先恢复、再精简”的策略
SERVER_MAIN="core-shizuku-server/src/main/java/com/zero/link/shizuku/server/ServerMain.kt"
if [ -f "$SERVER_MAIN" ]; then
    echo "[+] 正在修复 $SERVER_MAIN 的逻辑冗余..."
    # 这里的逻辑是：删除所有由 patch.sh 自动添加的特定优化行，只保留一行干净的
    # 我们先临时删除重复的配置，通过 uniq 或 sed 处理
    sed -i '/setInteger(MediaFormat.KEY_PROFILE/d' "$SERVER_MAIN"
    sed -i '/setInteger(MediaFormat.KEY_LEVEL/d' "$SERVER_MAIN"
    sed -i '/setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD/d' "$SERVER_MAIN"
    
    # 重新插入一份标准的（仅一份）
    sed -i '/setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)/a \                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)\n                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)\n                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {\n                            setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, 60)\n                        }' "$SERVER_MAIN"
fi

# 4. 执行标准的 Gradle 清理
echo "[+] 正在执行 Gradle Clean 并移除缓存..."
./gradlew clean --no-daemon > /dev/null 2>&1
rm -rf .gradle/
rm -rf .kotlin/

echo "----------------------------------------"
echo "[✅] 深度清理完成！"
echo "[📦] 当前项目目录状态："
ls -R | grep ".kt$" | grep "bak" && echo "[!] 警告：仍有残余" || echo "[✓] 备份文件已全部肃清"
