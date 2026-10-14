#!/bin/bash
TOKEN=$(python3 -c "import json;print(json.load(open('/Users/jwalr/Flashback/run/config/mcpfabric.config.json'))['token'])")
P="${2:-}"; [ -z "$P" ] && P='{}'
curl -s -X POST http://127.0.0.1:25599/rpc -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":$P}"
