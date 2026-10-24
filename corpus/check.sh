#!/usr/bin/env bash
# Проверка корпуса вручную: каждый пример компилируется, каждый вход выполняется дважды с одинаковым результатом.
# Запуск из корня репозитория: corpus/check.sh [пример...]   (нужен JDK 21)
set -euo pipefail
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS || true
examples=("$@")
[ ${#examples[@]} -eq 0 ] && examples=($(ls -d */ | tr -d /))
status=0
for ex in "${examples[@]}"; do
  out=$(mktemp -d)
  if ! javac -encoding UTF-8 -d "$out" $(find "$ex" -name '*.java') 2>"$out/javac.log"; then
    echo "✗ $ex: не компилируется"; cat "$out/javac.log"; status=1; continue
  fi
  lines=$(cat $(find "$ex" -name '*.java') | wc -l)
  for input in "$ex"/inputs/*.txt; do
    start=$(date +%s%N)
    set +e
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$out" Main <"$input" >"$out/a.out" 2>"$out/a.err"; c1=$?
    ms=$(( ($(date +%s%N) - start) / 1000000 ))
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$out" Main <"$input" >"$out/b.out" 2>"$out/b.err"; c2=$?
    set -e
    if [ $c1 -ne $c2 ] || ! cmp -s "$out/a.out" "$out/b.out" || ! cmp -s "$out/a.err" "$out/b.err"; then
      echo "✗ $ex/$(basename "$input"): недетерминированный вывод"; status=1
    else
      printf '✓ %-40s exit=%d  %5d мс  stdout %5d строк\n' "$ex/$(basename "$input")" $c1 $ms "$(wc -l <"$out/a.out")"
    fi
  done
  echo "  ($lines строк Java)"
  rm -rf "$out"
done
exit $status
