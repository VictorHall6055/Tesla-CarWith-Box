#!/system/bin/sh
################################################################################
# Tesla CarWith Box - Hotspot Detection & LAN Auto-Mapping Daemon
# 自动监听车机连接手机热点状态，瞬间放行防火墙并映射 Web 投屏服务
################################################################################

MODDIR="/data/adb/modules/tesla_carwith_box"
DATADIR="/data/adb/tesla_box"
mkdir -p "$DATADIR"
STATE_FILE="$DATADIR/tesla_state.json"
PID_FILE="$DATADIR/hotspot_monitor.pid"
CTRL_SCRIPT="$MODDIR/scripts/tesla_control.sh"

echo $$ > "$PID_FILE"

HOTSPOT_IFACES="wlan2 swlan0 ap0 softap0 wlan1 rndis0 wlan0"
VIRTUAL_PUBLIC_IP="101.200.208.6"
LAST_CONNECTED_CLIENTS=""

ensure_firewall() {
  # 1. 绝对放行 Loopback 回环 (保证 CarWith 对 127.0.0.1:7240 的通信畅通无阻)
  iptables -C INPUT -i lo -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -i lo -j ACCEPT 2>/dev/null

  # 2. 绑定虚拟公网 IP 至回环网卡 lo，让手机内核直接接管并响应对 101.200.208.6 的访问 (绕过特斯拉私网拦截)
  ip addr add "$VIRTUAL_PUBLIC_IP/32" dev lo 2>/dev/null

  # 3. 放行核心服务端口 (80 HTTP, 8080 Web, 7240 控制, 8240 视频, 9340 音频, 7999, 8089 音频串流, 5354 DNS)
  iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 7240 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 7240 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 8240 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8240 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p tcp --dport 9340 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 9340 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p udp --dport 7999 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 7999 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p udp --dport 8089 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 8089 -j ACCEPT 2>/dev/null
  iptables -C INPUT -p udp --dport 5354 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 5354 -j ACCEPT 2>/dev/null
  iptables -C INPUT -d "$VIRTUAL_PUBLIC_IP" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -d "$VIRTUAL_PUBLIC_IP" -j ACCEPT 2>/dev/null

  # 4. 热点网络隔离穿透放行 (防止 Android netd 动态清空并默认 DROP tetherctrl_INPUT)
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

  # 5. 虚拟公网 IP NAT 重定向：捕获发往 101.200.208.6 的所有 TCP 请求 (免端口 80 及其他端口)，平滑导流至 8080
  iptables -t nat -C PREROUTING -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null || \
  iptables -t nat -I PREROUTING 1 -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null

  iptables -t nat -C OUTPUT -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null || \
  iptables -t nat -I OUTPUT 1 -d "$VIRTUAL_PUBLIC_IP" -p tcp -j REDIRECT --to-ports 8080 2>/dev/null

  # 6. 精准 DNS 重定向：全局捕获包含 carwith 的查询重定向至 5354
  iptables -t nat -C PREROUTING -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null || \
  iptables -t nat -I PREROUTING 1 -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null

  ip6tables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || ip6tables -I INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null
  ip6tables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || ip6tables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null
}

clean_broad_redirects() {
  for dev in wlan2 swlan0 ap0 softap0 wlan1 rndis0 wlan0; do
    while iptables -t nat -D PREROUTING -i "$dev" -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING -i "$dev" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; do :; done
  done
  while iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; do :; done
  while iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; do :; done
}

# 初始放行防火墙与清理旧全量重定向规则
ensure_firewall
clean_broad_redirects

# 清理任何残留 hosts 挂载
umount /system/etc/hosts 2>/dev/null
umount /etc/hosts 2>/dev/null

while true; do
  # 持续保障防火墙规则不被 Android netd 冲刷掉
  ensure_firewall

  # 守护保活：确保车载串流 HTTP 守护进程始终处于运行状态
  if ! pgrep -f "com.tesla.carwith.Main" >/dev/null 2>&1; then
    "$MODDIR/scripts/server.sh" start 8080 >/dev/null 2>&1
  fi

  # 守护保活：确保车机端后台静默就绪 (不发送干扰前台的按键事件)
  if ! pgrep -f "com.baidu.carlifevehicle" >/dev/null 2>&1; then
    am start -n com.baidu.carlifevehicle/.CarlifeActivity >/dev/null 2>&1
  fi

  FOUND_CLIENT=0
  CURRENT_CLIENTS=""
  ACTIVE_IFACE=""

  for iface in $HOTSPOT_IFACES; do
    # 检查网卡是否已正常 UP
    if ip link show "$iface" 2>/dev/null | grep -q "state UP"; then
      ACTIVE_IFACE="$iface"

      # 动态获取网卡实际分配的网关 IP
      _gw=$(ip -4 addr show "$iface" 2>/dev/null | grep -oE 'inet [0-9.]+' | head -n 1 | cut -d' ' -f2)
      [ -z "$_gw" ] && _gw="192.168.43.1"

      # 确保活跃网卡绑定 192.168.43.1 别名，确保车机可通过固定 IP 瞬间直达
      if ! ip -4 addr show "$iface" 2>/dev/null | grep -q "192.168.43.1"; then
        ip addr add 192.168.43.1/24 dev "$iface" 2>/dev/null
      fi

      # 清理此网卡上可能残留的无条件全局劫持规则
      iptables -t nat -D PREROUTING -i "$iface" -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null
      iptables -t nat -D PREROUTING -i "$iface" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null

      # 1. 局域网精准 DNS 重定向：仅拦截查询中包含 carwith 的域名 (如 t.carwith.com)，定向至 5354
      # 绝不拦截外部正常公网域名（如 connectivitycheck.gstatic.com, tesla.com），直接由系统原生网络毫秒级解析
      iptables -t nat -C PREROUTING -i "$iface" -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null || \
      iptables -t nat -I PREROUTING 1 -i "$iface" -p udp --dport 53 -m string --string "carwith" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null

      # 2. 局域网精准 HTTP 80 重定向：仅拦截目的地址为本机热点网关 IP 的 80 端口请求重定向至 8080 (支持免端口访问 t.carwith.com)
      # 绝不拦截发往公网服务器的 HTTP 80 请求，彻底保障车机连网连通性探测 (generate_204 等) 100% 畅通
      iptables -t nat -C PREROUTING -i "$iface" -d "$_gw" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || \
      iptables -t nat -I PREROUTING 1 -i "$iface" -d "$_gw" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null
      if [ "$_gw" != "192.168.43.1" ]; then
        iptables -t nat -C PREROUTING -i "$iface" -d 192.168.43.1 -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || \
        iptables -t nat -I PREROUTING 1 -i "$iface" -d 192.168.43.1 -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null
      fi

      # 从 /proc/net/arp 读取连接到此热点网卡的客户端设备
      while read -r ip type flags hw_addr mask dev; do
        if [ "$dev" = "$iface" ] && [ "$flags" = "0x2" ]; then
          FOUND_CLIENT=1
          CURRENT_CLIENTS="$CURRENT_CLIENTS $ip|$hw_addr|$dev"
        fi
      done < /proc/net/arp
    fi
  done

  # 获取热点网关 IP
  GATEWAY_IP="192.168.43.1"
  if [ -n "$ACTIVE_IFACE" ]; then
    _ip=$(ip -4 addr show "$ACTIVE_IFACE" 2>/dev/null | grep -oE 'inet [0-9.]+' | head -n 1 | cut -d' ' -f2)
    if [ -n "$_ip" ] && [ "$_ip" != "127.0.0.1" ]; then
      GATEWAY_IP="$_ip"
    fi
  fi

  if [ "$FOUND_CLIENT" -eq 1 ]; then
    if [ "$CURRENT_CLIENTS" != "$LAST_CONNECTED_CLIENTS" ]; then
      first_client=$(echo "$CURRENT_CLIENTS" | awk '{print $1}')
      c_ip=$(echo "$first_client" | cut -d'|' -f1)
      c_mac=$(echo "$first_client" | cut -d'|' -f2)
      cat <<EOF > "$STATE_FILE"
{
  "hotspot_active": true,
  "client_ip": "${c_ip:-unknown}",
  "client_mac": "${c_mac:-unknown}",
  "gateway_ip": "$GATEWAY_IP",
  "virtual_public_ip": "$VIRTUAL_PUBLIC_IP",
  "tesla_url": "http://t.carwith.com",
  "tesla_ip_url": "http://$VIRTUAL_PUBLIC_IP",
  "tesla_backup_url": "http://${GATEWAY_IP}:8080/tesla",
  "last_updated": $(date +%s)
}
EOF
      LAST_CONNECTED_CLIENTS="$CURRENT_CLIENTS"
    fi
  else
    if [ -n "$LAST_CONNECTED_CLIENTS" ]; then
      cat <<EOF > "$STATE_FILE"
{
  "hotspot_active": false,
  "client_ip": "",
  "client_mac": "",
  "gateway_ip": "$GATEWAY_IP",
  "virtual_public_ip": "$VIRTUAL_PUBLIC_IP",
  "tesla_url": "http://t.carwith.com",
  "tesla_ip_url": "http://$VIRTUAL_PUBLIC_IP",
  "tesla_backup_url": "http://${GATEWAY_IP}:8080/tesla",
  "last_updated": $(date +%s)
}
EOF
      LAST_CONNECTED_CLIENTS=""
    fi
  fi

  sleep 2
done
