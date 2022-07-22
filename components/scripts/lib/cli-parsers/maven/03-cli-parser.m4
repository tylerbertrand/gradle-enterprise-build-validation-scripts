#!/usr/bin/env bash
# shellcheck disable=SC2034  # It is common for variables in this auto-generated file to go unused
# Created by argbash-init v2.10.0
# ARG_HELP([This function is overridden later on.])
# ARG_VERSION([print_version],[v],[version],[])
# ARG_OPTIONAL_BOOLEAN([interactive],[i],[],[off])
# ARG_OPTIONAL_SINGLE([first-build-ci],[1],[])
# ARG_OPTIONAL_SINGLE([second-build-ci],[2],[])
# ARG_OPTIONAL_BOOLEAN([debug],[],[],[off])
# ARG_OPTIONAL_SINGLE([mapping-file],[m],[])
# ARGBASH_SET_INDENT([  ])
# ARGBASH_PREPARE()
# needed because of Argbash --> m4_ignore([
### START OF CODE GENERATED BY Argbash v2.10.0 one line above ###
# Argbash is a bash code generator used to get arguments parsing right.
# Argbash is FREE SOFTWARE, see https://argbash.io for more info
### END OF CODE GENERATED BY Argbash (sortof) ### ])
# [ <-- needed because of Argbash
function print_help() {
  echo "Assists in validating that a Maven build is optimized for remote build caching when invoked from different CI agents."
  print_bl
  print_script_usage
  print_option_usage -i
  print_option_usage "-1, --first-build-ci" "Specifies the URL for the build scan of the first build run by a CI agent."
  print_option_usage "-2, --second-build-ci" "Specifies the URL for the build scan of the second build run by a CI agent."
  print_option_usage -m
  print_option_usage -x
  print_option_usage -v
  print_option_usage -h
}
# ] <-- needed because of Argbash
