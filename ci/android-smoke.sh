#!/usr/bin/env bash
set -euo pipefail

apk=app/build/outputs/apk/debug/app-debug.apk
package=com.wboat.todo
screen=/sdcard/todo-smoke.xml
adb install -r "$apk"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -W -n "$package/.MainActivity"

for attempt in {1..10}; do
    if adb shell uiautomator dump "$screen" >/dev/null 2>&1; then
        adb shell cat "$screen" > todo-smoke.xml
        if grep -Fq 'package="com.wboat.todo"' todo-smoke.xml &&
           grep -Fq '记下待办' todo-smoke.xml &&
           grep -Fq '识别待办' todo-smoke.xml; then
            exit 0
        fi
    fi
    sleep 2
done

adb logcat -d -s AndroidRuntime ActivityTaskManager | tail -n 100
echo "The to-do entry screen did not appear" >&2
exit 1
