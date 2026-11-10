#!/usr/bin/env python3
"""Loopback-only, read-only synthetic API for README screenshots. NOT a backend.
Run with Python 3, then point a local Neton frontend's NETON_BACKEND_URL at
http://127.0.0.1:3132. No authentication, database access, or write APIs.
"""
import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

NOW = int(datetime(2026, 11, 10, 9, tzinfo=timezone.utc).timestamp() * 1000)
APP = "pulse_demo_ios"
PAGES = [("overview", "数据概览"), ("apps", "应用管理"), ("events", "事件浏览"),
         ("crash-issues", "崩溃问题"), ("performance", "性能观测"), ("versions", "版本对比"),
         ("audit", "运行时审计"), ("alerts", "质量告警"), ("pipeline", "采集管道")]
MENUS = [dict(id=100, name="Pulse", type=1, parentId=0, path="/pulse", component=None,
              icon="Activity", sort=1, status=1, visible=True, keepAlive=True,
              children=[dict(id=101+i, name=name, type=2, parentId=100, path=key,
                             component=f"pulse/{key}/index", sort=i, status=1,
                             visible=True, keepAlive=True) for i, (key, name) in enumerate(PAGES)])]

def paged(rows):
    return dict(list=rows, total=len(rows), page=1, size=20, totalPages=1)

ISSUES = []
for i, (title, message, count, devices, status) in enumerate([
    ("SIGSEGV · ImagePipeline", "ImageCache::decode — invalid memory access", 126, 82, 1),
    ("NSInvalidArgumentException", "-[ProductCell setViewModel:]: unrecognized selector", 48, 35, 0),
    ("CheckoutTimeout", "Checkout request exceeded the configured timeout", 38, 27, 0),
    ("SIGABRT · CartStore", "Assertion failed while merging cart snapshots", 20, 12, 2),
    ("ResourceUnavailable", "Image resource unavailable after cache eviction", 11, 8, 3),
]):
    ISSUES.append(dict(id=i+1, projectId=APP, appKey=APP, fingerprint=f"demo{i+1:028x}",
                       title=title, kind="Error" if i in (2, 4) else "Crash", message=message,
                       buildNumber=240 if i < 3 else 239, firstSeenAt=NOW-86400000*(i+1),
                       lastSeenAt=NOW-60000*(i+1), eventCount=count, deviceCount=devices, status=status))
VERSIONS = []
for build, version, devices, sessions, crashed, crashes, new in [
    (240,"2.4.0",12480,38720,117,174,2), (239,"2.3.9",18600,62400,74,106,1),
    (238,"2.3.8",15200,48900,91,132,2), (237,"2.3.7",9800,30200,88,119,3)]:
    VERSIONS.append(dict(buildNumber=build, appVersion=version, devices=devices, sessions=sessions,
                         events=sessions*12, crashes=crashes, errors=42, crashedDevices=crashed,
                         crashedSessions=crashes, newIssues=new, firstSeenAt=NOW-86400000*(241-build),
                         lastSeenAt=NOW-60000, crashFreeDeviceRate=(devices-crashed)*100/devices,
                         crashFreeSessionRate=(sessions-crashes)*100/sessions))
OVERVIEW = dict(totalEvents=1286340, totalUsers=28460, identifiedUsers=19320, sessions=98420,
                todayActiveUsers=12480, todayNewUsers=1680, todaySessions=18420, todayLaunches=21360,
                crashes=194, errors=49, timezone="Asia/Shanghai",
                activeTrend=[dict(date=f"2026-11-{d:02d}", activeUsers=a, newUsers=n,
                                  sessions=int(a*1.5), launches=int(a*1.7)) for d,a,n in
                             [(4,8240,920),(5,9160,1080),(6,8840,1020),(7,10320,1290),
                              (8,11260,1430),(9,11840,1520),(10,12480,1680)]],
                byKind=[dict(kind=k,count=n) for k,n in [("Analytics",1100000),("Runtime",90000),
                                                         ("Performance",76000),("Network",20097),
                                                         ("Crash",194),("Error",49)]],
                byDomain=[dict(domain=k,count=n) for k,n in [("analytics",1100000),("apm",96340),("security",90000)]],
                topEvents=[dict(name=k,count=n) for k,n in [("product_view",186420),("search",98460),
                                                          ("add_to_cart",42800),("purchase",12640)]],
                recentIssues=[dict(id=x["id"],eventId=f'demo-event-{x["id"]}',sessionId=f'demo-session-{x["id"]:04d}',
                                   ts=x["lastSeenAt"],kind=x["kind"],name=x["title"],sourceModule="DemoApp",
                                   attributes=json.dumps({"message":x["message"]})) for x in ISSUES[:3]])
SUMMARY = dict(online=864,activeLast5Minutes=2160,sdkVersions=[dict(sdkVersion="0.3.0",devices=12480,sessions=18420)])
DATA = {
    "/admin/system/auth/get-permission-info":dict(user=dict(userId="demo",username="demo",nickname="模拟数据 · Demo",avatar="",homePath="/pulse/overview"),roles=["super_admin"],permissions=["*:*:*"],menus=MENUS),
    "/admin/system/site/public":dict(name="PulseKit · DEMO",tagline="模拟数据 / Synthetic data"),
    "/admin/system/health/ready":dict(status="ready",demo=True),
    "/admin/pulse/app/page":paged([dict(id=1,name="Demo Shop · 模拟数据",platform="ios",appKey=APP)]),
    "/admin/pulse/stats/overview":OVERVIEW,
    "/admin/pulse/connection/summary":SUMMARY,
    "/admin/pulse/version/list":VERSIONS,
    "/admin/pulse/version/compare":dict(current=VERSIONS[0],baseline=VERSIONS[1],
        crashFreeDeviceDelta=VERSIONS[0]["crashFreeDeviceRate"]-VERSIONS[1]["crashFreeDeviceRate"],
        crashFreeSessionDelta=VERSIONS[0]["crashFreeSessionRate"]-VERSIONS[1]["crashFreeSessionRate"],
        regressions=[dict(issueId=x["id"],title=x["title"],message=x["message"],fingerprint=x["fingerprint"],devices=x["deviceCount"],events=x["eventCount"]) for x in ISSUES[:2]]),
    "/admin/pulse/crash/page":paged(ISSUES),
    "/admin/pulse/crash/summary":dict(total=5,open=2,inProgress=1,resolved=1,ignored=1,crash=3,error=2,devices=164),
    "/admin/pulse/pipeline/status":dict(depth=dict(work=24,retry=2,processing=4,dead=1,truncatedAt=0),counters=dict(enqueued=201460,consumed=201429,failed=12,dead=1),hasDeadLetters=True),
    "/admin/pulse/pipeline/dead-letters":[dict(payload=json.dumps({"appId":APP,"batchId":"demo-batch-0042","events":32}),attempts=5,firstSeenMs=NOW-600000,lastError="DEMO: database write timeout; safe to replay after recovery")],
    "/admin/pulse/connection/online":[dict(connectionId=f"demo-connection-{i}",projectId=APP,deviceId=f"demo-device-{i:06d}",installationId=f"demo-install-{i}",platform="ios",deviceType=device,packageName="com.example.demoshop",appVersion="2.4.0",buildNumber=240,sdkVersion="0.3.0",capabilities=["analytics","apm","runtime"],connectedAt=NOW-3600000,lastHeartbeatAt=NOW-10000) for i,device in enumerate(["iPhone 16 Pro","iPhone 15","iPad Air"],1)],
}

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path=urlsplit(self.path).path
        if path not in DATA:
            self.send_response(404); self.end_headers(); return
        raw=json.dumps(dict(code=0,data=DATA[path]),ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Cache-Control","no-store")
        self.send_header("Content-Length",str(len(raw)))
        self.end_headers(); self.wfile.write(raw)

if __name__ == "__main__":
    print("Synthetic README API: http://127.0.0.1:3132 (read-only; no real data)",flush=True)
    ThreadingHTTPServer(("127.0.0.1",3132),Handler).serve_forever()
