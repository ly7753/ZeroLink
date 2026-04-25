#!/bin/bash
export PREFIX="/data/user/0/com.termux/files/usr"
export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
export PATH="$JAVA_HOME/bin:$PREFIX/bin:$PATH"
export ANDROID_HOME="/data/user/0/com.tom.rv2ide/files/home/android-sdk"

echo "[*] 🚀 开始编译 ZeroLink (Release 正式版)..."

# 使用与 QingYiCompose 相同的编译指令和 aapt2 覆盖
gradle :app-controller:assembleRelease :app-controlled:assembleRelease \
    --parallel \
    --no-daemon \
    -Pandroid.aapt2FromMavenOverride="/data/user/0/com.tom.rv2ide/files/home/.androidide/aapt2"

if [ $? -eq 0 ]; then
    echo "[✅] 编译成功！"
    
    # 准确获取产物路径
    CONTROLLER_APK="app-controller/build/outputs/apk/release/app-controller-release.apk"
    CONTROLLED_APK="app-controlled/build/outputs/apk/release/app-controlled-release.apk"
    
    # 复制到根目录并打上时间戳，避免 mv 逻辑丢失原始文件引用
    if [ -f "$CONTROLLER_APK" ] && [ -f "$CONTROLLED_APK" ]; then
        TS=$(date +%H%M%S)
        cp "$CONTROLLER_APK" "./ZeroLink_控制端_${TS}.apk"
        cp "$CONTROLLED_APK" "./ZeroLink_受控端_${TS}.apk"
        
        echo "[📦] 构建产物已完美生成:"
        echo "  ▶ ./ZeroLink_控制端_${TS}.apk"
        echo "  ▶ ./ZeroLink_受控端_${TS}.apk"
        echo "----------------------------------------"
        echo "[*] 🎉 包含完整 XML 和 ARSC，请前往文件管理器点击安装！"
    else
        echo "[❌] 编译成功，但在默认路径未找到 Release APK 文件。"
    fi
else
    echo "[❌] 编译失败，请检查报错。"
    exit 1
fi
