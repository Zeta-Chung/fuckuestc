function FindProxyForURL(url, host) {
  if (shExpMatch(host, "*.uestc.edu.cn") || host === "uestc.edu.cn") {
    return "PROXY 127.0.0.1:8082";
  }
  return "DIRECT";
}