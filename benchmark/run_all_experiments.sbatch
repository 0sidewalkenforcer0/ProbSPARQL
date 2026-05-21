#!/bin/bash

#SBATCH --job-name=probsparql-all
#SBATCH --partition=arboghast
#SBATCH --qos=ac_arboghast
#SBATCH --time=4-00:00:00
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=16
#SBATCH --mem=256G
#SBATCH --output=benchmark/slurm_all_experiments_%j.out
#SBATCH --error=benchmark/slurm_all_experiments_%j.err

set -euo pipefail

# User-local Java/Maven installation shared across login and compute nodes.
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21.0.8+9}"
export MAVEN_HOME="${MAVEN_HOME:-$HOME/tools/apache-maven-3.9.11}"
export PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx220g}"

# This job starts a local Fuseki server on the allocated node and loads all
# prepared benchmark TTL files under benchmark/data as separate services.
FUSEKI_PORT="${FUSEKI_PORT:-3030}"
BENCHMARK_DATA_DIR="${BENCHMARK_DATA_DIR:-benchmark/data}"
DEFAULT_ENDPOINT_TEMPLATE="http://localhost:${FUSEKI_PORT}/"'{dataset}'"/query"
ENDPOINT_TEMPLATE="${ENDPOINT_TEMPLATE:-${DEFAULT_ENDPOINT_TEMPLATE}}"
export ENDPOINT_TEMPLATE

# Submit from the repository root:
#   sbatch benchmark/run_all_experiments.sbatch
#
# This job assumes all benchmark TTL datasets have already been generated under
# BENCHMARK_DATA_DIR. It does not run any data-generation commands.

if [[ -n "${SLURM_SUBMIT_DIR:-}" ]]; then
  PROJECT_ROOT="${SLURM_SUBMIT_DIR}"
else
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
fi

cd "${PROJECT_ROOT}"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v /usr/libexec/java_home &>/dev/null; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)"
  else
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
  fi
  export JAVA_HOME
fi

export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"

endpoint_for_dataset() {
  local dataset="$1"
  if [[ "${ENDPOINT_TEMPLATE}" == *"{dataset}"* ]]; then
    printf '%s\n' "${ENDPOINT_TEMPLATE/\{dataset\}/${dataset}}"
  else
    printf '%s\n' "${ENDPOINT_TEMPLATE}"
  fi
}

check_dataset_endpoint() {
  local dataset="$1"
  local endpoint
  endpoint="$(endpoint_for_dataset "${dataset}")"

  curl -fsS \
    -H "Accept: application/sparql-results+json" \
    --data-urlencode 'query=ASK { ?s ?p ?o }' \
    "${endpoint}" >/dev/null 2>&1
}

check_required_datasets() {
  local datasets=(
    exp1_E1_det exp1_E3_det exp1_E5_det exp1_E7_det
    exp1_E1_K1 exp1_E1_K3 exp1_E1_K5 exp1_E1_K10
    exp1_E3_K1 exp1_E3_K3 exp1_E3_K5 exp1_E3_K10
    exp1_E5_K1 exp1_E5_K3 exp1_E5_K5 exp1_E5_K10
    exp1_E7_K1 exp1_E7_K3 exp1_E7_K5 exp1_E7_K10
    exp1_E5_K3_D1 exp1_E5_K3_D2 exp1_E5_K3_D4 exp1_E5_K3_D8
    exp1_E5_K3_permuted exp1_E5_K5_permuted exp1_E5_K10_permuted
    exp2_npairs_5000_uf_0p2 exp2_npairs_5000_uf_0p5 exp2_npairs_5000_uf_0p8
    simjoin_easy simjoin_medium simjoin_hard simjoin_mixed
    exp4_dispatch_gmm_K3 exp4_dispatch_hist_B50 exp4_dispatch_dir_k4
    exp4_micro_gmm_K3
    exp4_micro_hist_B20 exp4_micro_hist_B50 exp4_micro_hist_B100
    exp4_micro_dir_k4 exp4_micro_dir_k10 exp4_micro_dir_k20
    exp4_crosstype_gmm_hist exp4_crosstype_dir_hist
    exp4_E3_hist_B50 exp4_E3_hist_B100
    exp4_E5_hist_B50 exp4_E5_hist_B100
    exp4_E7_hist_B50 exp4_E7_hist_B100
    exp4_dirichlet
    exp5_gears_1000000_pass_0p01 exp5_gears_1000000_pass_0p05
    exp5_gears_1000000_pass_0p1 exp5_gears_1000000_pass_0p3
  )

  echo
  echo "[preflight] Checking local Fuseki services and loaded benchmark datasets..."
  local missing=()
  local dataset
  for dataset in "${datasets[@]}"; do
    if check_dataset_endpoint "${dataset}"; then
      echo "  OK      ${dataset}"
    else
      echo "  MISSING ${dataset} -> $(endpoint_for_dataset "${dataset}")"
      missing+=("${dataset}")
    fi
  done

  if (( ${#missing[@]} > 0 )); then
    echo
    echo "ERROR: ${#missing[@]} required benchmark dataset endpoint(s) are unavailable."
    echo "Start Fuseki on the target node and load the generated TTL files into services"
    echo "with the dataset names listed above, then resubmit this job."
    exit 1
  fi
}

start_local_fuseki() {
  local job_id="${SLURM_JOB_ID:-local}"
  FUSEKI_LOG="${PROJECT_ROOT}/benchmark/fuseki_benchmark_${job_id}.log"

  echo
  echo "[server] Starting local Fuseki on port ${FUSEKI_PORT}..."
  echo "[server] Data dir: ${BENCHMARK_DATA_DIR}"
  echo "[server] Log     : ${FUSEKI_LOG}"

  mvn -q exec:java \
    -Dprobsparql.simjoin.pruning=true \
    -Dprobsparql.simjoin.deduplicate=true \
    -Dexec.mainClass="org.apache.jena.probsparql.server.ProbSPARQLFuseki" \
    -Dexec.args="${FUSEKI_PORT} --benchmark-data ${BENCHMARK_DATA_DIR}" \
    >"${FUSEKI_LOG}" 2>&1 &

  FUSEKI_PID=$!
  export FUSEKI_PID

  echo "[server] Fuseki process PID: ${FUSEKI_PID}"
}

wait_for_local_fuseki() {
  local probe_dataset="exp1_E1_det"
  local timeout_seconds="${FUSEKI_STARTUP_TIMEOUT:-1800}"
  local start_ts
  start_ts="$(date +%s)"

  echo "[server] Waiting for Fuseki to finish loading datasets..."
  while true; do
    if check_dataset_endpoint "${probe_dataset}"; then
      echo "[server] Fuseki is ready."
      return 0
    fi

    if ! kill -0 "${FUSEKI_PID}" 2>/dev/null; then
      echo "ERROR: Fuseki process exited before becoming ready."
      echo "Check log: ${FUSEKI_LOG}"
      exit 1
    fi

    local now
    now="$(date +%s)"
    if (( now - start_ts > timeout_seconds )); then
      echo "ERROR: Timed out waiting for Fuseki after ${timeout_seconds}s."
      echo "Check log: ${FUSEKI_LOG}"
      exit 1
    fi

    sleep 10
  done
}

stop_local_fuseki() {
  if [[ -n "${FUSEKI_PID:-}" ]] && kill -0 "${FUSEKI_PID}" 2>/dev/null; then
    echo
    echo "[server] Stopping local Fuseki PID ${FUSEKI_PID}..."
    kill "${FUSEKI_PID}" 2>/dev/null || true
    wait "${FUSEKI_PID}" 2>/dev/null || true
  fi
}

trap stop_local_fuseki EXIT

echo "=================================================================="
echo "  ProbSPARQL full benchmark run"
echo "=================================================================="
echo "  Job ID       : ${SLURM_JOB_ID:-local}"
echo "  Node         : ${SLURM_NODELIST:-local}"
echo "  Project root : ${PROJECT_ROOT}"
echo "  Data dir     : ${BENCHMARK_DATA_DIR}"
echo "  Endpoint tpl : ${ENDPOINT_TEMPLATE}"
echo "  Java home    : ${JAVA_HOME}"
echo "  Start time   : $(date)"
echo "=================================================================="

echo
echo "[0/8] Building modified Jena snapshots..."
(
  cd jena
  mvn -q -pl jena-core,jena-arq -am \
    -DskipTests \
    -Drat.skip=true \
    -Dcyclonedx.skip=true \
    -Denforcer.skip=true \
    install
)

echo
echo "[1/8] Building ProbSPARQL once with Maven..."
mvn -q package -DskipTests
export SKIP_BUILD=1

start_local_fuseki
wait_for_local_fuseki
check_required_datasets

echo
echo "[2/8] Running Exp1 component..."
bash benchmark/scripts/Experiments1/component/run_exp1_component.sh

echo
echo "[3/8] Running Exp1 dimension..."
bash benchmark/scripts/Experiments1/dimension/run_exp1_dimension.sh

echo
echo "[4/8] Running Exp1 permutation..."
bash benchmark/scripts/Experiments1/permutation/run_exp1_permutation.sh

echo
echo "[5/8] Running Exp2..."
bash benchmark/scripts/Experiments2/run_exp2.sh

echo
echo "[6/8] Running Exp3..."
bash benchmark/scripts/Experiments3/run_exp3.sh

echo
echo "[7/8] Running Exp4..."
bash benchmark/scripts/Experiments4/run_exp4.sh

echo
echo "[8/8] Running Exp5..."
bash benchmark/scripts/Experiments5/run_exp5.sh

echo
echo "=================================================================="
echo "  All benchmark experiments completed"
echo "  End time: $(date)"
echo "  Results: benchmark/results/"
echo "=================================================================="
