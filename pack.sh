#!/bin/bash
# ZeroLink 项目源码备份打包脚本

# 定义输出文件名，带上时间戳
OUTPUT_FILE="ZeroLink_Backup_$(date +%Y%m%d_%H%M%S).zip"

echo "[*] 开始打包 ZeroLink 项目..."

# 检查 zip 工具是否存在
if ! command -v zip &> /dev/null; then
    echo "[!] 错误: 未安装 zip 工具。请先执行: pkg install zip (Termux) 或 sudo apt install zip"
    exit 1
fi

# 执行压缩，排除掉所有不必要的构建产物和缓存
# 排除列表基于项目模块结构：app-controller, app-controlled, core-domain 等
zip -r "$OUTPUT_FILE" . \
    -x "*/build/*" \
    -x ".gradle/*" \
    -x ".idea/*" \
    -x "*/.idea/*" \
    -x "local.properties" \
    -x "*.iml" \
    -x ".DS_Store" \
    -x "*/.codebuddy/*" \
    -x "*.apk" \
    -x "*.dex" \
    -x "out.log"

if [ $? -eq 0 ]; then
    echo "----------------------------------------"
    echo "[✅] 打包完成！"
    echo "[📦] 压缩包位置: $(pwd)/$OUTPUT_FILE"
    echo "[📏] 文件大小: $(du -h "$OUTPUT_FILE" | cut -f1)"
else
    echo "[❌] 打包失败，请检查磁盘空间或权限。"
    exit 1
fi
