from pathlib import Path

p = Path("app/src/main/java/com/lineageos/launcherenhance/MainActivity.java")
s = p.read_text(encoding="utf-8")

anchor1 = '''        addSection("桌面与文件夹");
        addSwitch("隐藏首屏搜索框 / At A Glance", ConfigKeys.HIDE_QSB, false);
        addSwitch("打开文件夹时强制屏幕居中", ConfigKeys.CENTER_FOLDER, false);
'''
replace1 = '''        addSection("桌面与文件夹");
        addSwitch("隐藏首屏搜索框 / At A Glance", ConfigKeys.HIDE_QSB, false);
        addSwitch("打开文件夹时强制屏幕居中", ConfigKeys.CENTER_FOLDER, false);
        addSwitch("壁纸随桌面滚动", ConfigKeys.ALLOW_WALLPAPER_SCROLLING, false);
'''

anchor2 = '''        addSection("交互效果");
        addSwitch("启用自定义图标按压缩放", ConfigKeys.PRESS_SCALE_ENABLED, false);
        addSeek("按下时图标缩放", ConfigKeys.PRESS_SCALE_PERCENT,
                80, 100, ConfigKeys.DEFAULT_PRESS_SCALE, "%");

        addSection("最近任务");
'''
replace2 = '''        addSection("交互效果");
        addSwitch("启用自定义图标按压缩放", ConfigKeys.PRESS_SCALE_ENABLED, false);
        addSeek("按下时图标缩放", ConfigKeys.PRESS_SCALE_PERCENT,
                80, 100, ConfigKeys.DEFAULT_PRESS_SCALE, "%");

        addSection("系统手势");
        addSwitch("三指下滑截图", ConfigKeys.THREE_FINGER_SCREENSHOT, true);

        addSection("最近任务");
'''

if anchor1 not in s:
    raise SystemExit("desktop settings anchor not found")
if anchor2 not in s:
    raise SystemExit("interaction settings anchor not found")

s = s.replace(anchor1, replace1, 1)
s = s.replace(anchor2, replace2, 1)
p.write_text(s, encoding="utf-8")
print("Patched MainActivity: wallpaper scrolling + three-finger screenshot switches")
