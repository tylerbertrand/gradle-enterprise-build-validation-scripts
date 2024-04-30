#!/usr/bin/env bash

invoke_gradle() {
  local run_num args
  args=()
  run_num=$1
  shift

  local original_dir
  if [ -n "${project_dir}" ]; then
    original_dir="$(pwd)"
    cd "${project_dir}" > /dev/null 2>&1 || die "ERROR: Subdirectory ${project_dir} (set with --project-dir) does not exist in ${project_name}" "${INVALID_INPUT}"
  fi

  args+=(--init-script "${INIT_SCRIPTS_DIR}/configure-gradle-enterprise.gradle")

  if [ "$enable_ge" == "on" ]; then
    args+=("-Dcom.gradle.enterprise.build-validation.gradle.plugin-repository.url=https://plugins.gradle.org/m2")
    args+=("-Dcom.gradle.enterprise.build-validation.gradle-enterprise.plugin.version=3.14.1")
    args+=("-Dcom.gradle.enterprise.build-validation.ccud.plugin.version=2.0")
  fi

  if [ -n "${ge_server}" ]; then
    args+=("-Dcom.gradle.enterprise.build-validation.gradle-enterprise.url=${ge_server}")
    args+=("-Dcom.gradle.enterprise.build-validation.gradle-enterprise.allow-untrusted-server=false")
  fi

  if [[ "${build_scan_publishing_mode}" == "off" ]]; then
    args+=("-Dcom.gradle.enterprise.build-validation.omitServerUrlValidation=true")
    args+=("-Dscan.dump")
  fi

  args+=(
    -Dcom.gradle.enterprise.build-validation.expDir="${EXP_DIR}"
    -Dcom.gradle.enterprise.build-validation.expId="${EXP_SCAN_TAG}"
    -Dcom.gradle.enterprise.build-validation.runId="${RUN_ID}"
    -Dcom.gradle.enterprise.build-validation.runNum="${run_num}"
    -Dcom.gradle.enterprise.build-validation.scriptsVersion="${SCRIPT_VERSION}"
    -Dscan.capture-task-input-files=true
  )

  # https://stackoverflow.com/a/31485948
  while IFS= read -r -d ''; do
    local extra_arg="$REPLY"
    if [ -n "$extra_arg" ]; then
      args+=("$extra_arg")
    fi
  done < <(xargs printf '%s\0' <<<"$extra_args")

  args+=("$@")

  rm -f "${EXP_DIR}/errors.txt"

  debug "Current directory: $(pwd)"
  debug ./gradlew "${args[@]}"

  if ./gradlew "${args[@]}"; then
      build_outcomes+=("SUCCESSFUL")
  else
      build_outcomes+=("FAILED")
  fi

  if [ -f "${EXP_DIR}/errors.txt" ]; then
    print_bl
    die "ERROR: Experiment aborted due to a non-recoverable failure: $(cat "${EXP_DIR}/errors.txt")"
  fi

  if [[ "${build_scan_publishing_mode}" == "on" ]] && is_build_scan_metadata_missing "$run_num"; then
    print_bl
    die "ERROR: Experiment aborted due to a non-recoverable failure: No Build Scan was published"
  fi

  # defined in git.sh
  read_git_metadata_from_current_repo
  requested_tasks+=("${tasks}")

  if [ -n "${project_dir}" ]; then
    # shellcheck disable=SC2164 # We are just navigating back to the original directory
    cd "${original_dir}"
  fi
}

make_local_cache_dir() {
  rm -rf "${BUILD_CACHE_DIR}"
  mkdir -p "${BUILD_CACHE_DIR}"
}
