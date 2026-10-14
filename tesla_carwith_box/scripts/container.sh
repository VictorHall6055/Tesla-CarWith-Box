#!/system/bin/sh
################################################################################
# Tesla CarWith Box - Android Container Manager (虚拟车机容器管理引擎)
# 基于 Android Multi-User 容器化隔离，运行独立车机端组件，绝不抢占宿主手机屏幕
################################################################################

CONTAINER_NAME="CarBox"
DATADIR="/data/adb/tesla_box"
mkdir -p "$DATADIR"

get_container_id() {
  pm list users 2>/dev/null | grep -i "$CONTAINER_NAME" | grep -oE 'UserInfo\{[0-9]+' | grep -oE '[0-9]+' | head -n 1
}

case "$1" in
  init|create|start)
    # 彻底清理残留 CarBox 多用户，防止双实例端口抢占导致 CarWith 掉线
    for uid in $(pm list users 2>/dev/null | grep -i "$CONTAINER_NAME" | grep -oE 'UserInfo\{[0-9]+' | grep -oE '[0-9]+'); do
      am stop-user -w "$uid" 2>/dev/null
      pm remove-user "$uid" 2>/dev/null
    done

    # 确保主用户 (User 0) CarLife 组件正常可用
    pm enable --user 0 com.baidu.carlife.xiaomi >/dev/null 2>&1
    pm grant com.baidu.carlifevehicle android.permission.RECORD_AUDIO 2>/dev/null
    pm grant com.baidu.carlifevehicle android.permission.ACCESS_FINE_LOCATION 2>/dev/null
    pm grant com.baidu.carlifevehicle android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
    pm grant com.baidu.carlifevehicle android.permission.READ_PHONE_STATE 2>/dev/null

    # 预留通信接口防火墙放行 (7240, 8240, 9340, 7999, 8080, 5354)
    iptables -I INPUT 1 -p tcp --dport 7240 -j ACCEPT 2>/dev/null
    iptables -I INPUT 1 -p tcp --dport 8240 -j ACCEPT 2>/dev/null
    iptables -I INPUT 1 -p tcp --dport 9340 -j ACCEPT 2>/dev/null
    iptables -I INPUT 1 -p udp --dport 7999 -j ACCEPT 2>/dev/null
    iptables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null
    iptables -I INPUT 1 -p udp --dport 5354 -j ACCEPT 2>/dev/null
    iptables -I tetherctrl_INPUT 1 -p tcp --dport 7240 -j ACCEPT 2>/dev/null
    iptables -I tetherctrl_INPUT 1 -p tcp --dport 8240 -j ACCEPT 2>/dev/null
    iptables -I tetherctrl_INPUT 1 -p tcp --dport 9340 -j ACCEPT 2>/dev/null
    iptables -I tetherctrl_INPUT 1 -p udp --dport 7999 -j ACCEPT 2>/dev/null
    iptables -I tetherctrl_INPUT 1 -p tcp --dport 8080 -j ACCEPT 2>/dev/null
    iptables -I tetherctrl_INPUT 1 -p udp --dport 5354 -j ACCEPT 2>/dev/null

    echo "{\"status\": \"ok\", \"container_id\": 0, \"isolated\": false, \"mode\": \"headless_user0\", \"message\": \"container_purged_user0_active\"}"
    exit 0
    ;;

  status)
    cid=$(get_container_id)
    if [ -n "$cid" ]; then
      is_running=0
      if pm list users 2>/dev/null | grep -i "$CONTAINER_NAME" | grep -q "running"; then
        is_running=1
      fi
      pkgs=$(pm list packages --user "$cid" 2>/dev/null | cut -d':' -f2 | tr '\n' ',' | sed 's/,$//')
      cat <<EOF
{
  "active": true,
  "container_id": $cid,
  "running": $is_running,
  "packages": "${pkgs}",
  "reserved_ports": [7240, 8240, 9340, 7999, 8080]
}
EOF
    else
      echo "{\"active\": false, \"container_id\": null, \"running\": 0, \"packages\": \"\"}"
    fi
    ;;

  install)
    apk_path="$2"
    cid=$(get_container_id)
    if [ -z "$cid" ]; then
      out=$(pm create-user "$CONTAINER_NAME" 2>&1)
      cid=$(echo "$out" | grep -oE '[0-9]+' | tail -n 1)
      [ -n "$cid" ] && am start-user "$cid" >/dev/null 2>&1
    fi

    if [ -z "$cid" ]; then
      echo "{\"status\": \"error\", \"message\": \"failed_to_get_container\"}"
      exit 1
    fi

    if [ -f "$apk_path" ]; then
      pm install --user "$cid" -r "$apk_path"
      echo "{\"status\": \"ok\", \"container_id\": $cid, \"apk\": \"$apk_path\"}"
    else
      pkg_name="$apk_path"
      if [ -n "$pkg_name" ]; then
        pm install-existing --user "$cid" "$pkg_name" 2>/dev/null
        echo "{\"status\": \"ok\", \"container_id\": $cid, \"pkg\": \"$pkg_name\"}"
      else
        echo "{\"status\": \"error\", \"message\": \"file_or_pkg_not_found\"}"
      fi
    fi
    ;;

  launch)
    target="$2"
    cid=$(get_container_id)
    if [ -z "$cid" ]; then
      echo "{\"status\": \"error\", \"message\": \"container_not_initialized\"}"
      exit 1
    fi
    # 在容器隔离用户中启动，绝不抢占宿主前台屏幕
    am start --user "$cid" "$target" >/dev/null 2>&1
    echo "{\"status\": \"ok\", \"launched_in_user\": $cid}"
    ;;

  stop)
    pkg="$2"
    cid=$(get_container_id)
    if [ -n "$cid" ] && [ -n "$pkg" ]; then
      am force-stop --user "$cid" "$pkg" >/dev/null 2>&1
    fi
    echo "{\"status\": \"ok\"}"
    ;;

  remove)
    cid=$(get_container_id)
    if [ -n "$cid" ]; then
      am stop-user -w "$cid" >/dev/null 2>&1
      pm remove-user "$cid" >/dev/null 2>&1
      # 恢复宿主组件
      pm enable --user 0 com.baidu.carlife.xiaomi >/dev/null 2>&1
      echo "{\"status\": \"ok\", \"removed_user\": $cid}"
    else
      echo "{\"status\": \"ok\", \"message\": \"container_not_found\"}"
    fi
    ;;

  *)
    echo "Usage: $0 {init|start|status|install <path/pkg>|launch <intent>|stop <pkg>|remove}"
    exit 1
    ;;
esac
