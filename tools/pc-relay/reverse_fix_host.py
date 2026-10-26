"""反向代理时把 Host 头改回真实域名。

背景：mitmproxy 12 没有 DNS 覆盖选项，若 hosts 把域名指向本机（为了截获），
上游也会被解析成本机，形成「自己连自己」。
因此改为：上游直接填服务器 IP，再用本插件把 Host 头改回真实域名，
这样校园网关按域名正常响应，票据也能被 ticket_saver.py 抓到。
"""

from mitmproxy import http

TARGET_HOST = "jzsz.uestc.edu.cn"


def request(flow: http.HTTPFlow) -> None:
    flow.request.headers["Host"] = TARGET_HOST
    # 让日志里也能看出这是哪个域名的请求
    flow.request.headers.setdefault("X-Relay-Host", TARGET_HOST)
