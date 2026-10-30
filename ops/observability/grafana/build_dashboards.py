#!/usr/bin/env python3
"""Builds the PayPort Operations Control Centre dashboards (grafana/dashboards/*.json).

Run after changing a panel:  python3 grafana/build_dashboards.py
The JSON files are what Grafana loads; this script keeps the five dashboards consistent (same colours, sizes and
naming) so they read well on a large TV from across the room.
"""
import json
import os

PROM = {"type": "prometheus", "uid": "prom"}
LOKI = {"type": "loki", "uid": "loki"}
GREEN, AMBER, RED, BLUE, GREY, PURPLE = "#22c55e", "#f59e0b", "#ef4444", "#3b82f6", "#64748b", "#a855f7"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboards")

_id = [0]


def nid():
    _id[0] += 1
    return _id[0]


def steps(*pairs):
    s = [{"color": pairs[0], "value": None}]
    for i in range(1, len(pairs), 2):
        s.append({"color": pairs[i + 1], "value": pairs[i]})
    return {"mode": "absolute", "steps": s}


def target(expr, legend="", instant=False, ds=PROM, fmt=None):
    t = {"datasource": ds, "expr": expr, "legendFormat": legend, "refId": chr(65 + nid() % 26)}
    if instant:
        t["instant"] = True
        t["range"] = False
    if fmt:
        t["format"] = fmt
    return t


def stat(title, exprs, x, y, w=4, h=5, unit="none", thresholds=None, color=BLUE, mappings=None, decimals=None, text_size=None, legend=""):
    exprs = exprs if isinstance(exprs, list) else [exprs]
    p = {
        "id": nid(), "type": "stat", "title": title, "datasource": PROM, "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [target(e, legend) for e in exprs],
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "colorMode": "value",
                    "graphMode": "none", "justifyMode": "center", "textMode": "auto", "orientation": "auto",
                    "text": {"valueSize": text_size} if text_size else {}},
        "fieldConfig": {"defaults": {"unit": unit, "color": {"mode": "thresholds"},
                                     "thresholds": thresholds or steps(color), "mappings": mappings or [],
                                     "noValue": "0"}, "overrides": []},
    }
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    return p


def timeseries(title, items, x, y, w=12, h=8, unit="none", stack=False, colors=None, fill=15):
    overrides = []
    for name, col in (colors or {}).items():
        overrides.append({"matcher": {"id": "byName", "options": name},
                          "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": col}}]})
    return {
        "id": nid(), "type": "timeseries", "title": title, "datasource": PROM, "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [target(e, l) for e, l in items],
        "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True}, "tooltip": {"mode": "multi"}},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": fill, "showPoints": "never",
                                                              "stacking": {"mode": "normal" if stack else "none"}}},
                        "overrides": overrides},
    }


def bargauge(title, expr, legend, x, y, w=12, h=8, unit="none", thresholds=None, minv=None, maxv=None, mappings=None):
    d = {"unit": unit, "color": {"mode": "thresholds"}, "thresholds": thresholds or steps(BLUE), "mappings": mappings or []}
    if minv is not None:
        d["min"] = minv
    if maxv is not None:
        d["max"] = maxv
    return {
        "id": nid(), "type": "bargauge", "title": title, "datasource": PROM, "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [target(expr, legend, instant=True)],
        "options": {"orientation": "horizontal", "displayMode": "gradient", "showUnfilled": True, "valueMode": "color",
                    "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "namePlacement": "left"},
        "fieldConfig": {"defaults": d, "overrides": []},
    }


def tiles(title, expr, legend, x, y, w=24, h=5, mappings=None, thresholds=None, unit="none"):
    """One coloured tile per series (providers, servers)."""
    p = stat(title, expr, x, y, w, h, unit=unit, thresholds=thresholds, mappings=mappings, legend=legend)
    p["targets"][0]["instant"] = True
    p["options"]["textMode"] = "value_and_name"
    p["options"]["colorMode"] = "background"
    p["options"]["orientation"] = "vertical"
    return p


def pie(title, items, x, y, w=6, h=8, colors=None):
    p = timeseries(title, items, x, y, w, h, colors=colors)
    p["type"] = "piechart"
    p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "pieType": "donut",
                    "legend": {"displayMode": "table", "placement": "right", "values": ["value", "percent"], "showLegend": True},
                    "displayLabels": []}
    for t in p["targets"]:
        t["instant"] = True
    return p


def table(title, expr, x, y, w=12, h=8, columns=None, ds=PROM):
    p = {
        "id": nid(), "type": "table", "title": title, "datasource": ds, "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [target(expr, "", instant=True, fmt="table")],
        "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}},
        "fieldConfig": {"defaults": {"custom": {"align": "auto"}}, "overrides": []},
        "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True, "__name__": True, "job": True, "instance": False, "Value": True, "alertstate": True},
                                                          "renameByName": columns or {}}}],
    }
    return p


def logs(title, expr, x, y, w=24, h=9):
    return {"id": nid(), "type": "logs", "title": title, "datasource": LOKI, "gridPos": {"x": x, "y": y, "w": w, "h": h},
            "targets": [{"datasource": LOKI, "expr": expr, "refId": "A"}],
            "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True, "dedupStrategy": "none"}}


def text(title, content, x, y, w, h):
    return {"id": nid(), "type": "text", "title": title, "gridPos": {"x": x, "y": y, "w": w, "h": h},
            "options": {"mode": "markdown", "content": content}}


PROVIDER_MAP = [{"type": "value", "options": {"1": {"text": "Operational", "color": GREEN}, "0": {"text": "Down", "color": RED},
                                              "-1": {"text": "Not checked", "color": GREY}}}]
UP_MAP = [{"type": "value", "options": {"1": {"text": "Up", "color": GREEN}, "0": {"text": "Down", "color": RED}}}]
STATUS_MAP = [{"type": "value", "options": {"1": {"text": "OPERATIONAL", "color": GREEN}, "0": {"text": "DEGRADED", "color": RED}}}]

FIRING = 'ALERTS{alertstate="firing"}'


def dashboard(uid, title, panels, refresh="30s"):
    return {
        "uid": uid, "title": title, "tags": ["payport", "control-centre"], "timezone": "browser", "schemaVersion": 39,
        "editable": False, "graphTooltip": 1, "refresh": refresh, "time": {"from": "now-6h", "to": "now"},
        "style": "dark", "panels": panels,
        "links": [{"title": "Control Centre", "type": "dashboards", "tags": ["control-centre"], "asDropdown": True, "includeVars": False}],
    }


def overview():
    _id[0] = 0
    up = ('(min(up{job="payport-gateway"}) or vector(0)) * (min(probe_success{job="payport-public"}) or vector(0)) '
          '* (min(mysql_up) or vector(0))')
    o = lambda k: f'sum(payport_cc_transactions_today_by_outcome{{outcome="{k}"}})'
    p = [
        stat("Platform status", up, 0, 0, 6, 5, mappings=STATUS_MAP, thresholds=steps(RED, 1, GREEN), text_size=34),
        stat("Transactions today", "sum(payport_cc_transactions_today)", 6, 0, 3, 5, color=BLUE),
        stat("Successful", o("successful"), 9, 0, 3, 5, color=GREEN),
        stat("Failed", o("failed"), 12, 0, 3, 5, color=RED),
        stat("Pending", o("pending"), 15, 0, 3, 5, color=AMBER),
        stat("Success rate (1h)", "max(payport_cc_success_rate_1h)", 18, 0, 3, 5, unit="percentunit", decimals=1,
             thresholds=steps(RED, 0.8, AMBER, 0.95, GREEN)),
        stat("Active incidents", 'count(ALERTS{alertstate="firing", severity="critical"}) or vector(0)', 21, 0, 3, 5, thresholds=steps(GREEN, 1, RED)),
        timeseries("Transaction processing volume (per minute)", [("sum(payport_cc_transactions_last_5m) / 5", "Transactions / min")], 0, 5, 12, 8,
                   colors={"Transactions / min": BLUE}),
        timeseries("Today by outcome", [(o("successful"), "Successful"), (o("failed"), "Failed"), (o("pending"), "Pending"), (o("refunded"), "Refunded")],
                   12, 5, 6, 8, colors={"Successful": GREEN, "Failed": RED, "Pending": AMBER, "Refunded": PURPLE}),
        pie("Outcome split", [(o("successful"), "Successful"), (o("failed"), "Failed"), (o("pending"), "Pending"), (o("refunded"), "Refunded")],
            18, 5, 6, 8, colors={"Successful": GREEN, "Failed": RED, "Pending": AMBER, "Refunded": PURPLE}),
        tiles("Payment provider availability", "max by (provider) (payport_cc_provider_up)", "{{provider}}", 0, 13, 24, 5,
              mappings=PROVIDER_MAP, thresholds=steps(GREY, 0, RED, 1, GREEN)),
        table("Current system alerts", FIRING, 0, 18, 12, 7, columns={"alertname": "Alert", "severity": "Severity", "provider": "Provider", "name": "Target"}),
        stat("Alerts firing", f"count({FIRING}) or vector(0)", 12, 18, 4, 7, thresholds=steps(GREEN, 1, AMBER, 3, RED), text_size=48),
        stat("Complaints open", "max(payport_cc_complaints_open)", 16, 18, 4, 7, color=BLUE, text_size=48),
        stat("Complaints overdue", "max(payport_cc_complaints_overdue)", 20, 18, 4, 7, thresholds=steps(GREEN, 1, RED), text_size=48),
    ]
    return dashboard("pp-cc-overview", "1. Executive Operations Overview", p)


def infrastructure():
    _id[0] = 0
    cpu = '100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])))'
    mem = '100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)'
    disk = '100 * (1 - node_filesystem_avail_bytes{fstype!~"tmpfs|overlay|squashfs|nsfs"} / node_filesystem_size_bytes{fstype!~"tmpfs|overlay|squashfs|nsfs"})'
    pct = steps(GREEN, 70, AMBER, 85, RED)
    p = [
        tiles("Server and service availability", 'max by (job) (up{job=~"payport-gateway|node|mysql|observability"})', "{{job}}", 0, 0, 12, 5,
              mappings=UP_MAP, thresholds=steps(RED, 1, GREEN)),
        stat("CPU", f"max({cpu})", 12, 0, 4, 5, unit="percent", thresholds=pct, decimals=0),
        stat("Memory", f"max({mem})", 16, 0, 4, 5, unit="percent", thresholds=pct, decimals=0),
        stat("Fullest disk", f"max({disk})", 20, 0, 4, 5, unit="percent", thresholds=pct, decimals=0),
        timeseries("CPU and memory utilisation", [(f"max({cpu})", "CPU %"), (f"max({mem})", "Memory %")], 0, 5, 12, 8, unit="percent"),
        bargauge("Disk space used", disk, "{{mountpoint}}", 12, 5, 12, 8, unit="percent", thresholds=pct, minv=0, maxv=100),
        timeseries("Network bandwidth", [
            ('sum(rate(node_network_receive_bytes_total{device!~"lo|docker.*|veth.*|br-.*"}[5m])) * 8', "In"),
            ('sum(rate(node_network_transmit_bytes_total{device!~"lo|docker.*|veth.*|br-.*"}[5m])) * 8', "Out")], 0, 13, 8, 8, unit="bps"),
        timeseries("Network latency (ICMP)", [('probe_icmp_duration_seconds{phase="rtt"}', "{{instance}}")], 8, 13, 8, 8, unit="s", fill=0),
        timeseries("Packet loss", [('100 * (1 - avg_over_time(probe_success{module="icmp"}[5m]))', "{{instance}}"),
                                   ('100 * (1 - avg_over_time(probe_success{job="internet"}[5m]))', "{{instance}}")], 16, 13, 8, 8, unit="percent", fill=0),
        stat("Database", "max(mysql_up)", 0, 21, 4, 5, mappings=UP_MAP, thresholds=steps(RED, 1, GREEN)),
        stat("DB threads connected", "max(mysql_global_status_threads_connected)", 4, 21, 4, 5, color=BLUE),
        stat("Slow queries (1h)", "sum(increase(mysql_global_status_slow_queries[1h]))", 8, 21, 4, 5, thresholds=steps(GREEN, 1, AMBER, 20, RED)),
        timeseries("Gateway database connections", [("max(hikaricp_connections_active)", "Active"), ("max(hikaricp_connections_idle)", "Idle"),
                                                   ("max(hikaricp_connections_pending)", "Waiting"), ("max(hikaricp_connections_max)", "Maximum")], 12, 21, 12, 8),
        timeseries("API response times", [
            ('histogram_quantile(0.5, sum by (le) (rate(http_server_requests_seconds_bucket{job="payport-gateway", uri!~"/actuator.*"}[5m])))', "Median"),
            ('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="payport-gateway", uri!~"/actuator.*"}[5m])))', "95%"),
            ('histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job="payport-gateway", uri!~"/actuator.*"}[5m])))', "99%")],
            0, 26, 12, 8, unit="s", fill=0),
        tiles("Connectivity between services", 'max by (instance) (probe_success{job="payport-public"})', "{{instance}}", 0, 34, 24, 5,
              mappings=UP_MAP, thresholds=steps(RED, 1, GREEN)),
        timeseries("JVM heap", [('sum(jvm_memory_used_bytes{job="payport-gateway", area="heap"})', "Used"),
                                ('sum(jvm_memory_max_bytes{job="payport-gateway", area="heap"})', "Max")], 12, 29, 12, 5, unit="bytes"),
    ]
    return dashboard("pp-cc-infrastructure", "2. Infrastructure and Network", p)


def payments():
    _id[0] = 0
    p = [
        stat("Transactions / minute", "sum(payport_cc_transactions_last_5m) / 5", 0, 0, 4, 5, color=BLUE, decimals=1),
        stat("Success rate (1h)", "max(payport_cc_success_rate_1h)", 4, 0, 4, 5, unit="percentunit", decimals=1, thresholds=steps(RED, 0.8, AMBER, 0.95, GREEN)),
        stat("Waiting for the customer", "max(payport_cc_pending_payments)", 8, 0, 4, 5, color=AMBER),
        stat("Uncertain outcomes", "max(payport_cc_uncertain_payments)", 12, 0, 4, 5, thresholds=steps(GREEN, 1, RED)),
        stat("Refunds today", "max(payport_cc_refunds_today)", 16, 0, 4, 5, color=PURPLE),
        stat("Reversals waiting", "max(payport_cc_reversals_pending)", 20, 0, 4, 5, thresholds=steps(GREEN, 1, AMBER)),
        timeseries("Transactions per minute", [("sum(payport_cc_transactions_last_5m) / 5", "Transactions / min")], 0, 5, 12, 8),
        timeseries("Success and failure today", [('sum(payport_cc_transactions_today_by_outcome{outcome="successful"})', "Successful"),
                                                 ('sum(payport_cc_transactions_today_by_outcome{outcome="failed"})', "Failed")], 12, 5, 12, 8,
                   colors={"Successful": GREEN, "Failed": RED}),
        bargauge("Provider success rate (1h)", "max by (provider) (payport_cc_provider_success_rate_1h)", "{{provider}}", 0, 13, 8, 8,
                 unit="percentunit", thresholds=steps(RED, 0.8, AMBER, 0.95, GREEN), minv=0, maxv=1),
        bargauge("Provider transactions (1h)", "max by (provider) (payport_cc_provider_transactions_1h)", "{{provider}}", 8, 13, 8, 8),
        bargauge("Payment processing latency (1h average)", "max by (provider) (payport_cc_provider_payment_latency_seconds_1h)", "{{provider}}", 16, 13, 8, 8,
                 unit="s", thresholds=steps(GREEN, 30, AMBER, 90, RED)),
        timeseries("Payment API latency", [
            ('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="payport-gateway", uri=~"/api/v1/transactions/(pay|initiate)"}[5m])))', "95% pay/initiate")],
            0, 21, 8, 8, unit="s", fill=0),
        timeseries("Duplicate attempts", [('sum(increase(payport_duplicate_requests_total[1h]))', "Repeated requests (1h)"),
                                          ("max(payport_cc_duplicate_callbacks_today)", "Repeated provider callbacks (today)")], 8, 21, 8, 8, fill=0),
        pie("Today by channel", [("max by (channel) (payport_cc_transactions_today_by_channel)", "{{channel}}")], 16, 21, 8, 8),
        bargauge("Settlement batches", "max by (status) (payport_cc_settlement_batches)", "{{status}}", 0, 29, 12, 6,
                 thresholds=steps(BLUE)),
        stat("Reconciliation exceptions open", "max(payport_cc_recon_exceptions_open)", 12, 29, 12, 6, thresholds=steps(GREEN, 1, AMBER, 10, RED), text_size=48),
    ]
    return dashboard("pp-cc-payments", "3. Payment Processing", p)


def security():
    _id[0] = 0
    ev = lambda e, rng="24h": f'sum(increase(payport_security_events_total{{event="{e}"}}[{rng}])) or vector(0)'
    p = [
        stat("Failed logins (24h)", ev("login_failed"), 0, 0, 4, 5, thresholds=steps(GREEN, 10, AMBER, 50, RED)),
        stat("Wrong PINs (24h)", ev("pin_rejected"), 4, 0, 4, 5, thresholds=steps(GREEN, 20, AMBER, 100, RED)),
        stat("Accounts locked (24h)", ev("account_locked"), 8, 0, 4, 5, thresholds=steps(GREEN, 1, AMBER, 5, RED)),
        stat("Rate-limit violations (24h)", ev("rate_limited"), 12, 0, 4, 5, thresholds=steps(GREEN, 20, AMBER, 200, RED)),
        stat("Fraud alerts open", "sum(payport_cc_risk_alerts_open) or vector(0)", 16, 0, 4, 5, thresholds=steps(GREEN, 1, AMBER, 5, RED)),
        stat("Security incidents", 'count(ALERTS{alertstate="firing", dashboard="security"}) or vector(0)', 20, 0, 4, 5, thresholds=steps(GREEN, 1, RED)),
        timeseries("Authentication failures", [
            ('sum by (who) (increase(payport_security_events_total{event="login_failed"}[15m]))', "Failed logins ({{who}})"),
            ('sum(increase(payport_security_events_total{event="pin_rejected"}[15m]))', "Wrong PINs"),
            ('sum(increase(payport_security_events_total{event="account_locked"}[15m]))', "Locked")], 0, 5, 12, 8, fill=0),
        timeseries("Suspicious API activity", [
            ('sum(rate(http_server_requests_seconds_count{job="payport-gateway", status=~"401|403"}[5m])) * 60', "Refused (401/403) per min"),
            ('sum(increase(payport_security_events_total{event="rate_limited"}[5m]))', "Rate-limited (5 min)"),
            ('sum(increase(payport_security_events_total{event="webhook_signature_invalid"}[5m]))', "Bad webhook signatures (5 min)"),
            ('sum(rate(http_server_requests_seconds_count{job="payport-gateway", status="404"}[5m])) * 60', "Not found per min (probing)")],
            12, 5, 12, 8, fill=0),
        timeseries("Unusual transaction patterns", [
            ("sum(payport_cc_transactions_last_5m) / 5", "Now (per min)"),
            ("avg_over_time((sum(payport_cc_transactions_last_5m) / 5)[1d:5m])", "Usual (24h average)")], 0, 13, 12, 8, fill=0),
        bargauge("Fraud alerts by severity", "max by (severity) (payport_cc_risk_alerts_open)", "{{severity}}", 12, 13, 6, 8,
                 thresholds=steps(AMBER, 5, RED)),
        stat("Privileged actions (24h)", "max(payport_cc_privileged_actions_24h)", 18, 13, 6, 8, color=PURPLE, text_size=48),
        logs("Security log", '{job="payport-gateway"} |~ "(?i)(locked|forbidden|denied|invalid signature|rate_limit|wrong pin|unauthori)"', 0, 21, 24, 10),
    ]
    return dashboard("pp-cc-security", "4. Security and Risk", p)


def integrations():
    _id[0] = 0
    p = [
        tiles("Provider API health (real requests)", "max by (provider) (payport_cc_provider_up)", "{{provider}}", 0, 0, 24, 5,
              mappings=PROVIDER_MAP, thresholds=steps(GREY, 0, RED, 1, GREEN)),
        tiles("Provider and rail connectivity (network)", 'max by (name) (probe_success{job="provider-connectivity", module="tcp_connect"})', "{{name}}", 0, 5, 24, 5,
              mappings=UP_MAP, thresholds=steps(RED, 1, GREEN)),
        timeseries("Provider response times (connect)", [('probe_duration_seconds{job="provider-connectivity", module="tcp_connect"}', "{{name}}")], 0, 10, 12, 8, unit="s", fill=0),
        timeseries("Provider packet loss", [('100 * (1 - avg_over_time(probe_success{job="provider-connectivity", module="icmp"}[10m]))', "{{name}}")], 12, 10, 12, 8,
                   unit="percent", fill=0),
        tiles("Callback addresses reachable", 'max by (name) (probe_success{job="provider-connectivity", kind="callback"})', "{{name}}", 0, 18, 12, 5,
              mappings=UP_MAP, thresholds=steps(RED, 1, GREEN)),
        stat("Callbacks rejected today", "max(payport_cc_failed_callbacks_today)", 12, 18, 4, 5, thresholds=steps(GREEN, 1, AMBER, 10, RED)),
        stat("Repeated callbacks today", "max(payport_cc_duplicate_callbacks_today)", 16, 18, 4, 5, color=BLUE),
        stat("Reconciliation exceptions", "max(payport_cc_recon_exceptions_open)", 20, 18, 4, 5, thresholds=steps(GREEN, 1, AMBER, 10, RED)),
        bargauge("Merchant webhook deliveries (24h)", "max by (status) (payport_cc_webhook_deliveries)", "{{status}}", 0, 23, 12, 7),
        timeseries("Failed integration requests", [
            ('sum by (client_name) (rate(http_client_requests_seconds_count{job="payport-gateway", outcome!="SUCCESS"}[5m])) * 60', "{{client_name}}"),
            ('sum(rate(http_server_requests_seconds_count{job="payport-gateway", uri=~"/api/v1/callbacks/.*", status=~"4..|5.."}[5m])) * 60', "Callbacks refused per min")],
            12, 23, 12, 7, fill=0),
        logs("Integration errors", '{job="payport-gateway", level=~"WARN|ERROR"} |~ "(?i)(ecocash|onemoney|netone|econet|telecel|esolutions|zimswitch|omari|innbucks|callback)"', 0, 30, 24, 9),
    ]
    return dashboard("pp-cc-integrations", "5. Integrations and Providers", p)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for build in (overview, infrastructure, payments, security, integrations):
        d = build()
        with open(os.path.join(OUT, d["uid"] + ".json"), "w") as f:
            json.dump(d, f, indent=2)
        print("wrote", d["uid"], len(d["panels"]), "panels")
