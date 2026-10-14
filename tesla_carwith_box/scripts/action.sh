#!/system/bin/sh
################################################################################
# Tesla CarWith Box - KSU Action Button Trigger
# 作者: 一缕微光（QQ：867836055）
################################################################################

MODDIR=${0%/*}

# 1. 彻底清除屏幕模拟悬浮小窗口
settings put global overlay_display_devices "" 2>/dev/null
settings delete global overlay_display_devices 2>/dev/null

# 2. 放行热点 8080 / 7240 防火墙
"$MODDIR/scripts/tesla_control.sh" unblock_hotspot >/dev/null 2>&1

# 3. 启动后台串流服务与热点监听
"$MODDIR/scripts/tesla_control.sh" start_server "8080" >/dev/null 2>&1

# 读取当前车辆名称与 IP
VEHICLE_NAME="Tesla Model Y"
[ -f /data/local/tmp/tesla_vehicle_name ] && VEHICLE_NAME=$(cat /data/local/tmp/tesla_vehicle_name)
IP="192.168.43.1"
for iface in ap0 wlan1 swlan0 softap0 wlan2 wlan0 rndis0; do
  _ip=$(ip -4 addr show "$iface" 2>/dev/null | grep -oE 'inet [0-9.]+' | head -n 1 | cut -d' ' -f2)
  if [ -n "$_ip" ] && [ "$_ip" != "127.0.0.1" ]; then
    IP="$_ip"
    break
  fi
done

# 容器状态
CONTAINER_STATUS="未创建"
if pm list users 2>/dev/null | grep -qi "CarBox"; then
  CONTAINER_STATUS="已激活 (隔离中)"
fi

echo "=============================================="
echo " 🚗 Tesla CarWith Box - 澎湃车联控制中心"
echo " 作者: 一缕微光 (QQ: 867836055)"
echo "----------------------------------------------"
echo " [√] 车辆名称:     $VEHICLE_NAME"
echo " [√] 车机静默引擎: 已就绪 (User 0 单实例)"
echo " [√] 串流网关:     已监听 (端口: 8080 / 80)"
echo " [√] 热点网络映射: 已启用 (网关: $IP)"
echo " [√] 悬浮小窗口:   已清理"
echo "----------------------------------------------"
echo " 🌐 特斯拉车机访问网址: http://t.carwith.com (或公网直连: http://101.200.208.6)"
echo " ⚙️ 本机控制台网址:     http://127.0.0.1:8080/index.html"
echo "=============================================="
echo " 正在自动为您打开 WebUI 控制台..."

# 4. 自动唤起手机浏览器直接加载 WebUI 页面
am start -a android.intent.action.VIEW -d "http://127.0.0.1:8080/index.html" >/dev/null 2>&1

exit 0
