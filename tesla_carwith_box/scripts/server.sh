#!/system/bin/sh
################################################################################
# Tesla CarWith Box - Native Bridge Daemon Manager (Zero PC Dependency)
################################################################################

MODDIR="/data/adb/modules/tesla_carwith_box"
DATADIR="/data/adb/tesla_box"
mkdir -p "$DATADIR"
PID_FILE="$DATADIR/server.pid"
LOG_FILE="$DATADIR/server.log"
PORT="${2:-8080}"

case "$1" in
  start)
    if [ -f "$PID_FILE" ]; then
      old_pid=$(cat "$PID_FILE")
      if [ -d "/proc/$old_pid" ]; then
        echo "Server already running (PID: $old_pid)"
        exit 0
      fi
      rm -f "$PID_FILE"
    fi

    # 清除旧进程残留
    pkill -f "com.tesla.carwith.Main" 2>/dev/null
    pkill -f "busybox httpd" 2>/dev/null

    echo "Starting Tesla Bridge Native Daemon on port $PORT..."
    
    # 放行防火墙与热点访问
    "$MODDIR/scripts/tesla_control.sh" unblock_hotspot >/dev/null 2>&1
    sleep 0.2

    # 确保清除小白点与旧版残留的屏幕ID
    settings put system show_touches 0 2>/dev/null
    rm -f /data/local/tmp/tesla_display_id 2>/dev/null

    # 初始化并同步 bdcf 车机音频配置
    A_MODE=$(cat "$DATADIR/audio_mode" 2>/dev/null || echo "native")
    T_MODE=1
    if [ "$A_MODE" = "virtual" ] || [ "$A_MODE" = "web" ]; then
      T_MODE=0
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

    # 确保车机端后台静默运行
    if ! pidof com.baidu.carlifevehicle >/dev/null 2>&1; then
      am start -n com.baidu.carlifevehicle/.CarlifeActivity >/dev/null 2>&1
      sleep 1.2
      input keyevent 3 2>/dev/null
      settings put system show_touches 0 2>/dev/null
    fi

    # 启动原生 ART app_process 守护进程
    CLASSPATH="$MODDIR/tesla_bridge.jar" ANDROID_DATA=/data/local/tmp nohup /system/bin/app_process \
      /system/bin \
      com.tesla.carwith.Main "$PORT" "$MODDIR/webroot" > "$LOG_FILE" 2>&1 &
    
    echo $! > "$PID_FILE"
    cp -f "$LOG_FILE" /data/local/tmp/tesla_server.log 2>/dev/null
    chmod 0666 /data/local/tmp/tesla_server.log 2>/dev/null
    echo "Tesla Bridge Native Daemon started (PID: $(cat "$PID_FILE"))"
    ;;

  stop)
    if [ -f "$PID_FILE" ]; then
      pid=$(cat "$PID_FILE")
      echo "Stopping server (PID: $pid)..."
      kill -9 "$pid" 2>/dev/null
      rm -f "$PID_FILE"
    fi
    pkill -f "com.tesla.carwith.Main" 2>/dev/null
    echo "Server stopped."
    ;;

  status)
    if [ -f "$PID_FILE" ]; then
      pid=$(cat "$PID_FILE")
      if [ -d "/proc/$pid" ]; then
        echo "Running (PID: $pid)"
        exit 0
      fi
    fi
    if pgrep -f "com.tesla.carwith.Main" >/dev/null 2>&1; then
      echo "Running"
      exit 0
    fi
    echo "Stopped"
    exit 1
    ;;

  *)
    echo "Usage: $0 {start|stop|status} [port]"
    exit 1
    ;;
esac
