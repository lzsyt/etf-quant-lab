#!/usr/bin/env bash
set -euo pipefail
# 仅转发管理台回环端口；固定主机密钥，账号无管理员权限。
exec ssh -i "${ETF_DEPLOY_SSH_KEY:-$HOME/.ssh/etf_quant_lab_ed25519}" \
  -o "UserKnownHostsFile=${ETF_DEPLOY_KNOWN_HOSTS:-$HOME/.ssh/etf_quant_lab_known_hosts}" \
  -o BatchMode=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes \
  -o ConnectTimeout=10 -o ConnectionAttempts=1 \
  -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -N -L 127.0.0.1:18088:127.0.0.1:18080 etfdeploy@example.invalid
