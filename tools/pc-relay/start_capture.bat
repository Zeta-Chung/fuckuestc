@echo off
rem 启动带取票插件的 mitmweb（默认监听 8080，界面 8081）
cd /d %~dp0
mitmweb -s ticket_saver.py --listen-port 8080