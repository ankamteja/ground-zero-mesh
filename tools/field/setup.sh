#!/usr/bin/env bash
# Generate the netsim gRPC stubs netsimctl.py needs.
#
# The .proto files under proto/ are vendored from AOSP platform/tools/netsim
# (Apache 2.0) so this harness does not depend on an AOSP checkout. They describe
# the frontend service the emulator's netsimd already exposes -- nothing here
# patches or replaces any Android component.
#
# Everything lands in tools/field/venv and tools/field/gen, both gitignored.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
venv="$here/venv"
gen="$here/gen"

python3 -m venv "$venv"
"$venv/bin/pip" install --quiet --upgrade pip
"$venv/bin/pip" install --quiet grpcio grpcio-tools

mkdir -p "$gen"
"$venv/bin/python" -m grpc_tools.protoc \
  --proto_path="$here/proto" \
  --python_out="$gen" \
  --grpc_python_out="$gen" \
  "$here/proto/netsim/common.proto" \
  "$here/proto/netsim/model.proto" \
  "$here/proto/netsim/frontend.proto" \
  "$here/proto/rootcanal/configuration.proto"

# model.proto imports "netsim/common.proto" and "rootcanal/configuration.proto",
# so the proto directory layout has to mirror those import paths, and the
# generated modules land in gen/netsim/ and gen/rootcanal/. netsimctl.py puts
# gen/ on sys.path and imports them as `from netsim import ...`.
echo "stubs generated in $gen"
echo "run: $here/netsimctl.py list"
