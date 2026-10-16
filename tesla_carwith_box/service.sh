#!/system/bin/sh
################################################################################
# Tesla CarWith Box - Boot Service (Scheme 2: Pure Native Client Bridge)
# 作者: 一缕微光（QQ：867836055）
################################################################################

MODDIR=${0%/*}
[ -z "$MODDIR" ] || [ "$MODDIR" = "." ] && MODDIR="/data/adb/modules/tesla_carwith_box"
DATADIR="/data/adb/tesla_box"
mkdir -p "$DATADIR"

# 等待系统开机完成 (Boot Completed)
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
sleep 1

# 赋予脚本、JAR 与 WebUI 执行/读取权限
chmod -R 0755 "$MODDIR"
chmod 0644 "$MODDIR/tesla_bridge.jar" 2>/dev/null
chcon -R u:object_r:system_file:s0 "$MODDIR" 2>/dev/null

# 确保清理残留 hosts 挂载，彻底恢复手机与应用底层网络连接
umount /system/etc/hosts 2>/dev/null
umount /etc/hosts 2>/dev/null

# 彻底清理模拟辅助屏悬浮小窗口并关闭小白点
settings put global overlay_display_devices "" 2>/dev/null
settings delete global overlay_display_devices 2>/dev/null
settings put system show_touches 0 2>/dev/null
rm -f /data/local/tmp/tesla_display_id 2>/dev/null

# 放行热点与车机协议通信防火墙并清理旧全量重定向
for dev in wlan2 swlan0 ap0 softap0 wlan1 rndis0 wlan0; do
  while iptables -t nat -D PREROUTING -i "$dev" -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; do :; done
  while iptables -t nat -D PREROUTING -i "$dev" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; do :; done
done
while iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; do :; done
while iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; do :; done

ip addr add 101.200.208.6/32 dev lo 2>/dev/null
"$MODDIR/scripts/tesla_control.sh" unblock_hotspot >/dev/null 2>&1

# 启动热点状态自动检测与局域网映射守护进程
nohup "$MODDIR/scripts/hotspot_monitor.sh" >/dev/null 2>&1 &

# 自动安装/更新车机端 APK 与虚拟网卡/蓝牙 LSPosed 模块
BUILD_STAMP="$DATADIR/.build_v5.2_soundflow"
if [ ! -f "$BUILD_STAMP" ]; then
  if [ -f "$MODDIR/apps/CarLifeVehicle.apk" ]; then
    pm install -r -d "$MODDIR/apps/CarLifeVehicle.apk" >/dev/null 2>&1
    pm grant com.baidu.carlifevehicle android.permission.RECORD_AUDIO 2>/dev/null
    pm grant com.baidu.carlifevehicle android.permission.ACCESS_FINE_LOCATION 2>/dev/null
    pm grant com.baidu.carlifevehicle android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
    pm grant com.baidu.carlifevehicle android.permission.READ_PHONE_STATE 2>/dev/null
  fi
  if [ -f "$MODDIR/apps/CarWithVirtualizer.apk" ]; then
    pm install -r -d "$MODDIR/apps/CarWithVirtualizer.apk" >/dev/null 2>&1
  fi
  touch "$BUILD_STAMP"
fi

# 彻底清理残留 CarBox 多用户容器，彻底根除双实例端口冲突导致的频繁掉线
for uid in $(pm list users 2>/dev/null | grep -i "CarBox" | grep -oE 'UserInfo\{[0-9]+' | grep -oE '[0-9]+'); do
  am stop-user -w "$uid" 2>/dev/null
  pm remove-user "$uid" 2>/dev/null
done
pm enable --user 0 com.baidu.carlife.xiaomi 2>/dev/null

# 初始化车机音频配置 bdcf (支持澎湃OS4控制中心【本机】、【虚拟车机】、【车载蓝牙】自由流转切换)
A_MODE=$(cat "$DATADIR/audio_mode" 2>/dev/null || echo "system")
T_MODE=0
if [ "$A_MODE" = "native" ] || [ "$A_MODE" = "bluetooth" ] || [ "$A_MODE" = "speaker" ]; then
  T_MODE=1
fi
cat <<EOF > /data/local/tmp/bdcf
20022100
AUDIO_TRACK_TYPE = 0
AUDIO_TRACK_NUM = 2
AUDIO_TRACK_STREAM_TYPE = 3
AUDIO_TTS_REQUEST_FOCUS = false
VOICE_MIC = 1
VOICE_WAKEUP = true
NEED_MORE_DECODE_TIME = false
BLUETOOTH_INTERNAL_UI = false
TRANSPARENT_SEND_TOUCH_EVENT = true
VEHICLE_GPS = false
FOCUS_UI = false
MEDIA_SAMPLE_RATE = 0
CONNECT_TYPE_ANDROID = 1
CONNECT_TYPE_IPHONE = 1
AUDIO_TRANSMISSION_MODE=$T_MODE
CONTENT_ENCRYPTION=false
ENGINE_TYPE=1
EOF
chmod 0666 /data/local/tmp/bdcf 2>/dev/null

# 确保车机端后台静默运行并开放 7240/8240/9340 通信端口
if ! pgrep -f "com.baidu.carlifevehicle" >/dev/null 2>&1; then
  am start -n com.baidu.carlifevehicle/.CarlifeActivity >/dev/null 2>&1
fi

# 启动本地原生车机桥接守护进程 (端口 8080)
"$MODDIR/scripts/server.sh" start "8080" >/dev/null 2>&1

exit 0
