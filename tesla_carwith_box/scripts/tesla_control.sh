#!/system/bin/sh
################################################################################
# Tesla CarWith Box - Core Control Script (With Container & Virtual Interface)
################################################################################

MODDIR="/data/adb/modules/tesla_carwith_box"
DATADIR="/data/adb/tesla_box"
mkdir -p "$DATADIR"

CONFIG_FILE="$DATADIR/config.env"
PID_FILE="$DATADIR/server.pid"

DEFAULT_RES="1920x1080"
DEFAULT_DPI="200"
DEFAULT_PORT="8080"
DEFAULT_AUDIO="system"
DEFAULT_VEHICLE="Xiaomi SU7-Tesla"

load_config() {
  if [ -f "$CONFIG_FILE" ]; then
    . "$CONFIG_FILE"
  fi
  RES="${RES:-$DEFAULT_RES}"
  DPI="${DPI:-$DEFAULT_DPI}"
  PORT="${PORT:-$DEFAULT_PORT}"
  TARGET_PKG="${TARGET_PKG:-com.baidu.carlifevehicle}"
  AUDIO_MODE="${AUDIO_MODE:-$DEFAULT_AUDIO}"
  VEHICLE_NAME="${VEHICLE_NAME:-$DEFAULT_VEHICLE}"
  if [ -n "$VEHICLE_NAME" ]; then
    echo "$VEHICLE_NAME" > /data/local/tmp/tesla_vehicle_name 2>/dev/null
    chmod 0666 /data/local/tmp/tesla_vehicle_name 2>/dev/null
  fi
}

save_config() {
  cat <<EOF > "$CONFIG_FILE"
RES="${RES:-$DEFAULT_RES}"
DPI="${DPI:-$DEFAULT_DPI}"
PORT="${PORT:-$DEFAULT_PORT}"
TARGET_PKG="${TARGET_PKG:-com.baidu.carlifevehicle}"
AUDIO_MODE="${AUDIO_MODE:-$DEFAULT_AUDIO}"
VEHICLE_NAME="${VEHICLE_NAME:-$DEFAULT_VEHICLE}"
EOF
  echo "${VEHICLE_NAME:-$DEFAULT_VEHICLE}" > /data/local/tmp/tesla_vehicle_name 2>/dev/null
  chmod 0666 /data/local/tmp/tesla_vehicle_name 2>/dev/null
}

load_config

get_ip() {
  # 优先检测活跃的热点接口 (wlan2, swlan0, ap0, softap0, wlan1) 的真实 IP
  for iface in wlan2 swlan0 ap0 softap0 wlan1 wlan0 rndis0; do
    ip_addr=$(ip -4 addr show "$iface" 2>/dev/null | grep -oE 'inet [0-9.]+' | head -n 1 | cut -d' ' -f2)
    if [ -n "$ip_addr" ] && [ "$ip_addr" != "127.0.0.1" ]; then
      echo "$ip_addr"
      return
    fi
  done
  echo "192.168.43.1"
}

unblock_hotspot() {
  VIRTUAL_PUBLIC_IP="101.200.208.6"

  # 1. 绝对放行 Loopback 回环 (保证 CarWith 本地通信畅通)
  iptables -C INPUT -i lo -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -i lo -j ACCEPT 2>/dev/null

  # 2. 绑定虚拟公网 IP 至回环网卡 lo，让手机内核直接接管并响应对 101.200.208.6 的访问 (彻底规避特斯拉私有IP拦截)
  ip addr add "$VIRTUAL_PUBLIC_IP/32" dev lo 2>/dev/null

  # 3. 放行所有局域网/热点端口 80 (HTTP), 8080 (Web), 7240 (控制), 8240 (视频), 9340 (音频), 7999, 8089 (音频串流), 5354 (DNS虚拟解析)
  iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 7240 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 7240 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 8240 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8240 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 9340 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 9340 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p udp --dport 7999 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 7999 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p udp --dport 8089 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 8089 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p udp --dport 5354 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 5354 -j ACCEPT 2>/dev/null
  iptables -C INPUT -d "$VIRTUAL_PUBLIC_IP" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -d "$VIRTUAL_PUBLIC_IP" -j ACCEPT 2>/dev/null

  # 4. 热点网络隔离穿透放行 (防 netd tetherctrl_INPUT 默认 DROP)
  iptables -C tetherctrl_INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_INPUT -p tcp --dport 7240 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 7240 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_INPUT -p tcp --dport 8240 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 8240 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_INPUT -p tcp --dport 9340 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 9340 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_INPUT -p udp --dport 8089 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p udp --dport 8089 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_INPUT -p udp --dport 5354 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p udp --dport 5354 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_INPUT -d "$VIRTUAL_PUBLIC_IP" -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -d "$VIRTUAL_PUBLIC_IP" -j ACCEPT 2>/dev/null

  iptables -C tetherctrl_FORWARD -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_FORWARD 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null
  iptables -C tetherctrl_FORWARD -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_FORWARD 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null
  iptables -C FORWARD -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null
  iptables -C FORWARD -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null

  # 5. 虚拟公网 IP NAT 重定向：捕获发往 101.200.208.6 的所有 TCP 请求 (免端口 80 及 8080 等)，平滑导流至 8080
  iptables -t nat -C PREROUTING -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null || \
  iptables -t nat -I PREROUTING 1 -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null

  iptables -t nat -C OUTPUT -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null || \
  iptables -t nat -I OUTPUT 1 -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null

  # 6. 精准 DNS 重定向：全局捕获包含 carwith 的查询重定向至 5354
  iptables -t nat -C PREROUTING -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null || \
  iptables -t nat -I PREROUTING 1 -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null

  ip6tables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || ip6tables -I INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null
  ip6tables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || ip6tables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null

  # 7. 清理残留 hosts 挂载，彻底恢复系统底层网络
  umount /system/etc/hosts 2>/dev/null
  umount /etc/hosts 2>/dev/null

  # 8. 彻底清理无条件全局劫持规则（防止影响车机联网连通性探测）
  for dev in wlan2 swlan0 ap0 softap0 wlan1 rndis0 wlan0; do
    while iptables -t nat -D PREROUTING -i "$dev" -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING -i "$dev" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; do :; done
  done
  while iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; do :; done
  while iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; do :; done

  # 9. 为已 UP 的热点网卡绑定 192.168.43.1 别名并配置高精度定向规则
  for iface in wlan2 swlan0 ap0 softap0 wlan1 rndis0 wlan0; do
    if ip link show "$iface" 2>/dev/null | grep -q "state UP"; then
      _gw=$(ip -4 addr show "$iface" 2>/dev/null | grep -oE 'inet [0-9.]+' | head -n 1 | cut -d' ' -f2)
      [ -z "$_gw" ] && _gw="192.168.43.1"

      if ! ip -4 addr show "$iface" 2>/dev/null | grep -q "192.168.43.1"; then
        ip addr add 192.168.43.1/24 dev "$iface" 2>/dev/null
      fi

      # 清理此网卡残留的全局重定向规则
      iptables -t nat -D PREROUTING -i "$iface" -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null
      iptables -t nat -D PREROUTING -i "$iface" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null

      # 局域网精准 DNS 重定向：仅拦截包含 carwith 的域名查询，重定向至本地 5354
      # 外网域名正常直通系统 DNS，保证车机网络健康检测毫秒级通过
      iptables -t nat -C PREROUTING -i "$iface" -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null || \
      iptables -t nat -I PREROUTING 1 -i "$iface" -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null

      # 局域网精准 HTTP 80 重定向：仅拦截发往本机网关 IP 的 80 端口请求重定向至 8080 (支持免端口访问 t.carwith.com)
      # 外部公网 HTTP 80 流量绝不拦截，车机连网检测 (generate_204 等) 完全不受影响
      iptables -t nat -C PREROUTING -i "$iface" -d "$_gw" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || \
      iptables -t nat -I PREROUTING 1 -i "$iface" -d "$_gw" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null
      if [ "$_gw" != "192.168.43.1" ]; then
        iptables -t nat -C PREROUTING -i "$iface" -d 192.168.43.1 -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || \
        iptables -t nat -I PREROUTING 1 -i "$iface" -d 192.168.43.1 -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null
      fi
    fi
  done
}

get_display_id() {
  # 优先获取 Android DisplayManager 中 com.miui.carlink 的逻辑显示屏 ID (如 12)
  disp_id=$(dumpsys display 2>/dev/null | grep -E 'Display [0-9]+:' -A 2 | grep -B 2 'com\.miui\.carlink(' | grep -oE 'Display [0-9]+' | head -n 1 | cut -d' ' -f2)
  if [ -n "$disp_id" ]; then
    echo "$disp_id"
    return
  fi
  # 备用：检测任意非 0/1 的虚拟显示屏
  disp_id=$(dumpsys display 2>/dev/null | grep -E 'Display [1-9][0-9]+:' | head -n 1 | cut -d':' -f1 | cut -d' ' -f2)
  if [ -n "$disp_id" ]; then
    echo "$disp_id"
    return
  fi
  echo "0"
}

# 容器管理 (Android Multi-User 容器)
get_container_id() {
  uid=$(pm list users 2>/dev/null | grep -i "CarBox" | grep -oE 'UserInfo\{[0-9]+' | grep -oE '[0-9]+' | head -n 1)
  if [ -n "$uid" ]; then
    echo "$uid"
  else
    echo ""
  fi
}

case "$1" in
  status)
    load_config
    ip=$(get_ip)
    disp_id=$(get_display_id)
    server_running=0
    if [ -f "$PID_FILE" ]; then
      pid=$(cat "$PID_FILE")
      if [ -d "/proc/$pid" ]; then
        server_running=1
      else
        rm -f "$PID_FILE"
      fi
    fi

    disp_active=0
    cur_overlay=$(settings get global overlay_display_devices 2>/dev/null)
    if [ -n "$cur_overlay" ] && [ "$cur_overlay" != "null" ]; then
      disp_active=1
    fi

    cid=$(get_container_id)
    container_active=0
    [ -n "$cid" ] && container_active=1

    vnet_active=0
    if ip link show dummy0 >/dev/null 2>&1; then
      vnet_active=1
    fi

    cat <<EOF
{
  "ip": "$ip",
  "port": "${PORT:-$DEFAULT_PORT}",
  "audio_mode": "${AUDIO_MODE:-$DEFAULT_AUDIO}",
  "vehicle_name": "${VEHICLE_NAME:-$DEFAULT_VEHICLE}",
  "server_running": $server_running,
  "display_active": $disp_active,
  "display_id": $disp_id,
  "resolution": "${RES:-$DEFAULT_RES}",
  "dpi": "${DPI:-$DEFAULT_DPI}",
  "target_pkg": "${TARGET_PKG:-com.ucarhu.carlink}",
  "container_active": $container_active,
  "container_id": "${cid:-0}",
  "vnet_active": $vnet_active
}
EOF
    ;;

  start_display)
    res="${2:-$DEFAULT_RES}"
    dpi="${3:-$DEFAULT_DPI}"
    RES="$res"
    DPI="$dpi"
    save_config
    # 彻底关闭 Android 模拟辅助屏（防止手机屏幕出现悬浮镜像小窗口，CarWith 自带虚拟大屏）
    settings put global overlay_display_devices "" 2>/dev/null
    settings delete global overlay_display_devices 2>/dev/null
    disp_id=$(get_display_id)
    echo "{\"status\": \"ok\", \"display_id\": $disp_id, \"resolution\": \"$res\"}"
    ;;

  stop_display)
    settings put global overlay_display_devices "" 2>/dev/null
    settings delete global overlay_display_devices 2>/dev/null
    echo "{\"status\": \"ok\"}"
    ;;

  start_server)
    port="${2:-$DEFAULT_PORT}"
    PORT="$port"
    save_config
    unblock_hotspot
    "$MODDIR/scripts/server.sh" start "$port"
    # 确保热点监控服务也在后台运行
    if ! pgrep -f "hotspot_monitor.sh" >/dev/null 2>&1; then
      nohup "$MODDIR/scripts/hotspot_monitor.sh" >/dev/null 2>&1 &
    fi
    echo "{\"status\": \"ok\", \"port\": $port}"
    ;;

  set_audio_mode)
    mode="${2:-system}"
    AUDIO_MODE="$mode"
    save_config
    echo "$mode" > "$DATADIR/audio_mode" 2>/dev/null
    echo "$mode" > /data/local/tmp/carwith_audio_mode 2>/dev/null
    chmod 0666 "$DATADIR/audio_mode" /data/local/tmp/carwith_audio_mode 2>/dev/null

    if [ "$mode" = "virtual" ] || [ "$mode" = "web" ]; then
      echo "virtual" > /data/local/tmp/tesla_active_audio 2>/dev/null
      T_MODE=0
    elif [ "$mode" = "native" ] || [ "$mode" = "bluetooth" ]; then
      echo "bluetooth" > /data/local/tmp/tesla_active_audio 2>/dev/null
      T_MODE=1
    elif [ "$mode" = "speaker" ]; then
      echo "speaker" > /data/local/tmp/tesla_active_audio 2>/dev/null
      T_MODE=1
    else
      # system mode (default)
      echo "system" > /data/local/tmp/tesla_active_audio 2>/dev/null
      T_MODE=0
    fi
    chmod 0666 /data/local/tmp/tesla_active_audio 2>/dev/null

    if [ -f /data/local/tmp/bdcf ]; then
      sed -i "s/^AUDIO_TRANSMISSION_MODE=.*/AUDIO_TRANSMISSION_MODE=$T_MODE/g" /data/local/tmp/bdcf 2>/dev/null
    fi

    # Broadcast audio mode change so running processes update dynamically without reboot!
    am broadcast -a com.tesla.carwith.ACTION_AUDIO_MODE_CHANGED --es mode "$mode" >/dev/null 2>&1

    if [ "$mode" != "virtual" ]; then
      service call audio 7 i32 3 i32 0 s16 default >/dev/null 2>&1
      cmd media_session volume --stream 3 --set 15 >/dev/null 2>&1
    fi
    echo "{\"status\": \"ok\", \"audio_mode\": \"$mode\"}"
    ;;

  set_vehicle_name)
    name="${2:-Xiaomi SU7-Tesla}"
    VEHICLE_NAME="$name"
    save_config
    am force-stop com.miui.carlink >/dev/null 2>&1
    echo "{\"status\": \"ok\", \"vehicle_name\": \"$name\"}"
    ;;

  start_carlife|launch_carwith)
    # 唤起手机端 CarWith 车载连接设置主界面（绝不打开第三方 Carlife 抢占前台屏幕）
    am start -n com.miui.carlink/com.carwith.launcher.settings.phone.UCarConnectInfoActivity >/dev/null 2>&1 || \
    am start -a miui.intent.action.UCAR_CONNECT_SETTINGS >/dev/null 2>&1
    echo "{\"status\": \"ok\"}"
    ;;

  unblock_hotspot)
    unblock_hotspot
    echo "{\"status\": \"ok\", \"unblocked\": true}"
    ;;

  stop_server)
    "$MODDIR/scripts/server.sh" stop
    echo "{\"status\": \"ok\"}"
    ;;

  # 容器管理 (已全面升级为 User 0 单实例后台静默引擎，彻底根除端口冲突掉线)
  container_create)
    # 彻底清理残留 CarBox 多用户，防止双实例端口冲突
    for uid in $(pm list users 2>/dev/null | grep -i "CarBox" | grep -oE 'UserInfo\{[0-9]+' | grep -oE '[0-9]+'); do
      am stop-user -w "$uid" 2>/dev/null
      pm remove-user "$uid" 2>/dev/null
    done
    pm enable --user 0 com.baidu.carlife.xiaomi 2>/dev/null
    echo "{\"status\": \"ok\", \"mode\": \"headless_user0\", \"container_id\": 0}"
    ;;

  container_remove)
    for uid in $(pm list users 2>/dev/null | grep -i "CarBox" | grep -oE 'UserInfo\{[0-9]+' | grep -oE '[0-9]+'); do
      am stop-user -w "$uid" 2>/dev/null
      pm remove-user "$uid" 2>/dev/null
    done
    pm enable --user 0 com.baidu.carlife.xiaomi 2>/dev/null
    echo "{\"status\": \"ok\"}"
    ;;

  container_start_app)
    pkg="$2"
    [ -z "$pkg" ] && pkg="com.baidu.carlifevehicle"
    am start -n com.baidu.carlifevehicle/.CarlifeActivity >/dev/null 2>&1
    am broadcast -a com.baidu.carlifevehicle.CONNECT_DIRECT --es ip "127.0.0.1" >/dev/null 2>&1
    echo "{\"status\": \"ok\", \"pkg\": \"$pkg\", \"mode\": \"headless_user0\"}"
    ;;

  # 虚拟网络接口与端口中继
  vnet_start)
    # 创建 dummy0 虚拟网卡
    ip link add dummy0 type dummy 2>/dev/null
    ip addr add 192.168.43.2/24 dev dummy0 2>/dev/null
    ip link set dummy0 up 2>/dev/null

    # 配置 iptables 端口中继规则 (CarLife 7240 + 亿连 20150-20152)
    iptables -t nat -A PREROUTING -p tcp --dport 7240 -j REDIRECT --to-ports 7240 2>/dev/null
    iptables -t nat -A PREROUTING -p udp --dport 7240 -j REDIRECT --to-ports 7240 2>/dev/null
    iptables -t nat -A PREROUTING -p tcp --dport 20150 -j REDIRECT --to-ports 20150 2>/dev/null
    iptables -t nat -A PREROUTING -p tcp --dport 20151 -j REDIRECT --to-ports 20151 2>/dev/null
    iptables -t nat -A PREROUTING -p tcp --dport 20152 -j REDIRECT --to-ports 20152 2>/dev/null
    
    echo "{\"status\": \"ok\", \"vnet\": \"dummy0 (192.168.43.2)\"}"
    ;;

  vnet_stop)
    ip link set dummy0 down 2>/dev/null
    ip link delete dummy0 2>/dev/null
    iptables -t nat -D PREROUTING -p tcp --dport 7240 -j REDIRECT --to-ports 7240 2>/dev/null
    iptables -t nat -D PREROUTING -p udp --dport 7240 -j REDIRECT --to-ports 7240 2>/dev/null
    iptables -t nat -D PREROUTING -p tcp --dport 20150 -j REDIRECT --to-ports 20150 2>/dev/null
    iptables -t nat -D PREROUTING -p tcp --dport 20151 -j REDIRECT --to-ports 20151 2>/dev/null
    iptables -t nat -D PREROUTING -p tcp --dport 20152 -j REDIRECT --to-ports 20152 2>/dev/null
    echo "{\"status\": \"ok\"}"
    ;;

  tap)
    x="$2"
    y="$3"
    disp_id=$(get_display_id)
    if [ -n "$x" ] && [ -n "$y" ]; then
      input -d "$disp_id" tap "$x" "$y" 2>/dev/null || input tap "$x" "$y"
    fi
    echo "{\"status\": \"ok\"}"
    ;;

  swipe)
    x1="$2"
    y1="$3"
    x2="$4"
    y2="$5"
    dur="${6:-300}"
    disp_id=$(get_display_id)
    if [ -n "$x1" ] && [ -n "$y2" ]; then
      input -d "$disp_id" swipe "$x1" "$y1" "$x2" "$y2" "$dur" 2>/dev/null || input swipe "$x1" "$y1" "$x2" "$y2" "$dur"
    fi
    echo "{\"status\": \"ok\"}"
    ;;

  *)
    echo "Usage: $0 {status|start_display|stop_display|start_server|stop_server|set_audio_mode|unblock_hotspot|container_create|container_remove|container_start_app|vnet_start|vnet_stop|tap|swipe}"
    exit 1
    ;;
esac
